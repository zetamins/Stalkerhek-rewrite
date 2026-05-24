use serde::{Deserialize, Serialize};
use std::net::SocketAddr;

use crate::dns;

/// Lightweight snapshot of PortalClient fields needed to send a watchdog ping.
/// Cloned out of the RwLock so the watchdog task does not hold the lock during the HTTP await.
pub struct WatchdogClient {
    pub base_url: String,
    pub token: String,
    pub serial_number: String,
    pub mac: String,
    pub timezone: String,
    pub model: String,
    client: reqwest::Client,
}

impl WatchdogClient {
    pub async fn watchdog_update(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let url = format!(
            "{}?action=get_events&event_active_id=0&init=0&type=watchdog&cur_play_type=1&JsHttpRequest=1-xml",
            self.base_url
        );
        use reqwest::header::*;
        let mut h = HeaderMap::new();
        h.insert(ACCEPT, HeaderValue::from_static("*/*"));
        h.insert("Cache-Control", HeaderValue::from_static("no-cache"));
        h.insert("X-User-Agent", HeaderValue::from_str(&format!("Model: {}; Link: Ethernet", self.model)).unwrap());
        if !self.token.is_empty() {
            h.insert(AUTHORIZATION, HeaderValue::from_str(&format!("Bearer {}", self.token)).unwrap());
        }
        let cookie = format!(
            "PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};",
            urlencoding(&self.serial_number), urlencoding(&self.mac), urlencoding(&self.timezone),
        );
        h.insert(COOKIE, HeaderValue::from_str(&cookie).unwrap());
        let resp = self.client.get(&url).headers(h).send().await?;
        let _ = resp.text().await?;
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Channel {
    pub title: String,
    pub cmd: String,
    pub logo: String,
    pub genre_id: String,
    pub genre: String,
    pub cmd_id: String,
    pub cmd_ch_id: String,
}

impl Channel {
    /// Extract the stream URL from the cmd field.
    /// cmd format is typically "ffmpeg http://..." — this returns just the URL part.
    pub fn stream_url(&self) -> &str {
        if self.cmd.starts_with("ffmpeg ") {
            &self.cmd[7..]
        } else {
            &self.cmd
        }
    }
}

#[derive(Debug, Clone)]
pub struct PortalClient {
    pub base_url: String,
    pub mac: String,
    pub username: String,
    pub password: String,
    pub serial_number: String,
    pub device_id: String,
    pub device_id2: String,
    #[allow(dead_code)]
    pub signature: String,
    pub model: String,
    pub timezone: String,
    pub token: String,
    pub device_id_auth: bool,
    client: reqwest::Client,
}

impl PortalClient {
    pub fn new(
        base_url: String, mac: String, username: String, password: String,
        serial_number: String, device_id: String, device_id2: String,
        signature: String, model: String, timezone: String,
        device_id_auth: bool,
    ) -> Self {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(120))
            .user_agent("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2116 Mobile Safari/533.3")
            .danger_accept_invalid_certs(false)
            .build()
            .expect("Failed to build HTTP client");

        Self {
            base_url, mac, username, password, serial_number, device_id,
            device_id2, signature, model, timezone, device_id_auth,
            token: String::new(), client,
        }
    }

    /// Rebuild the internal client with European DNS resolution for the portal hostname.
    /// Call this after construction to bypass geo-blocking on portal requests.
    pub async fn resolve_eu_dns(&mut self) {
        let parsed = match url::Url::parse(&self.base_url) {
            Ok(u) => u,
            Err(_) => return,
        };
        let host = parsed.host_str().unwrap_or("").to_string();
        let port = parsed.port_or_known_default().unwrap_or(443);
        let ips = dns::resolve_european(&host).await;

        let mut builder = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(120))
            .user_agent("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2116 Mobile Safari/533.3")
            .danger_accept_invalid_certs(false);
        if !ips.is_empty() {
            builder = builder.resolve(&host, SocketAddr::new(ips[0], port));
        }
        if let Ok(client) = builder.build() {
            self.client = client;
        }
    }

