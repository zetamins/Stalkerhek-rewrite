use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::{AppState, ProfileConfig, ProfileRunner, ProfileStatus};
use crate::hls;
use crate::proxy;
use crate::stalker;

pub(crate) fn save_profiles(profiles: &[ProfileConfig], data_dir: &std::path::Path) {
    if let Ok(data) = serde_json::to_string_pretty(profiles) {
        let _ = std::fs::create_dir_all(data_dir);
        let _ = std::fs::write(data_dir.join("profiles.json"), data);
    }
}

pub(crate) fn save_filters(filters: &crate::filter::FilterStore, data_dir: &std::path::Path) {
    use std::collections::HashSet;
    let all_ids: HashSet<i32> = filters.disabled_genres.keys()
        .chain(filters.disabled_channels.keys())
        .chain(filters.enabled_channels.keys())
        .chain(filters.rename_prefix.keys())
        .chain(filters.rename_suffix.keys())
        .chain(filters.genre_renames.keys())
        .chain(filters.versions.keys())
        .copied().collect();
    let snapshot: HashMap<i32, SyncFilterState> = all_ids.iter().map(|pid| {
        (*pid, SyncFilterState {
            disabled_genres: filters.disabled_genres.get(pid)
                .map(|s| s.iter().map(|k| (k.clone(), true)).collect())
                .unwrap_or_default(),
            disabled_channels: filters.disabled_channels.get(pid)
                .map(|s| s.iter().map(|k| (k.clone(), true)).collect())
                .unwrap_or_default(),
            enabled_channels: filters.enabled_channels.get(pid)
                .map(|s| s.iter().map(|k| (k.clone(), true)).collect())
                .unwrap_or_default(),
            rename_prefix: filters.rename_prefix.get(pid).cloned().unwrap_or_default(),
            rename_suffix: filters.rename_suffix.get(pid).cloned().unwrap_or_default(),
            genre_renames: filters.genre_renames.get(pid).cloned().unwrap_or_default(),
            version: *filters.versions.get(pid).unwrap_or(&0),
        })
    }).collect();
    if let Ok(data) = serde_json::to_string_pretty(&snapshot) {
        let _ = std::fs::create_dir_all(data_dir);
        let _ = std::fs::write(data_dir.join("filters.json"), data);
    }
}

pub fn load_profiles(data_dir: &std::path::Path) -> Vec<ProfileConfig> {
    std::fs::read_to_string(data_dir.join("profiles.json"))
        .ok()
        .and_then(|data| serde_json::from_str(&data).ok())
        .unwrap_or_default()
}

pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/api/v1/profile", post(create_profile))
        .route("/api/v1/profile/:id", get(get_profile).delete(delete_profile))
        .route("/api/v1/profile/:id/start", post(start_profile))
        .route("/api/v1/profile/:id/stop", post(stop_profile))
        .route("/api/v1/profile/:id/status", get(profile_status))
        .route("/api/v1/profile/:id/channels", get(profile_channels))
        .route("/api/v1/profile/:id/categories", get(profile_categories))
        .route("/api/v1/profiles", get(list_profiles))
        .route("/api/v1/filters", get(get_filters).post(set_filters))
        .route("/api/v1/filters/sync", post(sync_filters))
        .route("/api/v1/filters/reset/:id", post(reset_filters))
        .route("/api/v1/settings/runtime", get(get_runtime_settings).post(set_runtime_settings))
        .with_state(state)
        .layer(tower_http::trace::TraceLayer::new_for_http())
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "status": "ok",
        "service": "stalkerhek-engine"
    }))
}

async fn list_profiles(State(st): State<AppState>) -> Json<Vec<ProfileConfig>> {
    let profiles = st.profiles.read().await;
    Json(profiles.clone())
}

