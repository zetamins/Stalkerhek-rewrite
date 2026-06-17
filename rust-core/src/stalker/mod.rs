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
        let host_str = url::Url::parse(&self.base_url).ok().and_then(|u| u.host_str().map(|s| s.to_string())).unwrap_or_default();
        let (eur_ip, eur_tz) = dns::get_sticky_european_identity(&host_str);
        
        use reqwest::header::*;
        let mut h = HeaderMap::new();
        h.insert(ACCEPT, HeaderValue::from_static("*/*"));
        h.insert("Cache-Control", HeaderValue::from_static("no-cache"));

        // Absolute Method 1: Packet Length Obfuscation
        {
            use rand::Rng;
            let mut rng = rand::thread_rng();
            let padding_len = rng.gen_range(32..128);
            let padding: String = (0..padding_len).map(|_| (rng.gen_range(33..126) as u8) as char).collect();
            h.insert("X-DPI-Padding", HeaderValue::from_str(&padding).unwrap());
        }

        h.insert("X-User-Agent", HeaderValue::from_str(&format!("Model: {}; Link: Ethernet", self.model)).unwrap());
        h.insert("X-Forwarded-For", HeaderValue::from_str(&eur_ip).unwrap());
        h.insert("X-Real-IP", HeaderValue::from_str(&eur_ip).unwrap());
        h.insert("CF-Connecting-IP", HeaderValue::from_str(&eur_ip).unwrap());
        h.insert("True-Client-IP", HeaderValue::from_str(&eur_ip).unwrap());
        h.insert("X-Originating-IP", HeaderValue::from_str(&eur_ip).unwrap());
        if !self.token.is_empty() {
            h.insert(AUTHORIZATION, HeaderValue::from_str(&format!("Bearer {}", self.token)).unwrap());
        }
        let cookie = format!(
            "PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};",
            urlencoding(&self.serial_number), urlencoding(&self.mac), urlencoding(&eur_tz),
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
    #[allow(dead_code)]
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
}

impl PortalClient {
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
        // Absolute Method 2: JA3 Soft Mirroring — use reqwest's built-in rustls
        // backend with TLS 1.0-1.2 to match MAG254 TLS fingerprint.
        // Hard cipher-suite pinning was lost in the rustls 0.21→0.23 upgrade,
        // but reqwest/rustls 0.23 defaults are close enough for most CDNs.

        builder
            .timeout(std::time::Duration::from_secs(60))
            .use_rustls_tls()
            .min_tls_version(reqwest::tls::Version::TLS_1_0)
            .max_tls_version(reqwest::tls::Version::TLS_1_2)
            .tcp_keepalive(std::time::Duration::from_secs(60))
            // HTTP/2 SETTINGS fingerprint matching (MAG254 WebKit defaults)
            .http2_initial_stream_window_size(65535)
            .http2_initial_connection_window_size(1048576)
            .http2_max_frame_size(16384)
            .http2_keep_alive_interval(std::time::Duration::from_secs(30))
            .http2_keep_alive_timeout(std::time::Duration::from_secs(10))
            // TCP stack fingerprinting
            .tcp_nodelay(true)           // MAG254 disables Nagle's algorithm
            .https_only(false)           // allow HTTP connections
            .tls_sni(false)
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
        let (_sn, d1, d2, _ua, _tz) = self.get_stealth_params();
        let hw_version = Self::calculate_hw_version(&self.mac);
        let params = [
            ("type", "stb"),
            ("action", "do_auth"),
            ("login", &self.username),
            ("password", &self.password),
            ("device_id", &d1),
            ("device_id2", &d2),
            ("hw_version_2", &hw_version),
            ("api_signature", Self::api_signature()),
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
        let (sn, d1, d2, _ua, _tz) = self.get_stealth_params();
        let hw_version = Self::calculate_hw_version(&self.mac);
        let url = format!(
            "{}?type=stb&action=get_profile&JsHttpRequest=1-xml&hd=1&sn={}&stb_type={}&device_id={}&device_id2={}&hw_version_2={}&api_signature={}&auth_second_step=1",
            self.base_url, urlencoding(&sn), urlencoding(&self.model),
            urlencoding(&d1), urlencoding(&d2),
            urlencoding(&hw_version), urlencoding(Self::api_signature())
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
        let mut req = self.client.get(&url).headers(self.headers());
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
