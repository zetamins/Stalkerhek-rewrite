use axum::{
    body::Body,
    extract::{Path, State},
    http::{Request, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::filter::FilterStore;
use crate::stalker;

#[derive(Clone)]
pub struct HlsState {
    pub channels: Arc<Vec<ChannelState>>,
    pub channel_map: Arc<HashMap<String, usize>>,
    pub filter: Arc<RwLock<FilterStore>>,
    pub profile_id: i32,
    pub portal_client: Arc<RwLock<stalker::PortalClient>>,
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
    profile_id: i32,
    portal_client: Arc<RwLock<stalker::PortalClient>>,
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
        channels: Arc::new(channel_states),
        channel_map: Arc::new(channel_map),
        filter,
        profile_id,
        portal_client,
        token,
        serial_number,
        mac,
        timezone,
        model,
    };

    Router::new()
        .route("/", get(playlist_handler))
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
    let mut output = String::from("#EXTM3U\n");
    for ch in st.channels.iter() {
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

    let idx = match st.channel_map.get(&title) {
        Some(i) => *i,
        None => {
            // Try matching against renamed title
            let filter = st.filter.read().await;
            let found = st.channels.iter().position(|c| {
                filter.apply_rename(st.profile_id, &c.info.title) == title
            });
            if let Some(pos) = found { pos } else { return StatusCode::BAD_REQUEST.into_response(); }
        }
    };
    let ch = &st.channels[idx];

    let allowed = {
        let filter = st.filter.read().await;
        filter.is_channel_allowed(st.profile_id, &ch.info.cmd, &ch.info.genre_id)
    };
    if !allowed {
        return StatusCode::FORBIDDEN.into_response();
    }

    let scheme = scheme_from_request(&req);
    let host = host_from_request(&req);

    // Get or create stream link via portal client
    let target_url = if suffix.is_empty() {
        let client = st.portal_client.write().await;
        match client.create_link_with_retry(&ch.info.cmd, 3).await {
            Ok(url) => url,
            Err(e) => {
                tracing::error!("Failed to create link for {}: {}", title, e);
                return StatusCode::SERVICE_UNAVAILABLE.into_response();
            }
        }
    } else {
        // HLS segment or sub-request — reuse the base HLS root
        // We need the base link. Check if suffix is a full URL path segment
        format!("{}{}", get_hls_root(&st.portal_client, &ch.info.cmd).await, suffix)
    };

    match proxy_request(&target_url, &scheme, &host, &title, &ch.info.cmd, !suffix.is_empty(), &st.token, &st.serial_number, &st.mac, &st.timezone, &st.model).await {
        Ok(resp) => resp,
        Err(e) => {
            tracing::error!("Proxy failed for {title}: {e}");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

async fn get_hls_root(client: &Arc<RwLock<stalker::PortalClient>>, cmd: &str) -> String {
    let c = client.write().await;
    match c.create_link_with_retry(cmd, 3).await {
        Ok(url) => {
            if url.contains(".m3u8") {
                match url.rfind('/') {
                    Some(pos) => url[..=pos].to_string(),
                    None => format!("{url}/"),
                }
            } else {
                url
            }
        }
        Err(_) => String::new(),
    }
}

async fn proxy_request(
    url: &str, scheme: &str, host: &str, title: &str, _cmd: &str, is_suffix: bool,
    token: &str, serial_number: &str, mac: &str, timezone: &str, model: &str,
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .build()?;

    let req = client.get(url);
    let req = crate::mag::apply_mag_headers(req, token, serial_number, mac, timezone, model);
    tracing::info!("[HLS] fetching upstream: {}", url);
    let resp = req.send().await?;

    let status = resp.status();
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
        Ok(response)
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
        Ok(response)
    }
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