#[derive(Deserialize, Serialize)]
struct CreateProfileRequest {
    id: Option<i32>,
    name: String,
    portal_url: String,
    mac: String,
    username: Option<String>,
    password: Option<String>,
    hls_port: Option<u16>,
    proxy_port: Option<u16>,
    timezone: Option<String>,
    model: Option<String>,
    device_id_auth: Option<bool>,
    hls_enabled: Option<bool>,
    proxy_enabled: Option<bool>,
    proxy_rewrite: Option<bool>,
    serial_number: Option<String>,
    device_id: Option<String>,
    device_id2: Option<String>,
    signature: Option<String>,
    watchdog_interval: Option<u32>,
}

async fn create_profile(
    State(st): State<AppState>,
    Json(req): Json<CreateProfileRequest>,
) -> Result<Json<ProfileConfig>, StatusCode> {
    tracing::info!("create_profile called: {:?}", serde_json::to_string(&req).unwrap_or_default());
    let mut profiles = st.profiles.write().await;
    let new_id = req.id.unwrap_or_else(|| profiles.iter().map(|p| p.id).max().unwrap_or(0) + 1);
    // Upsert: replace existing profile with same id
    if let Some(pos) = profiles.iter().position(|p| p.id == new_id) {
        profiles.remove(pos);
    }
    let cfg = ProfileConfig {
        id: new_id,
        name: req.name,
        portal_url: req.portal_url,
        mac: req.mac.to_uppercase(),
        username: req.username.unwrap_or_default(),
        password: req.password.unwrap_or_default(),
        hls_port: req.hls_port.unwrap_or(4600 + (new_id as u16).saturating_sub(1) * 2),
        proxy_port: req.proxy_port.unwrap_or(4601 + (new_id as u16).saturating_sub(1) * 2),
        timezone: req.timezone.unwrap_or_else(|| "UTC".to_string()),
        serial_number: req.serial_number.unwrap_or_else(|| "0000000000000".to_string()),
        device_id: req.device_id.unwrap_or_else(|| "f".repeat(64)),
        device_id2: req.device_id2.unwrap_or_else(|| "f".repeat(64)),
        signature: req.signature.unwrap_or_else(|| "f".repeat(64)),
        model: req.model.unwrap_or_else(|| "MAG254".to_string()),
        device_id_auth: req.device_id_auth.unwrap_or(true),
        watchdog_interval: req.watchdog_interval.unwrap_or(5),
        hls_enabled: req.hls_enabled.unwrap_or(true),
        proxy_enabled: req.proxy_enabled.unwrap_or(true),
        proxy_rewrite: req.proxy_rewrite.unwrap_or(true),
    };
    profiles.push(cfg.clone());
    save_profiles(&profiles, &st.data_dir);
    Ok(Json(cfg))
}

