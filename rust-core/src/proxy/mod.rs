use axum::{
    body::Body,
    extract::{Query, State},
    http::{HeaderMap, Method, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::{any},
    Router,
};
use serde_json;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;

use url::Url;

use crate::dns;
use crate::filter::FilterStore;
use crate::stalker;

#[derive(Clone)]
pub struct ProxyState {
    pub portal_base: String,
    pub portal_root: String,
    pub portal_root_path: String,
    pub channels: Arc<RwLock<HashMap<String, stalker::Channel>>>,
    pub filter: Arc<RwLock<FilterStore>>,
    pub profile_id: i32,
    pub proxy_rewrite: bool,
    pub token: Arc<RwLock<String>>,
    pub serial_number: String,
    pub mac: String,
    pub timezone: String,
    pub model: String,
    pub device_id: String,
    pub device_id2: String,
    pub hls_bind: String,
    pub vod_categories: Vec<serde_json::Value>,
    pub series_categories: Vec<serde_json::Value>,
    pub portal_client: Arc<RwLock<stalker::PortalClient>>,
    /// Shared HTTP client for portal API proxying (no auto-redirect, 300s timeout).
    pub portal_http_client: reqwest::Client,
    /// Watchdog event counter: monotonically increments per watchdog ping, mimicking real MAG254 behaviour.
    pub event_active_id: Arc<AtomicU64>,
    /// Current play type reported by the STB: 0 = idle, 1 = ITV, 2 = VOD, 3 = timeshift.
    pub cur_play_type: Arc<AtomicU8>,
}

pub fn build_router(
    portal_base: String,
    channels: Vec<stalker::Channel>,
    filter: Arc<RwLock<FilterStore>>,
    profile_id: i32,
    proxy_rewrite: bool,
    token: String,
    serial_number: String,
    mac: String,
    timezone: String,
    model: String,
    device_id: String,
    device_id2: String,
    hls_bind: String,
    vod_categories: Vec<serde_json::Value>,
    series_categories: Vec<serde_json::Value>,
    portal_client: Arc<RwLock<stalker::PortalClient>>,
) -> Router {
    let mut channel_map = HashMap::new();
    for ch in &channels {
        channel_map.insert(ch.cmd.clone(), ch.clone());
    }

    // Compute portal root (directory containing portal.php) for serving the portal HTML page
    let portal_root = match portal_base.rfind('/') {
        Some(pos) => portal_base[..=pos].to_string(),
        None => portal_base.clone(),
    };
    // Extract the path portion of the portal_root URL (e.g., "/c/")
    let portal_root_path = match portal_root.splitn(2, "://").nth(1) {
        Some(rest) => match rest.splitn(2, '/').nth(1) {
            Some(p) => format!("/{}", p),
            None => "/".to_string(),
        },
        None => "/".to_string(),
    };

    let portal_http_client = {
        let builder = reqwest::Client::builder()
            .timeout(Duration::from_secs(300))
            .pool_max_idle_per_host(50)
            .redirect(reqwest::redirect::Policy::none());

        let builder = stalker::PortalClient::configure_stealth_client(builder);

        builder.build().unwrap_or_default()
    };

    let state = ProxyState {
        portal_base,
        portal_root,
        portal_root_path,
        channels: Arc::new(RwLock::new(channel_map)),
        filter,
        profile_id,
        proxy_rewrite,
        token: Arc::new(RwLock::new(token)),
        serial_number,
        mac,
        timezone,
        model,
        device_id,
        device_id2,
        hls_bind,
        vod_categories,
        series_categories,
        portal_client,
        portal_http_client,
        event_active_id: Arc::new(AtomicU64::new(0)),
        cur_play_type: Arc::new(AtomicU8::new(0)),
    };

    Router::new()
        .route("/", any(proxy_handler))
        .route("/*path", any(proxy_handler))
        .with_state(state)
}

#[derive(serde::Deserialize)]
struct ProxyQuery {
    action: Option<String>,
    r#type: Option<String>,
    cmd: Option<String>,
    sn: Option<String>,
    device_id: Option<String>,
    device_id2: Option<String>,
    signature: Option<String>,
    #[serde(flatten)]
    extra: HashMap<String, String>,
}

async fn proxy_handler(
    State(st): State<ProxyState>,
    method: Method,
    headers: HeaderMap,
    uri: Uri,
    query: Query<ProxyQuery>,
    body: axum::body::Bytes,
) -> Response {
    // Skip favicon -- not needed by STB
    if uri.path().contains("favicon") {
        return StatusCode::NOT_FOUND.into_response();
    }
    tracing::info!("[PROXY] {} -- action={:?} type={:?} cmd={:?}", uri, query.action, query.r#type, query.cmd);
    if let Some(action) = &query.action {
        match action.as_str() {
            "handshake" => {
                let tok = st.token.read().await.clone();
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(format!(
                        r#"{{"js":{{"token":"{}","random":"b8c4ef93de04e675350605eb0086bffe51507b88e6a1662e71fe9372"}},"text":"generated in: 0.01s"}}"#,
                        tok
                    )))
                    .unwrap();
            }
            "get_events" => {
                if query.r#type.as_deref() == Some("watchdog") {
                    let event_id = st.event_active_id.fetch_add(1, Ordering::SeqCst);
                    let play_type = st.cur_play_type.load(Ordering::SeqCst);
                    let body = format!(
                        r#"{{"js":{{"data":{{"msgs":0,"additional_services_on":"1","cur_play_type":"{}","event_active_id":{},"id":{}}},"text":"generated in: 0.01s"}}}}"#,
                        play_type, event_id, event_id
                    );
                    return Response::builder()
                        .header("Content-Type", "application/json")
                        .body(Body::from(body))
                        .unwrap();
                }
                if query.r#type.as_deref() == Some("log") {
                    return Response::builder()
                        .header("Content-Type", "application/json")
                        .body(Body::from(
                            r#"{"js":1,"text":"generated in: 0.001s"}"#,
                        ))
                        .unwrap();
                }
            }
            "do_auth" => {
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(
                        r#"{"js":true,"text":"Authenticated"}"#,
                    ))
                    .unwrap();
            }
            "logout" => {
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(
                        r#"{"js":true,"text":"Logged out"}"#,
                    ))
                    .unwrap();
            }
            // get_ordered_list falls through to portal proxy
            // VOD/series get_ordered_list falls through to portal proxy
            "get_all_channels" if query.r#type.as_deref() == Some("itv") => {
                // Serve from local cache with rename, # filtering, and quality dedup.
                let filter = st.filter.read().await;
                let channels_guard = st.channels.read().await;
                let mut all_data: Vec<serde_json::Value> = channels_guard.values()
                    .filter(|ch| filter.is_channel_allowed(st.profile_id, &ch.cmd, &ch.genre_id))
                    .enumerate()
                    .map(|(i, ch)| {
                        let ch_id = extract_stream_id(&ch.cmd);
                        let renamed = filter.apply_rename(st.profile_id, &ch.title);
                        serde_json::json!({
                            "id": ch_id,
                            "name": renamed,
                            "number": (i + 1).to_string(),
                            "cmd": ch.cmd,
                            "logo": ch.logo,
                            "tv_genre_id": ch.genre_id,
                            "censored": "0",
                            "cost": "0",
                            "count": "0",
                            "status": 1,
                            "hd": 0,
                            "base_ch": "0",
                            "xmltv_id": "",
                            "service_id": "",
                            "use_http_tmp_link": "0",
                            "use_load_balancing": "0",
                            "wowza_tmp_link": "0",
                            "wowza_dvr": "0",
                            "enable_tv_archive": 0,
                            "enable_wowza_load_balancing": "0",
                            "monitoring_status": "1",
                            "enable_monitoring": "0",
                            "allow_pvr": 0,
                            "allow_local_pvr": 0,
                            "allow_local_timeshift": "1",
                            "correct_time": "0",
                            "nimble_dvr": "0",
                            "volume_correction": "0",
                            "mc_cmd": "",
                            "bonus_ch": "0",
                            "cmd_1": "",
                            "cmd_2": "",
                            "cmd_3": "",
                            "modified": "",
                            "nginx_secure_link": "0",
                            "cmds": [{
                                "id": ch_id,
                                "url": ch.cmd,
                                "use_http_tmp_link": "0"
                            }]
                        })
                    })
                    .collect();
                drop(filter);
                // Drop # separators only
                all_data.retain(|item| {
                    !item["name"].as_str().map_or(false, |n| n.starts_with('#'))
                });
                let total = all_data.len();
                // Paginate matching real portal: 14 items per page
                let page: usize = query.extra.iter()
                    .find(|(k,_)| k.as_str() == "p")
                    .and_then(|(_,v)| v.parse().ok())
                    .unwrap_or(0);
                let per_page: usize = 14;
                let start = total.min(page.saturating_mul(per_page));
                let end = total.min(start + per_page);
                let data: Vec<serde_json::Value> = if start < all_data.len() {
                    all_data[start..end].to_vec()
                } else {
                    Vec::new()
                };
                let json = serde_json::json!({
                    "js": {
                        "total_items": total,
                        "max_page_items": 14,
                        "selected_item": 3,
                        "cur_page": page,
                        "data": data
                    }
                });
                let body = serde_json::to_string(&json).unwrap_or_else(|_| r#"{"js":{"data":[]}}"#.into());
                tracing::info!("[PROXY] get_all_channels page={} → {} of {} channels, {} bytes", page, data.len(), total, body.len());
                return Response::builder()
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Content-Length", body.len())
                    .body(Body::from(body))
                    .unwrap();
            }
            "get_all_fav_channels" if query.r#type.as_deref() == Some("itv") => {
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(r#"{"js": {"data": []}}"#))
                    .unwrap();
            }
            "get_categories" if query.r#type.as_deref() == Some("vod") => {
                let filter = st.filter.read().await;
                let filtered: Vec<serde_json::Value> = st.vod_categories.iter()
                    .filter(|item| {
                        let id = item["id"].as_str().unwrap_or("");
                        id != "*" && !filter.is_genre_disabled(st.profile_id, id)
                    })
                    .map(|item| {
                        let mut item = item.clone();
                        if let Some(obj) = item.as_object_mut() {
                            let id = obj.get("id").and_then(|v| v.as_str()).unwrap_or("");
                            let title = obj.get("title").and_then(|v| v.as_str()).unwrap_or("");
                            obj.insert("title".to_string(), serde_json::Value::String(
                                filter.apply_genre_rename(st.profile_id, id, title)
                            ));
                        }
                        item
                    })
                    .collect();
                drop(filter);
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(serde_json::to_string(&serde_json::json!({
                        "js": filtered
                    })).unwrap()))
                    .unwrap();
            }
            "get_categories" if query.r#type.as_deref() == Some("series") => {
                let filter = st.filter.read().await;
                let filtered: Vec<serde_json::Value> = st.series_categories.iter()
                    .filter(|item| {
                        let id = item["id"].as_str().unwrap_or("");
                        id != "*" && !filter.is_genre_disabled(st.profile_id, id)
                    })
                    .map(|item| {
                        let mut item = item.clone();
                        if let Some(obj) = item.as_object_mut() {
                            let id = obj.get("id").and_then(|v| v.as_str()).unwrap_or("");
                            let title = obj.get("title").and_then(|v| v.as_str()).unwrap_or("");
                            obj.insert("title".to_string(), serde_json::Value::String(
                                filter.apply_genre_rename(st.profile_id, id, title)
                            ));
                        }
                        item
                    })
                    .collect();
                drop(filter);
                return Response::builder()
                    .header("Content-Type", "application/json")
                    .body(Body::from(serde_json::to_string(&serde_json::json!({
                        "js": filtered
                    })).unwrap()))
                    .unwrap();
            }
            "create_link" if st.proxy_rewrite => {
                let hls_port = st.hls_bind.trim_start_matches("0.0.0.0:");
                let host = headers.get("host")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|h| h.split(':').next())
                    .unwrap_or("localhost");
                if let Some(cmd) = &query.cmd {
                    let channel_data = {
                        let ch_guard = st.channels.read().await;
                        // Try exact match first
                        if let Some(ch) = ch_guard.get(cmd.as_str()) {
                            Some(ch.clone())
                        } else {
                            // Fallback: match by stream ID extracted from cmd URL (e.g., "stream=691402")
                            let stream_id = extract_stream_id(cmd);
                            ch_guard.values().find(|ch| {
                                if cmd == &ch.cmd { return true; }
                                if !stream_id.is_empty() && ch.cmd.contains(&format!("stream={}", stream_id)) { return true; }
                                false
                            }).cloned()
                        }
                    };
                    if let Some(channel) = channel_data {
                        let allowed = {
                            let filter = st.filter.read().await;
                            filter.is_channel_allowed(st.profile_id, &channel.cmd, &channel.genre_id)
                        };
                        if !allowed {
                            return StatusCode::FORBIDDEN.into_response();
                        }
                        let stream_url = format!("http://{}:{}/{}", host, hls_port, url_encode(&channel.title));
                        let response = generate_create_link_response(&stream_url, &channel.cmd_id, &channel.cmd_ch_id);
                        return Response::builder()
                            .header("Content-Type", "application/json")
                            .body(Body::from(response))
                            .unwrap();
                    }
                    // Channel not found locally, fall through to proxy the create_link request to the real portal
                }
            }
            _ => {}
        }
    }

    // Build proxy request to real portal -- preserve all original params with stable ordering.
    // Stalker middleware validates that type comes before action in the query string.
    // Use a Vec to guarantee order: type, action, cmd, then everything else.
    let is_post = method == Method::POST;
    let mut query_params: Vec<(String, String)> = Vec::new();

    // Always put type first, then action, then cmd -- Stalker middleware is order-sensitive
    if let Some(ref v) = query.r#type  { query_params.push(("type".to_string(),   v.clone())); }
    if let Some(ref v) = query.action  { query_params.push(("action".to_string(), v.clone())); }
    if let Some(ref v) = query.cmd     { query_params.push(("cmd".to_string(),    v.clone())); }

    // Device identity rewriting -- replace STB params with engine's identity
    if query.sn.is_some() {
        query_params.push(("sn".to_string(), st.serial_number.clone()));
    }
    if query.device_id.is_some() {
        query_params.push(("device_id".to_string(), st.device_id.clone()));
    }
    if query.device_id2.is_some() {
        query_params.push(("device_id2".to_string(), st.device_id2.clone()));
    }
    if query.signature.is_some() {
        query_params.push(("signature".to_string(), "f".repeat(64)));
    }

    // Metrics MAC/serial rewriting
    let metrics_rewritten = crate::mag::scrub_metrics(&mut query_params, &st.serial_number, &st.mac);

    // Append remaining extra params, scrubbing STB-generated junk values
    let handled = ["type", "action", "cmd", "sn", "device_id", "device_id2", "signature", "metrics"];
    // Override STB's MAC with the engine's registered MAC so the upstream portal
    // sees the subscribed identity regardless of what STBEmu is configured with.
    query_params.push(("mac".to_string(), st.mac.clone()));
    // Parameters that the engine provides correct values for (override STB's undefined/null/empty)
    let engine_overrides: &[(&str, &str)] = &[
        ("stb_type", &st.model),
        ("client_type", "STB"),
        ("ver", "ImageDescription: 2.20.02-pub-424; ImageDate: Fri May 8 15:39:55 UTC 2020; PORTAL version: 5.3.0; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x588"),
        ("image_version", "220"),
        ("video_out", "hdmi"),
        ("num_banks", "2"),
        ("hw_version", "1.7-BD-00"),
        ("hw_version_2", "1e94aad3c53eeff22eabe78107f368cb9b468fcb"),
        ("api_signature", "262"),
        ("auth_second_step", "1"),
        ("not_valid_token", "0"),
        ("hd", "1"),
    ];
    for (k, v) in &query.extra {
        if handled.contains(&k.as_str()) || (k == "metrics" && metrics_rewritten) {
            continue;
        }
        // Use engine's identity instead of STB's junk (undefined, null, empty)
        if let Some(replacement) = engine_overrides.iter().find(|(ek, _)| ek == k) {
            query_params.push((k.clone(), replacement.1.to_string()));
        } else if v == "undefined" || v == "null" {
            // Skip STB-generated undefined/null values -- they break portal validation
            continue;
        } else {
            query_params.push((k.clone(), v.clone()));
        }
    }

    let request_path = uri.path();
    // API requests go to /portal.php. STBEmu uses /portal.php directly,
    // TiviMate uses /server/load.php. Static assets use portal_root.
    // Check the ORIGINAL query for API markers (type/action), not the
    // proxy-built query_params (which includes MAC on every request).
    let is_api = request_path.ends_with(".php")
        || query.r#type.is_some()
        || query.action.is_some();
    let api_base = if is_api {
        match url::Url::parse(&st.portal_base) {
            Ok(u) => {
                format!("{}://{}/portal.php", u.scheme(), u.host_str().unwrap_or(""))
            }
            Err(_) => st.portal_base.clone()
        }
    } else {
        st.portal_base.clone()
    };

    let final_url = if query_params.is_empty() {
        // Static asset or root request -- proxy to portal_root with the request path
        let stripped = if request_path.starts_with(&st.portal_root_path) {
            &request_path[st.portal_root_path.len()..]
        } else {
            request_path
        };
        let stripped = stripped.trim_start_matches('/');
        if stripped.is_empty() {
            st.portal_root.clone()
        } else {
            format!("{}{}", st.portal_root, stripped)
        }
    } else {
        let qs: Vec<String> = query_params.iter()
            .map(|(k, v)| format!("{}={}", k, url_encode(v)))
            .collect();
        format!("{}?{}", api_base, qs.join("&"))
    };

    // Manual redirect loop -- preserve all headers (Authorization, Cookie) on every hop.
    // DNS is re-resolved on every hop to ensure pinning persists across cross-domain redirects.
    let mut current_url = final_url;
    let max_redirects = 200;
    let mut response = None;
    let mut is_458 = false;

    for hop in 0..=max_redirects {
        let parsed_url = match Url::parse(&current_url) {
            Ok(u) => u,
            Err(_) => return StatusCode::BAD_GATEWAY.into_response(),
        };
        let current_host = parsed_url.host_str().unwrap_or("").to_string();
        let current_port = parsed_url.port_or_known_default().unwrap_or(443);
        let eur_ips = dns::resolve_european(&current_host).await;

        let pinned_client;
        let client: &reqwest::Client;
        if !eur_ips.is_empty() {
            let mut builder = reqwest::Client::builder()
                .timeout(Duration::from_secs(300))
                .pool_max_idle_per_host(50)
                .redirect(reqwest::redirect::Policy::none())
                .resolve(&current_host, SocketAddr::new(eur_ips[0], current_port));

            builder = stalker::PortalClient::configure_stealth_client(builder);

            match builder.build() {
                Ok(c) => { pinned_client = Some(c); client = pinned_client.as_ref().unwrap(); }
                Err(_) => { client = &st.portal_http_client; }
            }
        } else {
            client = &st.portal_http_client;
        }

        let proxy_req = if is_post && hop == 0 {
            client.post(&current_url).body(body.clone())
        } else {
            client.get(&current_url).body(axum::body::Bytes::new())
        };
        let mut proxy_req = proxy_req;

        // Forward client headers (same filtering as before)
        for (key, val) in headers.iter() {
            let ks = key.as_str().to_lowercase();
            match ks.as_str() {
                "host" | "cookie" | "authorization" | "referer" | "referrer" | "origin"
                | "connection" | "transfer-encoding" | "keep-alive"
                | "te" | "trailer" | "upgrade" | "proxy-authorization"
                | "proxy-authenticate" | "content-length" | "content-type"
                | "accept-encoding" | "content-encoding" => continue,
                _ => { proxy_req = proxy_req.header(key, val); }
            }
        }

        // Apply MAG headers
        let current_token = st.token.read().await.clone();
        proxy_req = crate::mag::apply_mag_headers(
            proxy_req, &current_token, &st.serial_number, &st.mac, &st.timezone, &st.model, &current_host
        );
        let referer_host = match current_url.splitn(2, "://").nth(1).and_then(|r| r.split('/').next()) {
            Some(h) => format!("{}://{}/", if current_url.starts_with("https") { "https" } else { "http" }, h),
            None => format!("{}/", st.portal_base.trim_end_matches('/')),
        };
        proxy_req = proxy_req
            .header("Referer", &referer_host)
            .header("Origin", referer_host.trim_end_matches('/'));

        tracing::info!("[PROXY] -> upstream {} (hop {})", &current_url, hop);

        match proxy_req.send().await {
            Ok(resp) => {
                let status = resp.status();
                is_458 = status.as_u16() == 458;

                if status.is_redirection() && hop < max_redirects {
                    if let Some(location) = resp.headers().get(reqwest::header::LOCATION) {
                        let dest = match location.to_str() {
                            Ok(d) => d.to_string(),
                            Err(_) => break,
                        };
                        current_url = if dest.starts_with("http://") || dest.starts_with("https://") {
                            dest
                        } else {
                            Url::parse(&current_url)
                                .ok()
                                .and_then(|u| u.join(&dest).ok().map(|u| u.to_string()))
                                .unwrap_or(dest)
                        };
                        tracing::info!("[PROXY] redirect {}: {} -> {}", hop + 1, status.as_u16(), current_url);
                        continue;
                    }
                }

                response = Some((status, resp));
                break;
            }
            Err(e) => {
                tracing::warn!("Proxy upstream error at hop {hop}: {e}, retrying...");
                if hop < max_redirects {
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    continue;
                }
                tracing::error!("Proxy upstream error after {} retries: {e}", max_redirects);
                return StatusCode::BAD_GATEWAY.into_response();
            }
        }
    }

    // Handle 458 (Cloudflare ban) -- refresh channels and retry for create_link
    if is_458 && query.action.as_deref() == Some("create_link") {
        tracing::warn!("[PROXY] got 458 for create_link, refreshing channels...");
        match proxy_refresh_and_retry(&st, &query, &headers, &current_url, &st.portal_http_client).await {
            Ok(r) => return r,
            Err(e) => {
                tracing::error!("Proxy 458 retry failed: {e}");
            }
        }
    }

    let (status, resp) = match response {
        Some(r) => r,
        None => return StatusCode::BAD_GATEWAY.into_response(),
    };
    let resp_headers = resp.headers().clone();
    let body_bytes = resp.bytes().await.unwrap_or_default();
    let body_preview_for = query.action.as_deref().unwrap_or("");
    let media_type = query.r#type.as_deref().unwrap_or("");
    tracing::info!("[PROXY] <- status={} bytes={} action={} type={}", status.as_u16(), body_bytes.len(), body_preview_for, media_type);

    // Override response status for error cases where we substitute the body
    let mut response_status = status;
    // Apply channel filtering and renaming to channel list responses
    let final_body = if status.is_success() {
        let filter = st.filter.read().await;
        // Always apply prefix stripping via rewrite_channel_list_response,
        // even when no filters are configured. Filtering is gated inside.
        let genre = query.extra.get("genre").map(String::as_str);
        rewrite_channel_list_response(&body_bytes, body_preview_for, media_type, &filter, st.profile_id, genre)
            .map(Vec::from)
            .unwrap_or_else(|| {
                tracing::trace!("[PROXY] rewrite_channel_list_response returned None for action={} type={} -- using original body", body_preview_for, media_type);
                body_bytes.to_vec()
            })
    } else {
        match (body_preview_for, media_type) {
            ("get_ordered_list", "itv" | "vod" | "series") => {
                response_status = StatusCode::OK;
                serde_json::to_vec(&serde_json::json!({"js": {"total_items": 0, "max_page_items": 14, "selected_item": 0, "cur_page": 1, "data": []}}))
                    .unwrap_or_else(|_| body_bytes.to_vec())
            }
            ("get_genres", "itv") => {
                serde_json::to_vec(&serde_json::json!({"js": []}))
                    .unwrap_or_else(|_| body_bytes.to_vec())
            }
            _ => body_bytes.to_vec(),
        }
    };
    if body_preview_for == "get_ordered_list" || body_preview_for == "set_fav_status" {
        tracing::info!("[PROXY] final_body size={} for action={}", final_body.len(), body_preview_for);
    }
    if body_preview_for == "get_ordered_list" {
        tracing::info!("[PROXY] upstream Content-Type: {:?}", resp_headers.get("content-type"));
    }
    let mut response = Response::builder().status(response_status);
    for (key, val) in resp_headers.iter() {
        let key_str = key.as_str().to_lowercase();
        match key_str.as_str() {
            "host" | "connection" | "transfer-encoding" | "keep-alive"
            | "te" | "trailer" | "upgrade" | "proxy-authorization"
            | "proxy-authenticate" | "content-length" | "content-encoding" => continue,
            _ => { response = response.header(key, val); }
        }
    }
    response.body(Body::from(final_body.to_vec())).unwrap()
}