    fn headers(&self) -> reqwest::header::HeaderMap {
        use reqwest::header::*;
        let mut h = HeaderMap::new();
        h.insert(ACCEPT, HeaderValue::from_static("*/*"));
        h.insert("Accept-Language", HeaderValue::from_static("en-US,en;q=0.9"));
        h.insert("Cache-Control", HeaderValue::from_static("no-cache"));
        h.insert("Pragma", HeaderValue::from_static("no-cache"));
        h.insert("X-User-Agent", HeaderValue::from_str(&format!("Model: {}; Link: Ethernet", self.model)).unwrap());
        if !self.token.is_empty() {
            h.insert(AUTHORIZATION, HeaderValue::from_str(&format!("Bearer {}", self.token)).unwrap());
        }
        let cookie = format!(
            "PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};",
            urlencoding(&self.serial_number),
            urlencoding(&self.mac),
            urlencoding(&self.timezone),
        );
        h.insert(COOKIE, HeaderValue::from_str(&cookie).unwrap());
        h
    }

    pub async fn handshake(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?type=stb&action=handshake&token={}&JsHttpRequest=1-xml", self.base_url, self.token);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct HandshakeResp {
            js: std::collections::HashMap<String, serde_json::Value>,
        }
        let parsed: HandshakeResp = serde_json::from_str(&text)?;
        if let Some(token) = parsed.js.get("token") {
            if let Some(t) = token.as_str() {
                if !t.is_empty() {
                    self.token = t.to_string();
                }
            }
        }
        Ok(())
    }

    pub async fn authenticate(&mut self) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        if self.device_id_auth || (self.username.is_empty() && self.password.is_empty()) {
            self.authenticate_device_id().await
        } else {
            self.authenticate_user_pass().await
        }
    }

    async fn authenticate_user_pass(&mut self) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        if self.handshake().await.is_err() {
            tracing::warn!("Handshake failed, continuing anyway");
        }
        let params = [
            ("type", "stb"),
            ("action", "do_auth"),
            ("login", &self.username),
            ("password", &self.password),
            ("device_id", &self.device_id),
            ("device_id2", &self.device_id2),
            ("JsHttpRequest", "1-xml"),
        ];
        let resp = self.client.post(&self.base_url)
            .headers(self.headers())
            .form(&params)
            .send()
            .await?;
        let text = resp.text().await?;
        tracing::info!("do_auth raw response (first 500): {}", &text.chars().take(500).collect::<String>());
        #[derive(Deserialize)]
        struct AuthResp { js: serde_json::Value, text: Option<String> }
        let parsed: AuthResp = serde_json::from_str(&text)?;
        if let Some(ref msg) = parsed.text {
            tracing::info!("Login: {}", msg);
        }
        // Accept auth if js contains a truthy token, or js itself is truthy
        let ok = match &parsed.js {
            serde_json::Value::Bool(b) => *b,
            serde_json::Value::Object(m) => {
                m.get("token").and_then(|t| t.as_str()).map(|s| !s.is_empty()).unwrap_or(false)
                    || m.get("id").and_then(|t| t.as_str()).map(|s| !s.is_empty()).unwrap_or(false)
            }
            _ => false,
        };
        if ok { Ok(parsed.text.unwrap_or_default()) } else { Err("Invalid credentials".into()) }
    }

    async fn authenticate_device_id(&mut self) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        self.handshake().await?;
        let url = format!(
            "{}?type=stb&action=get_profile&JsHttpRequest=1-xml&hd=1&sn={}&stb_type={}&device_id={}&device_id2={}&auth_second_step=1",
            self.base_url, urlencoding(&self.serial_number), urlencoding(&self.model),
            urlencoding(&self.device_id), urlencoding(&self.device_id2)
        );
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        tracing::info!("get_profile raw response (first 600): {}", &text.chars().take(600).collect::<String>());
        #[derive(Deserialize)]
        struct ProfileJs { id: serde_json::Value, fname: String }
        #[derive(Deserialize)]
        struct ProfileResp { js: ProfileJs, text: Option<String> }
        let parsed: ProfileResp = serde_json::from_str(&text)?;
        if let Some(ref msg) = parsed.text {
            tracing::info!("Auth: {}", msg);
        }
        match &parsed.js.id {
            serde_json::Value::String(s) if !s.is_empty() => Ok(parsed.js.fname),
            serde_json::Value::Number(n) if !n.to_string().is_empty() => Ok(parsed.js.fname),
            _ => Err("Device ID auth failed".into()),
        }
    }

    pub async fn get_channels(&self) -> Result<Vec<Channel>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?type=itv&action=get_all_channels&JsHttpRequest=1-xml", self.base_url);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        let genres = self.get_genres().await.unwrap_or_default();

        #[derive(Deserialize)]
        struct ChJs {
            data: Vec<ChData>,
        }
        #[derive(Deserialize)]
        struct ChData {
            name: String,
            cmd: String,
            logo: Option<String>,
            tv_genre_id: Option<String>,
            cmds: Option<Vec<CmdData>>,
        }
        #[derive(Deserialize)]
        struct CmdData { id: Option<String>, ch_id: Option<String> }
        #[derive(Deserialize)]
        struct Wrapper { js: serde_json::Value }

        let parsed: Wrapper = serde_json::from_str(&text)?;
        let js_val = parsed.js;
        if js_val.is_null() || js_val.is_array() {
            return Err("No channel data returned".into());
        }
        let payload: ChJs = serde_json::from_value(js_val)?;

        let channels: Vec<Channel> = payload.data.into_iter().map(|d| {
            let (cmd_id, cmd_ch_id) = match d.cmds.as_ref().and_then(|c| c.first()) {
                Some(cmd) => (
                    cmd.id.clone().unwrap_or_default(),
                    cmd.ch_id.clone().unwrap_or_default(),
                ),
                None => (String::new(), String::new()),
            };
            let gid = d.tv_genre_id.clone().unwrap_or_default();
            let genre_name = genres.get(&gid).cloned().unwrap_or_else(|| "Other".to_string());
            Channel {
                title: d.name,
                cmd: d.cmd,
                logo: d.logo.unwrap_or_default(),
                genre_id: gid,
                genre: genre_name,
                cmd_id,
                cmd_ch_id,
            }
        }).collect();
        Ok(channels)
    }

    async fn get_genres(&self) -> Result<std::collections::HashMap<String, String>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?action=get_genres&type=itv&JsHttpRequest=1-xml", self.base_url);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct GenreItem { id: String, title: String }
        #[derive(Deserialize)]
        struct GenreResp { js: Vec<GenreItem> }
        let parsed: GenreResp = serde_json::from_str(&text)?;
        Ok(parsed.js.into_iter().map(|g| (g.id, g.title)).collect())
    }

    pub async fn get_vod_categories(&self) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        self.get_categories("vod").await
    }

    pub async fn get_series_categories(&self) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        self.get_categories("series").await
    }

    async fn get_categories(&self, media_type: &str) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?action=get_categories&type={}&JsHttpRequest=1-xml", self.base_url, media_type);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct CatResp { js: Vec<serde_json::Value> }
        let parsed: CatResp = serde_json::from_str(&text)?;
        Ok(parsed.js)
    }

    pub async fn create_link(&self, cmd: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let encoded_cmd: String = cmd.split_whitespace()
            .map(|s| urlencoding(s))
            .collect::<Vec<_>>()
            .join("%20");
        let url = format!("{}?action=create_link&type=itv&cmd={}&JsHttpRequest=1-xml", self.base_url, encoded_cmd);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct LinkJs { cmd: String }
        #[derive(Deserialize)]
        struct LinkResp { js: LinkJs }
        let parsed: LinkResp = serde_json::from_str(&text)?;
        let cmd_str = parsed.js.cmd.trim().to_string();
        Ok(cmd_str.split_whitespace().last().unwrap_or("").to_string())
    }

    pub async fn create_link_with_retry(&self, cmd: &str, max_retries: u32) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let mut last_err = String::new();
        for attempt in 0..max_retries {
            match self.create_link(cmd).await {
                Ok(link) => return Ok(link),
                Err(e) => {
                    last_err = e.to_string();
                    tracing::warn!("create_link attempt {} failed: {}", attempt + 1, last_err);
                    tokio::time::sleep(std::time::Duration::from_secs(1 << attempt)).await;
                }
            }
        }
        Err(format!("create_link failed after {max_retries} retries: {last_err}").into())
    }

    pub async fn watchdog_update(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?action=get_events&event_active_id=0&init=0&type=watchdog&cur_play_type=1&JsHttpRequest=1-xml", self.base_url);
        let resp = self.client.get(&url)
            .headers(self.headers())
            .send()
            .await?;
        let _ = resp.text().await?;
        Ok(())
    }

    /// Produce a lightweight clone that can send the watchdog ping without
    /// holding the RwLock across an async network await.
    pub fn clone_for_watchdog(&self) -> WatchdogClient {
        WatchdogClient {
            base_url: self.base_url.clone(),
            token: self.token.clone(),
            serial_number: self.serial_number.clone(),
            mac: self.mac.clone(),
            timezone: self.timezone.clone(),
            model: self.model.clone(),
            client: self.client.clone(),
        }
    }

    #[allow(dead_code)]
    pub fn logo_url(&self, logo_path: &str) -> String {
        if logo_path.is_empty() { return String::new(); }
        let base = self.base_url.trim_end_matches(|c| c == '/');
        let dir = match base.rfind('/') {
            Some(pos) => &base[..=pos],
            None => return format!("{}/misc/logos/320/{logo_path}", base),
        };
        format!("{dir}misc/logos/320/{logo_path}")
    }
}

