use axum::{
    body::Body,
    extract::{Path, State},
    http::{Request, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;

use crate::dns;
use crate::filter::FilterStore;
use crate::stalker;

#[derive(Clone)]
pub struct HlsState {
    pub channels: Arc<RwLock<Vec<ChannelState>>>,
    pub channel_map: Arc<RwLock<HashMap<String, usize>>>,
    pub filter: Arc<RwLock<FilterStore>>,
    pub portal_client: Arc<RwLock<stalker::PortalClient>>,
    pub profile_id: i32,
    pub token: Arc<RwLock<String>>,
    pub serial_number: String,
    pub mac: String,
    pub timezone: String,
    pub model: String,
}

#[derive(Clone)]
pub struct ChannelState {
    pub info: stalker::Channel,
}

pub fn build_router(
    channels: Vec<stalker::Channel>,
    filter: Arc<RwLock<FilterStore>>,
    portal_client: Arc<RwLock<stalker::PortalClient>>,
    profile_id: i32,
    token: String,
    serial_number: String,
    mac: String,
    timezone: String,
    model: String,
) -> Router {
    let mut channel_map = HashMap::new();
    let channel_states: Vec<ChannelState> = channels.into_iter().enumerate().map(|(i, ch)| {
        channel_map.insert(ch.title.clone(), i);
        ChannelState { info: ch }
    }).collect();

    let state = HlsState {
        channels: Arc::new(RwLock::new(channel_states)),
        channel_map: Arc::new(RwLock::new(channel_map)),
        filter,
        portal_client,
        profile_id,
        token: Arc::new(RwLock::new(token)),
        serial_number,
        mac,
        timezone,
        model,
    };

    Router::new()
        .route("/", get(playlist_handler))
        .route("/epg", get(epg_handler))
        .route("/logo/*path", get(logo_handler))
        .route("/*path", get(channel_handler))
        .with_state(state)
}

async fn logo_handler(
    State(st): State<HlsState>,
    Path(path): Path<String>,
) -> impl IntoResponse {
    let logo_path = url_decode(&path);
    let target_url = {
        let client = st.portal_client.read().await;
        client.logo_url(&logo_path)
    };
    if target_url.is_empty() { return StatusCode::NOT_FOUND.into_response(); }

    tracing::info!("[HLS] logo request: {}", &target_url);

    let parsed = match url::Url::parse(&target_url) {
        Ok(u) => u,
        Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
    };
    let host = parsed.host_str().unwrap_or("").to_string();
    let port = parsed.port_or_known_default().unwrap_or(443);
    let eur_ips = dns::resolve_european(&host).await;

    let mut builder = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::none());
    
    builder = stalker::PortalClient::configure_stealth_client(builder);
    
    if !eur_ips.is_empty() {
        builder = builder.resolve(&host, SocketAddr::new(eur_ips[0], port));
    }
    let client = match builder.build() {
        Ok(c) => c,
        Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
    };

    let mut req = client.get(&target_url);
    let token = st.token.read().await.clone();
    req = crate::mag::apply_mag_headers(
        req, &token, &st.serial_number, &st.mac, &st.timezone, &st.model, &host
    );

    match req.send().await {
        Ok(resp) => {
            let status = resp.status();
            let headers = resp.headers().clone();
            let bytes = resp.bytes().await.unwrap_or_default();
            let mut response = Response::builder().status(status);
            for (key, val) in headers.iter() {
                let ks = key.as_str().to_lowercase();
                match ks.as_str() {
                    "host" | "connection" | "transfer-encoding" | "keep-alive"
                    | "te" | "trailer" | "upgrade" | "content-length"
                    | "content-encoding" => continue,
                    _ => { response = response.header(key, val); }
                }
            }
            response
                .header("Access-Control-Allow-Origin", "*")
                .header("Cache-Control", "public, max-age=86400")
                .body(Body::from(bytes.to_vec()))
                .unwrap()
        }
        Err(_) => StatusCode::SERVICE_UNAVAILABLE.into_response(),
    }
}

async fn playlist_handler(
    State(st): State<HlsState>,
    req: Request<Body>,
) -> impl IntoResponse {
    tracing::info!("[HLS] playlist requested");
    let scheme = scheme_from_request(&req);
    let host = host_from_request(&req);
    let filter = st.filter.read().await;
    let epg_url = format!("{}://{}/epg", scheme, host);
    let mut output = format!("#EXTM3U x-tvg-url=\"{}\"\n", epg_url);
    let channels = st.channels.read().await;
    for ch in channels.iter() {
        if !filter.is_channel_allowed(st.profile_id, &ch.info.cmd, &ch.info.genre_id) { continue; }
        let title = filter.apply_rename(st.profile_id, &ch.info.title);
        let logo = format!("/logo/{}", url_encode(&title));
        let link = format!("{}://{}/{}", scheme, host, url_encode(&title));
        let genre = filter.apply_genre_rename(st.profile_id, &ch.info.genre_id, &ch.info.genre);
        let tvg_id = &ch.info.cmd;
        output.push_str(&format!(
            "#EXTINF:-1 tvg-id=\"{}\" tvg-name=\"{}\" tvg-logo=\"{}\" group-title=\"{}\", {}\n{}\n",
            tvg_id, title, logo, genre, title, link
        ));
    }
    drop(channels);
    drop(filter);
    Response::builder()
        .header("Content-Type", "audio/x-mpegurl; charset=utf-8")
        .body(Body::from(output))
        .unwrap()
}

async fn channel_handler(
    State(st): State<HlsState>,
    Path(path): Path<String>,
    req: Request<Body>,
) -> impl IntoResponse {
    tracing::info!("[HLS] channel request: {}", &path);
    let parts: Vec<&str> = path.splitn(2, '/').collect();
    let title = url_decode(parts[0]);
    let suffix = parts.get(1).copied().unwrap_or("");

    // HLS cadence: small organic latency variance mimics real CDN jitter (5-25ms)
    {
        use rand::Rng;
        let jitter_ms = rand::thread_rng().gen_range(5..25);
        tokio::time::sleep(std::time::Duration::from_millis(jitter_ms)).await;
    }

    // Lookup channel index by title
    let idx = {
        let map = st.channel_map.read().await;
        map.get(&title).copied()
    };
    let idx = match idx {
        Some(i) => i,
        None => {
            let channels = st.channels.read().await;
            let filter = st.filter.read().await;
            match channels.iter().position(|c| {
                filter.apply_rename(st.profile_id, &c.info.title) == title
            }) {
                Some(pos) => pos,
                None => return StatusCode::BAD_REQUEST.into_response(),
            }
        }
    };

    // Get stream info and check allowed
    let (stream_url, cmd, allowed) = {
        let channels = st.channels.read().await;
        let ch = &channels[idx];
        let filter = st.filter.read().await;
        let allowed = filter.is_channel_allowed(st.profile_id, &ch.info.cmd, &ch.info.genre_id);
        (ch.info.stream_url().to_string(), ch.info.cmd.clone(), allowed)
    };
    if !allowed {
        return StatusCode::FORBIDDEN.into_response();
    }

    let scheme = scheme_from_request(&req);
    let host = host_from_request(&req);

    // Direct CDN URLs (not through portal's /play/live.php) — proxy directly.
    // Portal stream URLs (tres.4vps.info/play/live.php) — use fetch_stream
    // which does create_link + get on same connection.
    if suffix.is_empty() {
        let is_direct_cdn = !stream_url.contains("/play/live.php");
        if is_direct_cdn {
            // Direct CDN: proxy the URL directly (not behind Cloudflare geo-block)
            let stream_client = { st.portal_client.read().await.http_client().clone() };
            let (_ip, tz) = crate::dns::get_sticky_european_identity(&host);
            let target_url = stream_url.replacen("http://", "https://", 1);
            match stream_client.get(&target_url)
                .header("User-Agent", format!("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) {} stbapp ver: 4 rev: 250 Mobile Safari/533.3", st.model))
                .header("Cookie", format!("PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};", st.serial_number, st.mac, tz))
                .header("Accept", "*/*")
                .send().await {
                Ok(resp) => {
                    let status = resp.status();
                    let headers = resp.headers().clone();
                    let body = resp.bytes().await.unwrap_or_default();
                    let body_str = String::from_utf8_lossy(&body);
                    let rewritten = if body_str.starts_with("#EXTM3U") { rewrite_m3u8(&body_str, &scheme, &host, &title) } else { body_str.into_owned() };
                    let mut response = Response::builder().status(status);
                    for (k, v) in headers.iter() {
                        let ks = k.as_str().to_lowercase();
                        if !["host","connection","transfer-encoding","keep-alive","te","trailer","upgrade","content-encoding","content-length"].contains(&ks.as_str()) {
                            response = response.header(k, v);
                        }
                    }
                    return response.header("Access-Control-Allow-Origin", "*")
                        .body(Body::from(rewritten)).unwrap();
                }
                Err(e) => {
                    tracing::warn!("[HLS] direct CDN fetch failed for {title}: {e}");
                    return StatusCode::SERVICE_UNAVAILABLE.into_response();
                }
            }
        }
        // Portal stream: use fetch_stream (create_link + GET on same client)
        let pc = st.portal_client.read().await;
        match pc.fetch_stream(&cmd).await {
            Ok((body_bytes, upstream_headers)) => {
                let body_str = String::from_utf8_lossy(&body_bytes);
                let rewritten = rewrite_m3u8(&body_str, &scheme, &host, &title);
                let mut response = Response::builder().status(200);
                for (k, v) in upstream_headers.iter() {
                    let ks = k.as_str().to_lowercase();
                    if !["host","connection","transfer-encoding","keep-alive","te","trailer","upgrade","content-encoding","content-type"].contains(&ks.as_str()) {
                        response = response.header(k, v);
                    }
                }
                return response
                    .header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .body(Body::from(rewritten)).unwrap();
            }
            Err(e) => {
                tracing::error!("[HLS] fetch_stream failed for {title}: {e}");
                return StatusCode::SERVICE_UNAVAILABLE.into_response();
            }
        }
    }
    // TS segments: use cached stream_url with upgraded protocol
    let ts_url = upgrade_to_https(&if suffix.is_empty() { stream_url } else { format!("{}{}", get_hls_root_for_url(&stream_url), suffix) });
    let stream_client = { st.portal_client.read().await.http_client().clone() };
    let (_ip, tz) = crate::dns::get_sticky_european_identity(&host);
    match stream_client.get(&ts_url)
        .header("User-Agent", format!("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) {} stbapp ver: 4 rev: 250 Mobile Safari/533.3", st.model))
        .header("Cookie", format!("PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};", st.serial_number, st.mac, tz))
        .header("Accept", "*/*")
        .send().await {
        Ok(resp) => {
            let status = resp.status();
            let headers = resp.headers().clone();
            let body = resp.bytes().await.unwrap_or_default();
            let mut response = Response::builder().status(status);
            for (k, v) in headers.iter() {
                let ks = k.as_str().to_lowercase();
                if !["host","connection","transfer-encoding","keep-alive","te","trailer","upgrade","content-encoding"].contains(&ks.as_str()) {
                    response = response.header(k, v);
                }
            }
            response.header("Access-Control-Allow-Origin", "*")
                .header("Content-Length", body.len())
                .body(Body::from(body.to_vec())).unwrap()
        }
        Err(e) => {
            tracing::error!("TS segment failed for {title}: {e}");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

async fn epg_handler(
    State(st): State<HlsState>,
) -> impl IntoResponse {
    tracing::info!("[HLS] EPG requested");
    let portal_url = {
        let client = st.portal_client.read().await;
        client.base_url.clone()
    };
    let epg_url = format!("{}?type=itv&action=get_epg_info&period=5&JsHttpRequest=1-xml", portal_url);

    let parsed = match url::Url::parse(&epg_url) {
        Ok(u) => u,
        Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
    };
    let host = parsed.host_str().unwrap_or("").to_string();
    let port = parsed.port_or_known_default().unwrap_or(443);
    let eur_ips = dns::resolve_european(&host).await;

    let mut builder = reqwest::Client::builder()
        .timeout(Duration::from_secs(60))

        .redirect(reqwest::redirect::Policy::none());
    
    builder = stalker::PortalClient::configure_stealth_client(builder);
    
    if !eur_ips.is_empty() {
        builder = builder.resolve(&host, SocketAddr::new(eur_ips[0], port));
    }
    let client = match builder.build() {
        Ok(c) => c,
        Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
    };

    let mut req = client.get(&epg_url);
    let epg_token = st.token.read().await.clone();
    req = crate::mag::apply_mag_headers(
        req, &epg_token, &st.serial_number, &st.mac, &st.timezone, &st.model, &host
    );

    // Use the profile's configured timezone for EPG timeshift metadata.
    // This is set by the user's browser/device timezone when creating the profile.
    let tz = st.timezone.clone();
    tracing::info!("[HLS] EPG timezone: {}", tz);

    match req.send().await {
        Ok(resp) => {
            let status = resp.status();
            let headers = resp.headers().clone();
            let bytes = resp.bytes().await.unwrap_or_default();
            let body = if status.is_success() {
                // Inject timezone into EPG JSON for IPTV player timeshift support
                match serde_json::from_slice::<serde_json::Value>(&bytes) {
                    Ok(mut json) => {
                        json["timezone"] = serde_json::Value::String(tz);
                        serde_json::to_vec(&json).unwrap_or_else(|_| bytes.to_vec())
                    }
                    Err(_) => bytes.to_vec(),
                }
            } else {
                bytes.to_vec()
            };
            let mut response = Response::builder().status(status);
            for (key, val) in headers.iter() {
                let ks = key.as_str().to_lowercase();
                match ks.as_str() {
                    "host" | "connection" | "transfer-encoding" | "keep-alive"
                    | "te" | "trailer" | "upgrade" | "content-length"
                    | "content-encoding" => continue,
                    _ => { response = response.header(key, val); }
                }
            }
            response
                .header("Access-Control-Allow-Origin", "*")
                .body(Body::from(body))
                .unwrap()
        }
        Err(e) => {
            tracing::error!("[HLS] EPG fetch failed: {e}");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

/// Get a fresh stream URL for a single channel via create_link.
/// This is much faster than re-fetching all channels when a play_token expires.
async fn fresh_stream_url(
    st: &HlsState, cmd: &str,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    let client = st.portal_client.read().await;
    client.create_link(cmd).await
}

/// Re-fetch channels from portal to get fresh play_tokens, update cache, and retry.
async fn refresh_and_retry(
    st: &HlsState, title: &str, suffix: &str, scheme: &str, host: &str,
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    // Release the write lock immediately after the network call so concurrent
    // STB requests are not blocked for the duration of the HTTP round-trip.
    let fresh = {
        let client = st.portal_client.write().await;
        client.get_channels().await?
    };

    // Rebuild channel states
    let mut new_map = HashMap::new();
    let new_states: Vec<ChannelState> = fresh.into_iter().enumerate().map(|(i, ch)| {
        new_map.insert(ch.title.clone(), i);
        ChannelState { info: ch }
    }).collect();

    // Find matching channel in fresh data
    let ni = new_map.get(title).copied().ok_or_else(|| {
        format!("Channel '{}' not found after 458 refresh", title)
    })?;

    let new_url = new_states[ni].info.stream_url().to_string();
    let new_cmd = new_states[ni].info.cmd.clone();

    // Update cached channels and map atomically: acquire both write locks before
    // writing either, so no concurrent request sees a new channel vec with the old map.
    {
        let mut ch_guard = st.channels.write().await;
        let mut map_guard = st.channel_map.write().await;
        *ch_guard = new_states;
        *map_guard = new_map;
    }

    // Read the freshest token from portal_client (may have changed during get_channels)
    // and update the shared token so all subsequent requests use it immediately.
    let fresh_token = st.portal_client.read().await.token.clone();
    *st.token.write().await = fresh_token.clone();

    let new_target = if suffix.is_empty() {
        new_url
    } else {
        format!("{}{}", get_hls_root_for_url(&new_url), suffix)
    };

    let stream_client = { let pc = st.portal_client.read().await; pc.http_client().clone() };
    let mut new_target = new_target;
    new_target = upgrade_to_https(&new_target);
    tracing::info!("[HLS] retrying {} with fresh token", title);
    proxy_request(
        &new_target, scheme, host, title, &new_cmd, !suffix.is_empty(),
        &fresh_token, &st.serial_number, &st.mac, &st.timezone, &st.model,
        &stream_client,
    ).await
}

/// Upgrade an HTTP URL to HTTPS and strip port 80, so the request reuses
/// the PortalClient's authenticated HTTP/2 connection to Cloudflare.
fn upgrade_to_https(url: &str) -> String {
    let s = url.replacen("http://", "https://", 1);
    s.replace(":80/", "/").replacen(":80?", "?", 1)
}

fn get_hls_root_for_url(url: &str) -> String {
    if url.contains(".m3u8") {
        match url.rfind('/') {
            Some(pos) => url[..=pos].to_string(),
            None => format!("{url}/"),
        }
    } else {
        url.to_string()
    }
}

async fn proxy_request(
    url: &str, scheme: &str, host: &str, title: &str, _cmd: &str, is_suffix: bool,
    token: &str, serial_number: &str, mac: &str, timezone: &str, model: &str,
    shared_client: &reqwest::Client,
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    // Follow redirect chain manually, preserving all headers and DNS pinning on every hop.
    // DNS is re-resolved on every hop to ensure pinning persists across cross-domain redirects.
    let mut current_url = url.to_string();
    let max_redirects = 200;
    for hop in 0..=max_redirects {
        let parsed_url = url::Url::parse(&current_url)?;
        let current_host = parsed_url.host_str().unwrap_or("").to_string();
        let current_port = parsed_url.port_or_known_default().unwrap_or(443);

        // Always use the shared client (PortalClient's HTTP/2 connection) —
        // Cloudflare binds stream play_tokens to the authenticated API session connection.
        // Creating a new client with DNS pinning breaks this binding and causes 458/444.
        let client_ref: &reqwest::Client = shared_client;

        // Warm up the HTTP/2 connection: make a quick API call to portal.php first.
        // Cloudflare binds stream play_tokens to the authenticated HTTP/2 session.
        // Without a recent API call on the same connection, the stream gets 458.
        if hop == 0 {
            let warmup_url = format!("https://{}/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml", current_host);
            let mac_s = mac.to_string();
            let sn_s = serial_number.to_string();
            let model_s = model.to_string();
            let _ = client_ref.post(&warmup_url)
                .form(&[("mac", &mac_s), ("sn", &sn_s), ("stb_type", &model_s)])
                .send().await;
        }

        let (_eur_ip, eur_tz) = crate::dns::get_sticky_european_identity(&current_host);
        let mut req = client_ref.get(&current_url);
        req = req
            .header("User-Agent", format!("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) {} stbapp ver: 4 rev: 250 Mobile Safari/533.3", model))
            .header("Cookie", format!("PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};", serial_number, mac, eur_tz))
            .header("Accept", "*/*");
        tracing::info!("[HLS] fetch (hop {}/{}): {}", hop, max_redirects, current_url);
        let resp = req.send().await?;
        let status = resp.status();

        // Follow redirect
        if status.is_redirection() && hop < max_redirects {
            if let Some(location) = resp.headers().get(reqwest::header::LOCATION) {
                let dest = location.to_str()?.to_string();
                current_url = if dest.starts_with("http://") || dest.starts_with("https://") {
                    dest
                } else {
                    url::Url::parse(&current_url)?
                        .join(&dest)
                        .map(|u| u.to_string())
                        .unwrap_or(dest)
                };
                tracing::info!("[HLS] redirect {}: {} -> {}", hop + 1, status.as_u16(), current_url);
                continue;
            }
        }

        let upstream_headers = resp.headers().clone();
        let content_type = upstream_headers.get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        let ct_lower = content_type.to_lowercase();
        let is_m3u8 = ct_lower.contains("mpegurl") || ct_lower.contains("x-mpegurl");

        let bytes = resp.bytes().await?;

        if is_m3u8 {
            let body_str = String::from_utf8_lossy(&bytes);
            let rewritten = rewrite_m3u8(&body_str, scheme, host, title);
            let mut response = Response::builder()
                .status(status)
                .header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                .body(Body::from(rewritten))
                .unwrap();
            response.headers_mut().insert("Access-Control-Allow-Origin", "*".parse().unwrap());
            return Ok(response);
        } else {
            let mut response = Response::builder()
                .status(status)
                .header("Content-Type", &content_type)
                .body(Body::from(bytes.to_vec()))
                .unwrap();
            // Forward upstream headers except hop-by-hop
            for (key, val) in upstream_headers.iter() {
                let ks = key.as_str().to_lowercase();
                match ks.as_str() {
                    "host" | "connection" | "transfer-encoding" | "keep-alive"
                    | "te" | "trailer" | "upgrade" | "proxy-authorization"
                    | "proxy-authenticate" | "content-type" | "content-length"
                    | "content-encoding" | "access-control-allow-origin" => continue,
                    _ => { response.headers_mut().insert(key, val.clone()); }
                }
            }
            response.headers_mut().insert("Access-Control-Allow-Origin", "*".parse().unwrap());
            if !is_suffix {
                response.headers_mut().insert("Content-Length", bytes.len().into());
            }
            return Ok(response);
        }
    }

    Err("Too many redirects".into())
}

fn rewrite_m3u8(content: &str, scheme: &str, host: &str, title: &str) -> String {
    let prefix = format!("{}://{}/{}", scheme, host, url_encode(title));
    let mut out = String::new();
    for line in content.lines() {
        if line.is_empty() {
            out.push('\n');
            continue;
        }
        if line.starts_with('#') {
            if let Some(start) = line.find("URI=\"") {
                if let Some(end) = line[start + 5..].find('"') {
                    let uri = &line[start + 5..start + 5 + end];
                    let uri = uri.trim_start_matches('/');
                    out.push_str(&format!("{}URI=\"{}/{}\"{}", &line[..start], prefix, uri, &line[start + 5 + end + 1..]));
                    out.push('\n');
                    continue;
                }
            }
            out.push_str(line);
            out.push('\n');
        } else {
            let segment = line.trim().trim_start_matches('/');
            out.push_str(&format!("{}/{}", prefix, segment));
            out.push('\n');
        }
    }
    out
}

fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn url_decode(s: &str) -> String {
    let mut bytes: Vec<u8> = Vec::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            let hex: String = chars.by_ref().take(2).collect();
            if let Ok(byte) = u8::from_str_radix(&hex, 16) {
                bytes.push(byte);
            } else {
                // Invalid escape — pass through literally
                bytes.push(b'%');
                bytes.extend_from_slice(hex.as_bytes());
            }
        } else if c == '+' {
            bytes.push(b' ');
        } else {
            let mut buf = [0u8; 4];
            bytes.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn scheme_from_request(req: &Request<Body>) -> String {
    if let Some(proto) = req.headers().get("X-Forwarded-Proto") {
        if let Ok(v) = proto.to_str() {
            return v.split(',').next().unwrap_or("http").trim().to_string();
        }
    }
    "http".to_string()
}

fn host_from_request(req: &Request<Body>) -> String {
    if let Some(h) = req.headers().get("X-Forwarded-Host") {
        if let Ok(v) = h.to_str() {
            return v.split(',').next().unwrap_or("").trim().to_string();
        }
    }
    if let Some(h) = req.headers().get("Host") {
        if let Ok(v) = h.to_str() {
            return v.to_string();
        }
    }
    "localhost".to_string()
}

// ─── Auto-reconnect on 401 ────────────────────────────────────────────────────
/// Called when proxy_request receives a 401. Re-authenticates via portal_client
/// and updates st.token, then retries the request with the new token.
pub async fn handle_401_and_retry(
    st: &HlsState,
    url: &str, scheme: &str, host: &str,
    title: &str, cmd: &str, is_suffix: bool,
) -> Option<Response> {
    tracing::warn!("[HLS] got 401 for {}, re-authenticating...", title);
    // Re-authenticate
    let new_token = {
        let mut client = st.portal_client.write().await;
        if client.authenticate().await.is_err() { return None; }
        client.token.clone()
    };
    *st.token.write().await = new_token.clone();
    let stream_client = { let pc = st.portal_client.read().await; pc.http_client().clone() };
    let mut url = url.to_string();
    url = upgrade_to_https(&url);
    tracing::info!("[HLS] re-auth succeeded, retrying {}", title);
    proxy_request(&url, scheme, host, title, cmd, is_suffix, &new_token,
        &st.serial_number, &st.mac, &st.timezone, &st.model, &stream_client)
        .await.ok()
}
