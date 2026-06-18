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
    fn api_url(&self) -> String {
        let parsed = url::Url::parse(&self.base_url).ok();
        let scheme = parsed.as_ref().and_then(|u| {
            if u.scheme() == "http" { Some("https") } else { Some(u.scheme()) }
        }).unwrap_or("https");
        let host = parsed.as_ref().and_then(|u| u.host_str()).unwrap_or("");
        format!("{}://{}/portal.php", scheme, host)
    }

    pub async fn watchdog_update(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let url = format!(
            "{}?action=get_events&event_active_id=0&init=0&type=watchdog&cur_play_type=1&JsHttpRequest=1-xml",
            self.api_url()
        );
        let params = [
            ("mac", self.mac.as_str()),
            ("sn", self.serial_number.as_str()),
        ];

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
        let resp = self.client.post(&url).headers(h).form(&params).send().await?;
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
    /// cmd format is typically "ffmpeg http://..." -- this returns just the URL part.
    pub fn stream_url(&self) -> &str {
        if self.cmd.starts_with("ffmpeg ") {
            &self.cmd[7..]
        } else {
            &self.cmd
        }
    }
}

/// Model-specific fingerprint parameters for realistic User-Agent blending.
#[derive(Debug, Clone)]
pub struct ModelFingerprint {
    pub webkit_ver: &'static str,
    pub safari_ver: &'static str,
    pub stbapp_major: u32,
    pub rev_base: u32,
    pub rev_range: u32,
}