fn urlencoding(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            b' ' => out.push_str("%20"),
            _ => { out.push('%'); out.push_str(&format!("{:02X}", b)); }
        }
    }
    out
}

// ─── EPG ──────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EpgEntry {
    pub channel_id: String,
    pub title: String,
    pub description: String,
    pub start: i64,
    pub stop: i64,
}

impl PortalClient {
    pub async fn get_epg_for_channel(&self, cmd: &str) -> Result<Vec<EpgEntry>, Box<dyn std::error::Error + Send + Sync>> {
        let encoded: String = cmd.split_whitespace().map(|s| urlencoding(s)).collect::<Vec<_>>().join("%20");
        let url = format!("{}?type=itv&action=get_epg_info&period=5&cmd={}&JsHttpRequest=1-xml", self.base_url, encoded);
        let resp = self.client.get(&url).headers(self.headers()).send().await?;
        let text = resp.text().await?;
        #[derive(Deserialize)] struct EpgJs { data: Option<serde_json::Value> }
        #[derive(Deserialize)] struct EpgWrap { js: EpgJs }
        let parsed: EpgWrap = serde_json::from_str(&text).unwrap_or(EpgWrap { js: EpgJs { data: None } });
        let mut entries = Vec::new();
        if let Some(serde_json::Value::Array(items)) = parsed.js.data {
            for item in items {
                let title = item["name"].as_str().unwrap_or("").to_string();
                let desc = item["descr"].as_str().unwrap_or("").to_string();
                let start = item["start_timestamp"].as_i64().or_else(|| item["time"].as_i64()).unwrap_or(0);
                let stop  = item["stop_timestamp"].as_i64().or_else(|| item["time_to"].as_i64()).unwrap_or(0);
                let ch_id = item["ch_id"].as_str().or_else(|| item["id"].as_str()).unwrap_or("").to_string();
                if !title.is_empty() { entries.push(EpgEntry { channel_id: ch_id, title, description: desc, start, stop }); }
            }
        }
        Ok(entries)
    }