/// Extract the stream ID from a cmd/URL like "ffmpeg http://.../live.php?...&stream=691402&..."
pub(crate) fn extract_stream_id(cmd: &str) -> String {
    // Try to find "stream=XXXXX" pattern in the URL
    if let Some(pos) = cmd.find("stream=") {
        let rest = &cmd[pos + 7..];
        rest.chars().take_while(|c| c.is_ascii_digit()).collect()
    } else {
        String::new()
    }
}

fn generate_create_link_response(stream_url: &str, id: &str, ch_id: &str) -> String {
    let link_id = ch_id.parse::<u64>().unwrap_or(0);
    // Only escape slashes after the scheme -- the STB JS calls JSON.parse() on this
    // and then does its own URL handling. Escaping "://" breaks protocol parsing.
    let escaped = if let Some(rest) = stream_url.find("://").map(|i| i + 3).and_then(|i| Some((i, stream_url))) {
        let (after_scheme, s) = rest;
        format!("{}{}", &s[..after_scheme], &s[after_scheme..].replace('/', "\\/"))
    } else {
        stream_url.replace('/', "\\/")
    };
    format!(
        r#"{{"js":{{"id":"{}","cmd":"{}","streamer_id":0,"link_id":{},"load":0,"error":""}},"text":""}}"#,
        id, escaped, link_id
    )
}