async fn get_profile(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> Result<Json<ProfileConfig>, StatusCode> {
    let profiles = st.profiles.read().await;
    profiles.iter().find(|p| p.id == id)
        .ok_or(StatusCode::NOT_FOUND)
        .map(|p| Json(p.clone()))
}

async fn delete_profile(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> StatusCode {
    let mut profiles = st.profiles.write().await;
    if let Some(pos) = profiles.iter().position(|p| p.id == id) {
        // Stop if running
        let mut runners = st.runners.write().await;
        if let Some(rpos) = runners.iter().position(|r| r.config.id == id) {
            let runner = runners.swap_remove(rpos);
            if let Some(cancel) = runner.cancel_hls {
                let _ = cancel.send(());
            }
            if let Some(cancel) = runner.cancel_proxy {
                let _ = cancel.send(());
            }
            if let Some(cancel) = runner.cancel_watchdog {
                let _ = cancel.send(());
            }
        }
        save_profiles(&profiles, &st.data_dir);
        StatusCode::OK
    } else {
        StatusCode::NOT_FOUND
    }
}

async fn start_profile(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    start_profile_by_id(&st, id).await.map(Json)
}

/// Start a profile by ID. Returns JSON with ok/id/channels on success.
pub async fn start_profile_by_id(
    st: &AppState,
    id: i32,
) -> Result<serde_json::Value, StatusCode> {
    let profile = {
        let profiles = st.profiles.read().await;
        profiles.iter().find(|p| p.id == id).cloned()
    };
    let profile = profile.ok_or(StatusCode::NOT_FOUND)?;

    // Stop existing runner if any
    {
        let mut runners = st.runners.write().await;
        if let Some(pos) = runners.iter().position(|r| r.config.id == id) {
            let runner = runners.swap_remove(pos);
            if let Some(cancel) = runner.cancel_hls {
                let _ = cancel.send(());
            }
            if let Some(cancel) = runner.cancel_proxy {
                let _ = cancel.send(());
            }
            if let Some(cancel) = runner.cancel_watchdog {
                let _ = cancel.send(());
            }
        }
    }

    // Build portal client with European DNS resolution and authenticate
    let portal_client = Arc::new(RwLock::new(
        stalker::PortalClient::new(
            profile.portal_url.clone(),
            profile.mac.clone(),
            profile.username.clone(),
            profile.password.clone(),
            profile.serial_number.clone(),
            profile.device_id.clone(),
            profile.device_id2.clone(),
            profile.signature.clone(),
            profile.model.clone(),
            profile.timezone.clone(),
            profile.device_id_auth,
        )
    ));

    // Resolve portal via European DNS to bypass geo-blocking
    {
        let mut client = portal_client.write().await;
        client.resolve_eu_dns().await;
    }

    // Authenticate
    {
        let mut client = portal_client.write().await;
        if let Err(e) = client.authenticate().await {
            tracing::error!("Auth failed for profile {}: {}", id, e);
            return Err(StatusCode::UNAUTHORIZED);
        }
    }

    // Fetch channels
    let channels = {
        let client = portal_client.read().await;
        match client.get_channels().await {
            Ok(chs) => chs,
            Err(e) => {
                tracing::error!("Channel fetch failed for profile {}: {}", id, e);
                return Err(StatusCode::BAD_GATEWAY);
            }
        }
    };

    let channel_count = channels.len();
    tracing::info!("Profile {}: got {} channels", id, channel_count);

    // Fetch VOD and Series categories
    let vod_categories = {
        let client = portal_client.read().await;
        client.get_vod_categories().await.unwrap_or_default()
    };
    let series_categories = {
        let client = portal_client.read().await;
        client.get_series_categories().await.unwrap_or_default()
    };
    tracing::info!("Profile {}: got {} vod categories, {} series categories", id, vod_categories.len(), series_categories.len());

    // Prune stale genre IDs from filter state (genre IDs can change when the
    // portal reassigns them between restarts, making filters silently ineffective).
    // Collect IDs from ITV channels, VOD categories, and series categories.
    {
        let mut current_genre_ids: HashSet<String> = channels.iter()
            .map(|ch| ch.genre_id.clone())
            .filter(|id| !id.is_empty())
            .collect();
        for cat in &vod_categories {
            if let Some(id) = cat["id"].as_str().filter(|s| !s.is_empty() && *s != "*") {
                current_genre_ids.insert(id.to_string());
            }
        }
        for cat in &series_categories {
            if let Some(id) = cat["id"].as_str().filter(|s| !s.is_empty() && *s != "*") {
                current_genre_ids.insert(id.to_string());
            }
        }
        if !current_genre_ids.is_empty() {
            let mut filters = st.filters.write().await;
            if filters.prune_stale_genres(id, &current_genre_ids) {
                save_filters(&filters, &st.data_dir);
            }
        }
    }

    let filter = st.filters.clone();
    let profile_id = id;
    let hls_enabled = profile.hls_enabled;
    let proxy_enabled = profile.proxy_enabled;

    // Start watchdog
    let watchdog_portal = portal_client.clone();
    let watchdog_interval = profile.watchdog_interval;
    let (watchdog_cancel_tx, watchdog_cancel_rx) = tokio::sync::oneshot::channel::<()>();
    if watchdog_interval > 0 {
        tokio::spawn(async move {
            let mut cancel = watchdog_cancel_rx;
            loop {
                tokio::select! {
                    _ = &mut cancel => {
                        tracing::info!("Watchdog cancelled for profile {profile_id}");
                        break;
                    }
                    _ = tokio::time::sleep(tokio::time::Duration::from_secs(watchdog_interval as u64 * 60)) => {
                        let client = watchdog_portal.read().await;
                        if let Err(e) = client.watchdog_update().await {
                            tracing::warn!("Watchdog update failed for profile {profile_id}: {e}");
                        }
                    }
                }
            }
        });
    }

    let (hls_cancel_tx, hls_cancel_rx) = tokio::sync::oneshot::channel::<()>();
    let (proxy_cancel_tx, proxy_cancel_rx) = tokio::sync::oneshot::channel::<()>();

    // Start HLS server
    if hls_enabled {
        let hls_bind = format!("0.0.0.0:{}", profile.hls_port);
        let hls_channels = channels.clone();
        let hls_filter = filter.clone();
        let hls_token = {
            let c = portal_client.read().await;
            c.token.clone()
        };
        let hls_serial = profile.serial_number.clone();
        let hls_mac = profile.mac.clone();
        let hls_tz = profile.timezone.clone();
        let hls_model = profile.model.clone();
        let hls_portal_client = portal_client.clone();
        tokio::spawn(async move {
            let app = hls::build_router(
                hls_channels, hls_filter, hls_portal_client, profile_id,
                hls_token, hls_serial, hls_mac, hls_tz, hls_model,
            );
            let listener = match tokio::net::TcpListener::bind(&hls_bind).await {
                Ok(l) => l,
                Err(e) => {
                    tracing::error!("HLS server bind failed on {hls_bind}: {e}");
                    return;
                }
            };
            tracing::info!("HLS server for profile {profile_id} on {hls_bind}");
            axum::serve(listener, app)
                .with_graceful_shutdown(async { let _ = hls_cancel_rx.await; })
                .await
                .ok();
        });
    }

    // Start Proxy server
    if proxy_enabled {
        let proxy_bind = format!("0.0.0.0:{}", profile.proxy_port);
        let proxy_channels = channels.clone();
        let proxy_filter = filter.clone();
        let proxy_base = profile.portal_url.clone();
        let token = {
            let c = portal_client.read().await;
            c.token.clone()
        };
        let serial_number = profile.serial_number.clone();
        let mac = profile.mac.clone();
        let timezone = profile.timezone.clone();
        let model = profile.model.clone();
        let device_id = profile.device_id.clone();
        let device_id2 = profile.device_id2.clone();
        let hls_port = profile.hls_port;
        let profile_cfg = profile.clone();
        let proxy_vod_cats = vod_categories.clone();
        let proxy_series_cats = series_categories.clone();
        let proxy_portal_client = portal_client.clone();

        tokio::spawn(async move {
            let app = proxy::build_router(
                proxy_base,
                proxy_channels,
                proxy_filter,
                profile_id,
                profile_cfg.proxy_rewrite,
                token,
                serial_number,
                mac,
                timezone,
                model,
                device_id,
                device_id2,
                format!("0.0.0.0:{}", hls_port),
                proxy_vod_cats,
                proxy_series_cats,
                proxy_portal_client,
            );
            let listener = match tokio::net::TcpListener::bind(&proxy_bind).await {
                Ok(l) => l,
                Err(e) => {
                    tracing::error!("Proxy server bind failed on {proxy_bind}: {e}");
                    return;
                }
            };
            tracing::info!("Proxy server for profile {profile_id} on {proxy_bind}");
            axum::serve(listener, app)
                .with_graceful_shutdown(async { let _ = proxy_cancel_rx.await; })
                .await
                .ok();
        });
    }

    let status = Arc::new(RwLock::new(ProfileStatus {
        id: profile.id,
        phase: "success".to_string(),
        message: "Running".to_string(),
        channels_count: channel_count,
        hls_addr: format!(":{}", profile.hls_port),
        proxy_addr: format!(":{}", profile.proxy_port),
        running: true,
    }));

    let runner = ProfileRunner {
        config: profile,
        cancel_hls: Some(hls_cancel_tx),
        cancel_proxy: Some(proxy_cancel_tx),
        cancel_watchdog: Some(watchdog_cancel_tx),
        channels: Arc::new(RwLock::new(Some(channels))),
        vod_channels: Arc::new(RwLock::new(None)),
        series_channels: Arc::new(RwLock::new(None)),
        portal_token: Arc::new(RwLock::new(portal_client.read().await.token.clone())),
        status,
        vod_categories: Arc::new(RwLock::new(vod_categories)),
        series_categories: Arc::new(RwLock::new(series_categories)),
    };

    st.runners.write().await.push(runner);

    Ok(serde_json::json!({
        "ok": true,
        "id": id,
        "channels": channel_count,
    }))
}

async fn stop_profile(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let mut runners = st.runners.write().await;
    if let Some(pos) = runners.iter().position(|r| r.config.id == id) {
        let runner = runners.swap_remove(pos);
        if let Some(cancel) = runner.cancel_hls {
            let _ = cancel.send(());
        }
        if let Some(cancel) = runner.cancel_proxy {
            let _ = cancel.send(());
        }
        if let Some(cancel) = runner.cancel_watchdog {
            let _ = cancel.send(());
        }
        Ok(Json(serde_json::json!({"ok": true})))
    } else {
        Err(StatusCode::NOT_FOUND)
    }
}

async fn profile_status(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> Json<serde_json::Value> {
    let runners = st.runners.read().await;
    if let Some(runner) = runners.iter().find(|r| r.config.id == id) {
        let status = runner.status.read().await;
        Json(serde_json::to_value(&*status).unwrap_or_default())
    } else {
        Json(serde_json::json!({
            "phase": "idle",
            "message": "Not running",
            "running": false
        }))
    }
}

async fn profile_categories(
    State(st): State<AppState>,
    Path(id): Path<i32>,
    Query(params): Query<HashMap<String, String>>,
) -> Json<serde_json::Value> {
    let media_type = params.get("type").map(|s| s.as_str()).unwrap_or("vod");
    let runners = st.runners.read().await;
    if let Some(runner) = runners.iter().find(|r| r.config.id == id) {
        let cats = match media_type {
            "series" => runner.series_categories.read().await.clone(),
            _ => runner.vod_categories.read().await.clone(),
        };
        let filtered: Vec<serde_json::Value> = cats.into_iter()
            .filter(|item| item["id"].as_str().map_or(false, |id| id != "*"))
            .map(|item| {
                serde_json::json!({
                    "id": item["id"].as_str().unwrap_or(""),
                    "title": item["title"].as_str().unwrap_or("")
                })
            })
            .collect();
        return Json(serde_json::json!(filtered));
    }
    Json(serde_json::json!([]))
}

async fn profile_channels(
    State(st): State<AppState>,
    Path(id): Path<i32>,
    Query(params): Query<HashMap<String, String>>,
) -> Json<serde_json::Value> {
    let runners = st.runners.read().await;
    if let Some(runner) = runners.iter().find(|r| r.config.id == id) {
        let channel_type = params.get("type").map(|s| s.as_str()).unwrap_or("itv");
        let channels = match channel_type {
            "vod" => runner.vod_channels.read().await,
            "series" => runner.series_channels.read().await,
            _ => runner.channels.read().await,
        };
        Json(serde_json::json!(channels.as_ref()))
    } else {
        Json(serde_json::json!(null))
    }
}

async fn get_filters(
    State(st): State<AppState>,
) -> Json<serde_json::Value> {
    let filters = st.filters.read().await;
    Json(serde_json::to_value(&*filters).unwrap_or_default())
}

async fn set_filters(
    State(st): State<AppState>,
    Json(req): Json<HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let mut filters = st.filters.write().await;
    let profile_id = req.get("profile_id").and_then(|s| s.parse::<i32>().ok()).unwrap_or(0);
    let action = req.get("action").map(|s| s.as_str()).unwrap_or("");

    match action {
        "toggle_genre" => {
            if let Some(gid) = req.get("genre_id").filter(|s| !s.is_empty()) {
                let disabled = req.get("disabled").map(|s| s == "1").unwrap_or(true);
                filters.set_genre_disabled(profile_id, gid.clone(), disabled);
            }
        }
        "toggle_channel" => {
            if let Some(cmd) = req.get("cmd").filter(|s| !s.is_empty()) {
                let disabled = req.get("disabled").map(|s| s == "1").unwrap_or(true);
                filters.set_channel_disabled(profile_id, cmd.clone(), disabled);
            }
        }
        "reset" => {
            filters.reset_profile(profile_id);
        }
        "rename" => {
            filters.set_rename(
                profile_id,
                req.get("rename_prefix").cloned().unwrap_or_default(),
                req.get("rename_suffix").cloned().unwrap_or_default(),
            );
        }
        "rename_genre" => {
            let gid = req.get("genre_rename_id").filter(|s| !s.is_empty());
            let gname = req.get("genre_rename_name").filter(|s| !s.is_empty());
            if let (Some(gid), Some(gname)) = (gid, gname) {
                filters.set_genre_rename(profile_id, gid.clone(), gname.clone());
            }
        }
        _ => return Err(StatusCode::BAD_REQUEST),
    }
    save_filters(&filters, &st.data_dir);
    Ok(Json(serde_json::json!({"ok": true})))
}

async fn reset_filters(
    State(st): State<AppState>,
    Path(id): Path<i32>,
) -> Json<serde_json::Value> {
    let mut filters = st.filters.write().await;
    filters.reset_profile(id);
    save_filters(&filters, &st.data_dir);
    Json(serde_json::json!({"ok": true}))
}

#[derive(Debug, Deserialize, Serialize, Clone)]
struct RuntimeSettings {
    playlist_delay_segments: Option<i32>,
    response_header_timeout: Option<u64>,
    max_idle_conns: Option<u32>,
}

async fn get_runtime_settings() -> Json<RuntimeSettings> {
    Json(RuntimeSettings {
        playlist_delay_segments: Some(3),
        response_header_timeout: Some(25),
        max_idle_conns: Some(128),
    })
}

async fn set_runtime_settings(
    Json(settings): Json<RuntimeSettings>,
) -> Json<serde_json::Value> {
    tracing::info!("Runtime settings updated: {:?}", settings);
    Json(serde_json::json!({"ok": true, "settings": settings}))
}

/// Filter state snapshot from Kotlin persistence. Uses camelCase to match kotlinx.serialization.
#[derive(serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncFilterState {
    pub disabled_genres: HashMap<String, bool>,
    pub disabled_channels: HashMap<String, bool>,
    pub enabled_channels: HashMap<String, bool>,
    pub rename_prefix: String,
    pub rename_suffix: String,
    pub genre_renames: HashMap<String, String>,
    pub version: u64,
}

/// Accept full filter state snapshot (sent by Kotlin on startup to restore persisted filters).
async fn sync_filters(
    State(st): State<AppState>,
    Json(snapshot): Json<HashMap<i32, SyncFilterState>>,
) -> Json<serde_json::Value> {
    let count = snapshot.len();
    {
        let mut filters = st.filters.write().await;
        filters.load_snapshot(snapshot);
        save_filters(&filters, &st.data_dir);
    }
    tracing::info!("Filters synced from snapshot ({} profiles)", count);
    Json(serde_json::json!({"ok": true, "synced": count}))
}