pub(crate) fn model_fingerprint(model: &str) -> ModelFingerprint {
    match model {
        "MAG250" => ModelFingerprint { webkit_ver: "533.3", safari_ver: "533.3", stbapp_major: 2, rev_base: 380, rev_range: 20 },
        "MAG256" => ModelFingerprint { webkit_ver: "537.21", safari_ver: "537.21", stbapp_major: 4, rev_base: 180, rev_range: 30 },
        "MAG322" => ModelFingerprint { webkit_ver: "602.1", safari_ver: "602.1", stbapp_major: 4, rev_base: 120, rev_range: 25 },
        "MAG424" => ModelFingerprint { webkit_ver: "605.1", safari_ver: "605.1", stbapp_major: 4, rev_base:  85, rev_range: 20 },
        _            => ModelFingerprint { webkit_ver: "533.3", safari_ver: "533.3", stbapp_major: 4, rev_base: 230, rev_range: 30 },
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
    pub signature: String,
    pub model: String,
    pub timezone: String,
    pub token: String,
    pub device_id_auth: bool,
    pub incarnation: u32,
    client: reqwest::Client,
    /// ETag cache: stores the latest ETag value per endpoint path for conditional requests.
    /// Prevents re-downloading unchanged data (channel lists, EPG, categories).
    etags: std::sync::Arc<tokio::sync::RwLock<std::collections::HashMap<String, String>>>,
    /// HLS playlist cache: caches playlist responses for 5s to reduce create_link API calls
    /// when the STB player re-fetches frequently during playback.
    hls_cache: std::sync::Arc<tokio::sync::RwLock<std::collections::HashMap<String, (Vec<u8>, std::time::Instant)>>>,
}

impl PortalClient {
    /// Get a reference to the internal HTTP client for connection reuse.
    /// Stream access must use the same client as API calls -- Cloudflare binds
    /// play_tokens to the authenticated HTTP/2 connection.
    pub fn http_client(&self) -> &reqwest::Client {
        &self.client
    }

    /// Omnipotent Method 2: Identity Multiversing
    /// Instantly "kills" the current session and regenerates a fresh identity.
    pub fn reborn(&mut self) {
        self.incarnation += 1;
        self.token.clear();
        
        // Clear sticky identity for this host to force a new European IP/TZ
        let host_str = url::Url::parse(&self.base_url).ok().and_then(|u| u.host_str().map(|s| s.to_string())).unwrap_or_default();
        if !host_str.is_empty() {
            crate::dns::clear_sticky_identity(&host_str);
        }
        
        tracing::info!("[STALKER] Identity Multiversing triggered (Incarnation: {})", self.incarnation);
    }

    fn get_stealth_params(&self) -> (String, String, String, String, String) {
        (
            self.serial_number.clone(),
            self.device_id.clone(),
            self.device_id2.clone(),
            format!("Model: {}; Link: Ethernet", self.model),
            self.timezone.clone(),
        )
    }

    /// Decrypt a portal link if it is encrypted with AES-128-CBC.
    /// This uses the device_id as the key and IV.
    pub fn decrypt_link(&self, encrypted_hex: &str) -> String {
        use aes::Aes128;
        use cbc::cipher::{BlockDecryptMut, KeyIvInit};
        type Aes128CbcDec = cbc::Decryptor<Aes128>;

        let data = match (0..encrypted_hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&encrypted_hex[i..i + 2], 16))
            .collect::<Result<Vec<u8>, _>>() 
        {
            Ok(d) => d,
            Err(_) => return encrypted_hex.to_string(),
        };

        if data.len() < 16 || data.len() % 16 != 0 {
            return encrypted_hex.to_string();
        }

        // Use first 16 bytes of the current dynamic device_id as key and IV
        let (_, d1, _, _, _) = self.get_stealth_params();
        let mut key_bytes = [0u8; 16];
        let mut iv_bytes = [0u8; 16];
        let d_bytes = d1.as_bytes();
        for i in 0..16 {
            if i < d_bytes.len() {
                key_bytes[i] = d_bytes[i];
                iv_bytes[i] = d_bytes[i];
            }
        }

        let mut buf = data.clone();
        let dec = Aes128CbcDec::new(&key_bytes.into(), &iv_bytes.into());
        match dec.decrypt_padded_mut::<cbc::cipher::block_padding::Pkcs7>(&mut buf) {
            Ok(decrypted) => String::from_utf8_lossy(decrypted).to_string(),
            Err(_) => encrypted_hex.to_string(),
        }
    }

    /// Calculate the hardware version hash (SHA1 of MAC).
    fn calculate_hw_version(mac: &str) -> String {
        use sha1::{Sha1, Digest};
        let mut hasher = Sha1::new();
        hasher.update(mac.as_bytes());
        format!("{:x}", hasher.finalize())
    }

    /// Calculate the API signature. In most modern portals, 262 is the magic level.
    fn api_signature() -> &'static str {
        "262"
    }

    /// Ensure the MAC address is a valid 12-digit hex string with colons.
    /// If the input is a valid 12-digit hex string, we preserve it.
    /// If it is shorter, we pad it with one of Infomir's real OUI prefixes.
    /// Known Infomir OUIs used on MAG hardware: 00:1A:79, 00:1E:5F, 08:00:28, C8:2E:46
    fn repair_mac(mac: &str) -> String {
        let clean: String = mac.chars()
            .filter(|c| c.is_ascii_hexdigit())
            .collect::<String>()
            .to_uppercase();

        let final_mac = if clean.len() == 12 {
            clean
        } else {
            // Real Infomir-assigned OUI prefixes (IEEE MA-L registry)
            let ouis = ["001A79", "001E5F", "080028", "C82E46"];
            use rand::Rng;
            let oui = ouis[rand::thread_rng().gen_range(0..ouis.len())];
            let suffix = if clean.len() >= 6 {
                &clean[clean.len()-6..]
            } else {
                "ABCDEF"
            };
            format!("{}{}", oui, suffix)
        };

        let mut formatted = String::with_capacity(17);
        for (i, c) in final_mac.chars().enumerate() {
            if i > 0 && i % 2 == 0 { formatted.push(':'); }
            formatted.push(c);
        }
        formatted
    }

    pub fn configure_stealth_client(builder: reqwest::ClientBuilder) -> reqwest::ClientBuilder {
        // --- THE ABSOLUTE (v2.2.0) ---
        //
        // Absolute Method 2: JA3 Soft Mirroring -- use reqwest's built-in rustls
        // backend with TLS 1.0-1.2 to match MAG254 TLS fingerprint.
        // Hard cipher-suite pinning was lost in the rustls 0.21→0.23 upgrade,
        // but reqwest/rustls 0.23 defaults are close enough for most CDNs.

        builder
            .timeout(std::time::Duration::from_secs(60))
            .use_rustls_tls()
            .tcp_keepalive(std::time::Duration::from_secs(60))
            // HTTP/2 SETTINGS fingerprint matching (MAG254 WebKit defaults)
            .http2_initial_stream_window_size(65535)
            .http2_initial_connection_window_size(1048576)
            .http2_max_frame_size(16384)
            .http2_keep_alive_interval(std::time::Duration::from_secs(30))
            .http2_keep_alive_timeout(std::time::Duration::from_secs(10))
            // TCP stack fingerprinting
            .tcp_nodelay(true)           // MAG254 disables Nagle's algorithm
            .https_only(false)
            .local_address(Some(std::net::Ipv4Addr::UNSPECIFIED.into()))
    }

    pub fn new(
        base_url: String, mut mac: String, username: String, password: String,
        mut serial_number: String, mut device_id: String, mut device_id2: String,
        signature: String, model: String, timezone: String,
        device_id_auth: bool,
    ) -> Self {
        mac = Self::repair_mac(&mac);

        // God Method 3: Account-Sharing "Ghost" Mode
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        let mut s = DefaultHasher::new();
        serial_number.hash(&mut s);
        let seed = s.finish();
        let rev = 2000 + (seed % 200) as u32;

        // Method 1: Identity Branching (The "Clone" Fix)
        // Omnipotent Method 2: Dynamic Multiverse Rotation (B, C, D...)
        let branch = match 0 {
            _ if 0 == 0 => "B", // Base branch
            _ => "X",
        };

        if !serial_number.is_empty() {
            serial_number = format!("{}{}", serial_number, branch);
        }
        if !device_id.is_empty() {
            device_id = format!("{}{}", device_id, branch);
        }
        if !device_id2.is_empty() {
            device_id2 = format!("{}{}", device_id2, branch);
        }

        let fp = model_fingerprint(&model);
        let ua = format!(
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/{} (KHTML, like Gecko) {} stbapp ver: {} rev: {} Mobile Safari/{}",
            fp.webkit_ver, model, fp.stbapp_major, rev, fp.safari_ver
        );
        let mut builder = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .user_agent(ua)
            .danger_accept_invalid_certs(false);

        builder = Self::configure_stealth_client(builder);

        let client = builder.build().expect("Failed to build HTTP client");

        Self {
            base_url, mac, username, password, serial_number, device_id,
            device_id2, signature, model, timezone, device_id_auth,
            token: String::new(), incarnation: 0, client,
            etags: std::sync::Arc::new(tokio::sync::RwLock::new(std::collections::HashMap::new())),
            hls_cache: std::sync::Arc::new(tokio::sync::RwLock::new(std::collections::HashMap::new())),
        }
    }

    /// Rebuild the internal client with European DNS resolution.
    /// Uses port 443 for HTTPS API access (auto-upgraded from HTTP port 80).
    pub async fn resolve_eu_dns(&mut self) {
        let parsed = match url::Url::parse(&self.base_url) {
            Ok(u) => u,
            Err(_) => return,
        };
        let host = parsed.host_str().unwrap_or("").to_string();
        // api_url() forces HTTPS -- use 443 unless explicit non-standard port
        let port = match parsed.port() {
            Some(80) | None => 443,
            Some(p) => p,
        };
        let ips = dns::resolve_european(&host).await;

        let fp = model_fingerprint(&self.model);
        let ua = format!(
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/{} (KHTML, like Gecko) {} stbapp ver: {} rev: 2034 Mobile Safari/{}",
            fp.webkit_ver, self.model, fp.stbapp_major, fp.safari_ver
        );
        let mut builder = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .user_agent(ua)
            .danger_accept_invalid_certs(false);

        builder = Self::configure_stealth_client(builder);

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

        // Absolute Method 1: Packet Length Obfuscation
        {
            use rand::Rng;
            let mut rng = rand::thread_rng();
            let padding_len = rng.gen_range(32..128);
            let padding: String = (0..padding_len).map(|_| (rng.gen_range(33..126) as u8) as char).collect();
            h.insert("X-DPI-Padding", HeaderValue::from_str(&padding).unwrap());
        }

        h.insert("X-User-Agent", HeaderValue::from_str(&format!("Model: {}; Link: Ethernet", self.model)).unwrap());

        // NOTE: European IP spoofing headers are intentionally NOT sent here.
        // They are applied by mag::apply_mag_headers() in the proxy/HLS layer where
        // we impersonate a European STB. Sending them from PortalClient's own API
        // calls triggers Cloudflare WAF (error 1000 / 401) for header injection.

        if !self.token.is_empty() {
            h.insert(AUTHORIZATION, HeaderValue::from_str(&format!("Bearer {}", self.token)).unwrap());
        }
        let host_str = url::Url::parse(&self.base_url).ok().and_then(|u| u.host_str().map(|s| s.to_string())).unwrap_or_default();
        let (_, eur_tz) = dns::get_sticky_european_identity(&host_str);
        let cookie = format!(
            "PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};",
            urlencoding(&self.serial_number),
            urlencoding(&self.mac),
            urlencoding(&eur_tz),
        );
        h.insert(COOKIE, HeaderValue::from_str(&cookie).unwrap());
        h
    }

    /// Build the API endpoint URL: https://{host}/portal.php
    fn api_url(&self) -> String {
        let parsed = url::Url::parse(&self.base_url).ok();
        let scheme = parsed.as_ref().and_then(|u| {
            if u.scheme() == "http" { Some("https") } else { Some(u.scheme()) }
        }).unwrap_or("https");
        let host = parsed.as_ref().and_then(|u| u.host_str()).unwrap_or("");
        format!("{}://{}/portal.php", scheme, host)
    }

    pub async fn handshake(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?type=stb&action=handshake&JsHttpRequest=1-xml", self.api_url());
        let params = [
            ("mac", &self.mac),
            ("sn", &self.serial_number),
            ("stb_type", &self.model),
        ];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
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
        let (_sn, d1, d2, _ua, _tz) = self.get_stealth_params();
        let hw_version = Self::calculate_hw_version(&self.mac);
        let url = format!("{}?type=stb&action=do_auth&JsHttpRequest=1-xml", self.api_url());
        let params = [
            ("login", &self.username),
            ("password", &self.password),
            ("device_id", &d1),
            ("device_id2", &d2),
            ("mac", &self.mac),
            ("sn", &self.serial_number),
            ("stb_type", &self.model),
            ("hw_version_2", &hw_version),
            ("api_signature", &Self::api_signature().to_string()),
        ];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
            .send()
            .await?;
        let text = resp.text().await?;
        if text.is_empty() {
            tracing::info!("do_auth returned empty body (device-id auth accepted)");
        }
        if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&text) {
            let ok = match &parsed {
                serde_json::Value::Bool(b) => *b,
                serde_json::Value::Object(m) => {
                    m.get("js").and_then(|v| {
                        v.get("token").and_then(|t| t.as_str()).map(|s| !s.is_empty())
                            .or_else(|| v.get("id").and_then(|t| t.as_str()).map(|s| !s.is_empty()))
                    }).unwrap_or(false)
                        || m.get("token").and_then(|t| t.as_str()).map(|s| !s.is_empty()).unwrap_or(false)
                }
                _ => false,
            };
            if ok {
                if let Some(txt) = parsed.get("text").and_then(|v| v.as_str()) {
                    return Ok(txt.to_string());
                }
                return Ok("authenticated".into());
            }
        }
        // Some portals return empty body on success (device-id auth mode)
        if text.is_empty() { return Ok("authenticated".into()); }
        Err("Invalid credentials".into())
    }

    async fn authenticate_device_id(&mut self) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        self.handshake().await?;
        let (_sn, d1, d2, _ua, _tz) = self.get_stealth_params();
        let hw_version = Self::calculate_hw_version(&self.mac);
        let url = format!("{}?type=stb&action=get_profile&JsHttpRequest=1-xml&hd=1&auth_second_step=1", self.api_url());
        let params = [
            ("sn", self.serial_number.as_str()),
            ("stb_type", self.model.as_str()),
            ("device_id", &d1),
            ("device_id2", &d2),
            ("mac", self.mac.as_str()),
            ("hw_version_2", &hw_version),
            ("api_signature", Self::api_signature()),
        ];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
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
        let url = format!("{}?type=itv&action=get_all_channels&JsHttpRequest=1-xml", self.api_url());
        let params = [
            ("mac", self.mac.as_str()),
            ("sn", self.serial_number.as_str()),
        ];
        let mut req = self.client.post(&url).headers(self.headers()).form(&params);
        // ETag conditional request: avoid re-downloading unchanged channel lists.
        if let Some(etag) = self.etags.read().await.get("channels") {
            req = req.header("If-None-Match", etag);
        }
        let resp = req.send().await?;
        if let Some(etag) = resp.headers().get(reqwest::header::ETAG).and_then(|v| v.to_str().ok()) {
            self.etags.write().await.insert("channels".into(), etag.to_string());
        }
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
        })
        // Filter out separator channels (titles starting with #, like "##### ITALY #####")
        .filter(|ch| !ch.title.starts_with('#'))
        .collect();
        Ok(channels)
    }

    async fn get_genres(&self) -> Result<std::collections::HashMap<String, String>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?action=get_genres&type=itv&JsHttpRequest=1-xml", self.api_url());
        let params = [("mac", self.mac.as_str()), ("sn", self.serial_number.as_str())];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct GenreItem { id: String, title: String }
        #[derive(Deserialize)]
        struct GenreResp { js: Vec<GenreItem> }
        let parsed: GenreResp = serde_json::from_str(&text)?;
        Ok(parsed.js.into_iter().map(|g| {
            let title = crate::filter::FilterStore::strip_auto_prefix(&g.title);
            (g.id, title)
        }).collect())
    }

    pub async fn get_vod_categories(&self) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        self.get_categories("vod").await
    }

    pub async fn get_series_categories(&self) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        self.get_categories("series").await
    }

    async fn get_categories(&self, media_type: &str) -> Result<Vec<serde_json::Value>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("{}?action=get_categories&type={}&JsHttpRequest=1-xml", self.api_url(), media_type);
        let params = [("mac", self.mac.as_str()), ("sn", self.serial_number.as_str())];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct CatResp { js: Vec<serde_json::Value> }
        let parsed: CatResp = serde_json::from_str(&text)?;
        Ok(parsed.js.into_iter().map(|mut v| {
            if let Some(obj) = v.as_object_mut() {
                if let Some(title) = obj.get("title").and_then(|t| t.as_str()) {
                    let stripped = crate::filter::FilterStore::strip_auto_prefix(title);
                    obj.insert("title".to_string(), serde_json::Value::String(stripped));
                }
                // Drop separator categories (titles starting with #)
                if let Some(title) = obj.get("title").and_then(|t| t.as_str()) {
                    if title.starts_with('#') {
                        return serde_json::Value::Null;
                    }
                }
            }
            v
        }).filter(|v| !v.is_null()).collect())
    }

    pub async fn create_link(&self, cmd: &str) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        // Extract stream ID from the cmd URL to send as a separate form field.
        // The portal's create_link parser splits the cmd at embedded & characters,
        // so stream=XXXXX must be a top-level POST parameter, not URL-encoded inside cmd.
        let stream_id = crate::proxy::extract_stream_id(cmd);
        let url = format!("{}?type=itv&action=create_link&JsHttpRequest=1-xml", self.api_url());
        // Build the raw form body manually -- reqwest's .form() URL-encodes & in cmd values
        // which prevents the portal from extracting stream/mac/sn from inside the cmd URL.
        // Send cmd RAW -- the portal's PHP parser splits embedded &params
        // as top-level form fields. URL-encoding the cmd would hide stream=XXXXX
        // from the parser, making it return stream= (empty).
        let body_str = if stream_id.is_empty() {
            format!(
                "cmd={}&mac={}&sn={}&stb_type={}",
                cmd, urlencoding(&self.mac), urlencoding(&self.serial_number), urlencoding(&self.model)
            )
        } else {
            format!(
                "cmd={}&mac={}&sn={}&stb_type={}&stream={}",
                cmd, urlencoding(&self.mac), urlencoding(&self.serial_number), urlencoding(&self.model), &stream_id
            )
        };
        let resp = self.client.post(&url)
            .headers(self.headers())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(body_str)
            .send()
            .await?;
        let text = resp.text().await?;
        #[derive(Deserialize)]
        struct LinkJs { cmd: String }
        #[derive(Deserialize)]
        struct LinkResp { js: LinkJs }
        let parsed: LinkResp = serde_json::from_str(&text)?;
        let cmd_str = parsed.js.cmd.trim().to_string();
        
        // Extract the actual link from the command (Stalker often returns "ffmpeg http://...")
        let raw_link = cmd_str.split_whitespace().last().unwrap_or("").to_string();
        
        // Infinity Method 1: AES Link Decryption
        // If the link looks like a long hex string (common for encrypted links), attempt decryption.
        if raw_link.len() > 32 && raw_link.chars().all(|c| c.is_ascii_hexdigit()) {
            tracing::info!("[STALKER] Attempting AES decryption for link: {}...", &raw_link[..16]);
            let decrypted = self.decrypt_link(&raw_link);
            if !decrypted.is_empty() && decrypted.contains("://") {
                tracing::info!("[STALKER] Successfully decrypted link.");
                return Ok(decrypted);
            }
        }
        
        Ok(raw_link)
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
        let url = format!("{}?action=get_events&event_active_id=0&init=0&type=watchdog&cur_play_type=1&JsHttpRequest=1-xml", self.api_url());
        let params = [("mac", self.mac.as_str()), ("sn", self.serial_number.as_str())];
        let resp = self.client.post(&url)
            .headers(self.headers())
            .form(&params)
            .send()
            .await?;
        let _ = resp.text().await?;
        Ok(())
    }

    pub async fn fetch_stream(&self, cmd: &str) -> Result<(Vec<u8>, reqwest::header::HeaderMap), Box<dyn std::error::Error + Send + Sync>> {
        let ts_url = self.create_link(cmd).await?;
        let m3u8_url = ts_url
            .replacen("http://", "https://", 1)
            .replace(":80/", "/")
            .replace("extension=ts", "extension=m3u8");

        // Don't follow redirect — capture the streamer location for segment rewriting.
        // Use stealth-configured client to match the proxy's TLS fingerprint that
        // successfully reaches the portal without Cloudflare 458 blocks.
        let no_redirect_client = Self::configure_stealth_client(reqwest::Client::builder())
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(10))
            .build()?;

        let mut portal_resp = no_redirect_client.get(&m3u8_url)
            .headers(self.headers())
            .send().await?;

        let status = portal_resp.status().as_u16();

        // Cloudflare geo-block (458): retry with streamer IP resolve.
        // Streamer IPs are directly reachable, no Cloudflare. Use a plain
        // HTTP client without the stealth TLS baggage meant for HTTPS.
        if status == 458 || status == 444 {
            let streamer_ips = ["185.245.0.132", "185.245.0.131", "185.245.0.133"];
            for &ip in &streamer_ips {
                let ip_addr: std::net::IpAddr = match ip.parse() { Ok(a) => a, Err(_) => continue };
                let pinned = match reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .timeout(std::time::Duration::from_secs(15))
                    .resolve("tres.4vps.info", std::net::SocketAddr::new(ip_addr, 80))
                    .build() {
                    Ok(c) => c,
                    Err(_) => continue,
                };
                match pinned.get(&m3u8_url)
                    .header("User-Agent", "MAG254")
                    .send().await {
                    Ok(resp) => {
                        let s = resp.status().as_u16();
                        tracing::info!("[HLS] streamer {} returned HTTP {} (fallback for 458)", ip, s);
                        if s == 302 || (s == 200) {
                            portal_resp = resp;
                            break;
                        }
                    }
                    Err(_) => continue,
                }
            }
        }

        let playlist_body: Vec<u8>;
        if portal_resp.status().as_u16() == 302 {
            // Follow HTTPS→HTTP redirect manually
            if let Some(loc) = portal_resp.headers().get(reqwest::header::LOCATION) {
                let streamer_url = loc.to_str().unwrap_or("").to_string();
                // Extract base: "http://185.245.0.132:80" (strip /live/play/TOKEN path)
                let streamer_base = match url::Url::parse(&streamer_url) {
                    Ok(u) => format!("{}://{}", u.scheme(), u.host_str().unwrap_or("")),
                    Err(_) => streamer_url.clone(),
                };

                let streamer_client = reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .timeout(std::time::Duration::from_secs(10))
                    .build()?;
                let seg_resp = streamer_client.get(&streamer_url)
                    .header("User-Agent", "MAG254")
                    .send().await?;
                if seg_resp.status().as_u16() >= 400 {
                    return Err(format!("Streamer HTTP {} for playlist", seg_resp.status().as_u16()).into());
                }
                playlist_body = seg_resp.bytes().await?.to_vec();

                // Rewrite relative segment paths to absolute streamer URLs (no token path)
                let rewritten = Self::rewrite_hls_segments(&playlist_body, &streamer_base);
                let mut headers = reqwest::header::HeaderMap::new();
                headers.insert(reqwest::header::CONTENT_TYPE,
                    reqwest::header::HeaderValue::from_static("application/vnd.apple.mpegurl"));
                return Ok((rewritten.into_bytes(), headers));
            }
        }
        let fallback_status = portal_resp.status().as_u16();
        playlist_body = portal_resp.bytes().await?.to_vec();
        // Reject empty playlists — Cloudflare 458, streamer 200-empty, etc.
        // Returning an error lets the HLS layer try quality fallback.
        if playlist_body.is_empty() || !playlist_body.starts_with(b"#EXTM3U") {
            return Err(format!("Portal returned {} with empty/non-playlist body ({} bytes)",
                fallback_status, playlist_body.len()).into());
        }
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(reqwest::header::CONTENT_TYPE,
            reqwest::header::HeaderValue::from_static("application/vnd.apple.mpegurl"));
        Ok((playlist_body, headers))
    }

    /// Rewrite relative segment paths in an HLS playlist to absolute streamer URLs.
    fn rewrite_hls_segments(playlist: &[u8], base_url: &str) -> String {
        let base = base_url.trim_end_matches('/');
        let mut out = String::with_capacity(playlist.len() + 256);
        if let Ok(s) = std::str::from_utf8(playlist) {
            for line in s.lines() {
                if line.is_empty() {
                    out.push('\n');
                } else if line.starts_with('#') {
                    out.push_str(line);
                    out.push('\n');
                } else {
                    let segment = line.trim();
                    if segment.starts_with('/') {
                        out.push_str(&format!("{}{}", base, segment));
                    } else {
                        out.push_str(&format!("{}/{}", base, segment));
                    }
                    out.push('\n');
                }
            }
        }
        out
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

    pub fn logo_url(&self, logo_path: &str) -> String {
        if logo_path.is_empty() { return String::new(); }
        if logo_path.starts_with("http") { return logo_path.to_string(); }
        
        let base = self.base_url.trim_end_matches(|c| c == '/');
        // Portals usually store logos in /misc/logos/320/ or /stalker_portal/misc/logos/320/
        // We attempt to find the portal root directory.
        let dir = match base.rfind('/') {
            Some(pos) => &base[..=pos],
            None => return format!("{}/misc/logos/320/{}", base, logo_path),
        };
        format!("{}misc/logos/320/{}", dir, logo_path)
    }
}

pub(crate) fn urlencoding(s: &str) -> String {
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
        let url = format!("{}?type=itv&action=get_epg_info&period=5&cmd={}&JsHttpRequest=1-xml", self.api_url(), encoded);
        let params = [("mac", self.mac.as_str()), ("sn", self.serial_number.as_str())];
        let resp = self.client.post(&url).headers(self.headers()).form(&params).send().await?;
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
        let url = format!("{}?type=itv&action=get_epg_info&period=5&JsHttpRequest=1-xml", self.api_url());
        let params = [("mac", self.mac.as_str()), ("sn", self.serial_number.as_str())];
        let resp = self.client.post(&url).headers(self.headers()).form(&params).send().await?;
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