/// Retry a create_link request after re-fetching channels to get a fresh play_token.
/// Called when the portal returned 458 (Cloudflare ban / stale token).
async fn proxy_refresh_and_retry(
    st: &ProxyState, query: &ProxyQuery, headers: &HeaderMap, _original_url: &str, client: &reqwest::Client,
) -> Result<Response, Box<dyn std::error::Error + Send + Sync>> {
    // Re-authenticate and re-fetch channels to get a fresh token and play_tokens.
    // Release the write lock immediately after the network call so concurrent
    // STB requests are not blocked for the duration of the HTTP round-trip.
    let (fresh, fresh_token) = {
        let mut client_lock = st.portal_client.write().await;
        if let Err(e) = client_lock.authenticate().await {
            tracing::warn!("[PROXY] re-auth failed during 458 retry: {e}");
        }
        let channels = client_lock.get_channels().await?;
        let token = client_lock.token.clone();
        (channels, token)
    };

    // Update token and channel map so all subsequent requests use fresh data
    *st.token.write().await = fresh_token.clone();
    {
        let mut ch_guard = st.channels.write().await;
        ch_guard.clear();
        for ch in &fresh {
            ch_guard.insert(ch.cmd.clone(), ch.clone());
        }
    }

    // Find matching channel cmd in fresh data
    let cmd = query.cmd.as_deref().unwrap_or("");
    let fresh_cmd = fresh.iter()
        .find(|ch| ch.cmd == cmd || ch.title == cmd)
        .map(|ch| ch.cmd.clone())
        .unwrap_or_else(|| cmd.to_string());

    // Build fresh create_link URL
    let encoded_cmd: String = fresh_cmd.split_whitespace()
        .map(|s| url_encode(s))
        .collect::<Vec<_>>()
        .join("%20");
    let fresh_url = format!(
        "{}?action=create_link&type=itv&cmd={}&JsHttpRequest=1-xml",
        st.portal_base, encoded_cmd
    );

    tracing::info!("[PROXY] 458 retry with fresh token: {}", fresh_url);

    let mut proxy_req = client.get(&fresh_url);
    for (key, val) in headers.iter() {
        let ks = key.as_str().to_lowercase();
        match ks.as_str() {
            "host" | "cookie" | "authorization" | "referer" | "referrer" | "origin"
            | "connection" | "transfer-encoding" | "keep-alive"
            | "te" | "trailer" | "upgrade" | "proxy-authorization"
            | "proxy-authenticate" | "content-length" | "content-type"
            | "accept-encoding" | "content-encoding" => continue,
            _ => { proxy_req = proxy_req.header(key, val); }
        }
    }
    let host_str = url::Url::parse(&fresh_url).ok().and_then(|u| u.host_str().map(|s| s.to_string())).unwrap_or_default();
    proxy_req = crate::mag::apply_mag_headers(
        proxy_req, &fresh_token, &st.serial_number, &st.mac, &st.timezone, &st.model,
        &host_str
    );
    let referer_host = st.portal_base.trim_end_matches('/');
    proxy_req = proxy_req.header("Referer", format!("{}/", referer_host))
        .header("Origin", referer_host);

    let resp = proxy_req.send().await?;
    let status = resp.status();
    let resp_headers = resp.headers().clone();
    let body_bytes = resp.bytes().await.unwrap_or_default();

    let mut response = Response::builder().status(status);
    for (key, val) in resp_headers.iter() {
        let key_str = key.as_str().to_lowercase();
        match key_str.as_str() {
            "host" | "connection" | "transfer-encoding" | "keep-alive"
            | "te" | "trailer" | "upgrade" | "proxy-authorization"
            | "proxy-authenticate" | "content-length" | "content-encoding" => continue,
            _ => { response = response.header(key, val); }
        }
    }
    Ok(response.body(Body::from(body_bytes.to_vec())).unwrap())
}

