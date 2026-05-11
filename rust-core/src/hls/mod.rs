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
    pub token: String,
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
        token,
        serial_number,
        mac,
        timezone,
        model,
    };

    Router::new()
        .route("/", get(playlist_handler))
        .route("/epg", get(epg_handler))
        .route("/*path", get(channel_handler))
        .with_state(state)
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
) -> Response {
    tracing::info!("[HLS] channel request: {}", &path);
    let parts: Vec<&str> = path.splitn(2, '/').collect();
    let title = url_decode(parts[0]);
    let suffix = parts.get(1).copied().unwrap_or("");

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

    // Build target URL
    let target_url = if suffix.is_empty() {
        stream_url
    } else {
        format!("{}{}", get_hls_root_for_url(&stream_url), suffix)
    };

    // Try the request
    let result = proxy_request(
        &target_url, &scheme, &host, &title, &cmd, !suffix.is_empty(),
        &st.token, &st.serial_number, &st.mac, &st.timezone, &st.model,
    ).await;

    // On 458 (Cloudflare ban) for initial playlist, retry with fresh channel data
    if suffix.is_empty() {
        if let Ok(ref r) = result {
            if r.status().as_u16() == 458 {
                tracing::warn!("[HLS] got 458 for {}, refreshing channels...", &title);
                return refresh_and_retry(&st, &title, &suffix, &scheme, &host).await
                    .unwrap_or_else(|e| {
                        tracing::error!("Retry failed for {}: {}", title, e);
                        StatusCode::SERVICE_UNAVAILABLE.into_response()
                    });
            }
        }
    }

    match result {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("Proxy failed for {title}: {e}");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

async fn epg_handler(
    State(st): State<HlsState>,
) -> Response {
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
        .timeout(Duration::from_secs(300))
        .redirect(reqwest::redirect::Policy::none());
    if !eur_ips.is_empty() {
        builder = builder.resolve(&host, SocketAddr::new(eur_ips[0], port));
    }
    let client = match builder.build() {
        Ok(c) => c,
        Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
    };

    let mut req = client.get(&epg_url);
    req = crate::mag::apply_mag_headers(
        req, &st.token, &st.serial_number, &st.mac, &st.timezone, &st.model,
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

/// Re-fetch channels from portal to get fresh play_tokens, update cache, and retry.
async fn refresh_and_retry(
    st: &HlsState, title: &str, suffix: &str, scheme: &str, host: &str,
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    let client = st.portal_client.write().await;
    let fresh = client.get_channels().await?;

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

    // Update cached channels so subsequent requests benefit too
    *st.channels.write().await = new_states;
    *st.channel_map.write().await = new_map;
    drop(client);

    let new_target = if suffix.is_empty() {
        new_url
    } else {
        format!("{}{}", get_hls_root_for_url(&new_url), suffix)
    };

    tracing::info!("[HLS] retrying {} with fresh token", title);
    proxy_request(
        &new_target, scheme, host, title, &new_cmd, !suffix.is_empty(),
        &st.token, &st.serial_number, &st.mac, &st.timezone, &st.model,
    ).await
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
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    // Resolve stream hostname via European DNS to bypass geo-blocking
    let parsed_url = url::Url::parse(url)?;
    let stream_host = parsed_url.host_str().unwrap_or("").to_string();
    let stream_port = parsed_url.port_or_known_default().unwrap_or(443);
    let eur_ips = dns::resolve_european(&stream_host).await;

    let mut client_builder = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(300))
        // Disable auto-redirect — we follow manually to preserve all
        // headers (Authorization, Cookie) across cross-origin hops,
        // since reqwest strips Authorization on cross-origin redirects.
        .redirect(reqwest::redirect::Policy::none());

    // Pin only the first resolved IP (multiple resolve entries can cause hangs)
    if !eur_ips.is_empty() {
        tracing::info!("[HLS] European DNS for {}: {:?}", stream_host, eur_ips);
        client_builder = client_builder.resolve(&stream_host, SocketAddr::new(eur_ips[0], stream_port));
    }

    let client = client_builder.build()?;

    // Follow redirect chain manually, preserving all headers on every hop.
    // Stream URLs often redirect through CDN/storage server chains and
    // dropping Authorization or Cookie on any hop causes a 444/458 block.
    let mut current_url = url.to_string();
    let max_redirects = 200;
    for hop in 0..=max_redirects {
        let req = client.get(&current_url);
        let req = crate::mag::apply_mag_headers(req, token, serial_number, mac, timezone, model);
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
                    out.push_str(&format!("{}URI=\"{}/{}\"{}", &line[..start], prefix, uri, &line[start + 5 + end + 1..]));
                    out.push('\n');
                    continue;
                }
            }
            out.push_str(line);
            out.push('\n');
        } else {
            out.push_str(&format!("{}/{}", prefix, line.trim()));
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
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            let hex: String = chars.by_ref().take(2).collect();
            if let Ok(byte) = u8::from_str_radix(&hex, 16) {
                out.push(byte as char);
            }
        } else {
            out.push(c);
        }
    }
    out
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