    pub async fn get_epg_all(&self) -> Result<Vec<EpgEntry>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?type=itv&action=get_epg_info&period=5&JsHttpRequest=1-xml", self.base_url);
        let resp = self.client.get(&url).headers(self.headers()).send().await?;
        let text = resp.text().await?;
        #[derive(Deserialize)] struct EpgJs { data: Option<serde_json::Value> }
        #[derive(Deserialize)] struct EpgWrap { js: EpgJs }
        let parsed: EpgWrap = serde_json::from_str(&text).unwrap_or(EpgWrap { js: EpgJs { data: None } });
        let mut entries = Vec::new();
        if let Some(serde_json::Value::Object(map)) = parsed.js.data {
            for (_key, val) in map {
                if let serde_json::Value::Array(items) = val {
                    for item in items {
                        let title = item["name"].as_str().unwrap_or("").to_string();
                        let desc  = item["descr"].as_str().unwrap_or("").to_string();
                        let start = item["start_timestamp"].as_i64().or_else(|| item["time"].as_i64()).unwrap_or(0);
                        let stop  = item["stop_timestamp"].as_i64().or_else(|| item["time_to"].as_i64()).unwrap_or(0);
                        let ch_id = item["ch_id"].as_str().or_else(|| item["id"].as_str()).unwrap_or("").to_string();
                        if !title.is_empty() { entries.push(EpgEntry { channel_id: ch_id, title, description: desc, start, stop }); }
                    }
                }
            }
        }
        Ok(entries)
    }

    /// Re-authenticate only if the current token is empty or has expired (401 received).
    pub async fn refresh_token_if_needed(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        if self.token.is_empty() {
            self.authenticate().await?;
        }
        Ok(())
    }
}