/// Extract a string value from a JSON value that may be a string or number.
fn json_str<'a>(val: &'a serde_json::Value, buf: &'a mut String) -> &'a str {
    match val {
        serde_json::Value::String(s) => s.as_str(),
        serde_json::Value::Number(n) => {
            *buf = n.to_string();
            buf.as_str()
        }
        _ => "",
    }
}

/// Intercept channel list responses from the portal and apply filtering/renaming.
/// Handles `get_all_channels` (ITV) and `get_ordered_list` (ITV/VOD/Series).
/// get_ordered_list passes through unfiltered -- genre-level filtering is enforced
/// by the genre list (hides disabled genres) and create_link (blocks playback).
fn rewrite_channel_list_response(
    body: &[u8],
    action: &str,
    media_type: &str,
    filter: &FilterStore,
    profile_id: i32,
    _genre: Option<&str>,

) -> Option<Vec<u8>> {
    let mut json: serde_json::Value = serde_json::from_slice(body).ok()?;

    match (action, media_type) {
        ("get_ordered_list", "itv" | "vod" | "series") => {
            // Apply rename + filter # separators only. No dedup — portal paginates.
            if let Some(data) = json["js"]["data"].as_array_mut() {
                for item in data.iter_mut() {
                    if let Some(name) = item["name"].as_str() {
                        item["name"] = serde_json::Value::String(
                            filter.apply_rename(profile_id, name)
                        );
                    }
                }
                data.retain(|item| {
                    !item["name"].as_str().map_or(false, |n| n.starts_with('#'))
                });
            }
            Some(serde_json::to_vec(&json).ok()?)
        }
        // Handle get_all_channels response (used by Kotlin backend)
        ("get_all_channels", "itv") => {
            let data = json["js"]["data"].as_array_mut()?;
            let mut cmd_buf = String::new();
            let mut genre_buf = String::new();
            let mut name_buf = String::new();
            *data = std::mem::take(data).into_iter().filter(|item| {
                let cmd = json_str(&item["cmd"], &mut cmd_buf);
                let genre_id = json_str(&item["tv_genre_id"], &mut genre_buf);
                let name = json_str(&item["name"], &mut name_buf);
                if name.starts_with('#') { return false; }
                filter.is_channel_allowed(profile_id, cmd, genre_id)
            }).collect();
            // Rename only — no dedup
            for item in data.iter_mut() {
                if let Some(name) = item["name"].as_str() {
                    let renamed = filter.apply_rename(profile_id, name);
                    item["name"] = serde_json::Value::String(renamed);
                }
            }
            Some(serde_json::to_vec(&json).ok()?)
        }
        // Filter disabled genres from genre/category lists shown in STB
        ("get_genres", "itv") | ("get_categories", "vod" | "series") => {
            let items = json["js"].as_array_mut()?;
            let mut id_buf = String::new();
            *items = std::mem::take(items).into_iter().filter(|item| {
                let id = json_str(&item["id"], &mut id_buf);
                !id.is_empty() && id != "*" && !filter.is_genre_disabled(profile_id, id)
            }).collect();
            // Apply genre renames
            for item in items.iter_mut() {
                if let Some(obj) = item.as_object_mut() {
                    let id = obj.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                    let title = obj.get("title").and_then(|v| v.as_str()).unwrap_or("").to_string();
                    if !id.is_empty() {
                        obj.insert("title".to_string(), serde_json::Value::String(
                            filter.apply_genre_rename(profile_id, &id, &title)
                        ));
                    }
                }
            }
            Some(serde_json::to_vec(&json).ok()?)
        }
        _ => None,
    }
}

fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => { out.push('%'); out.push_str(&format!("{:02X}", b)); }
        }
    }
    out
}

