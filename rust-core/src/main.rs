mod stalker;
mod hls;
mod proxy;
mod filter;
mod api;
mod mag;

use std::sync::Arc;
use tokio::sync::RwLock;
use tracing_subscriber::EnvFilter;

#[derive(Clone)]
pub struct AppState {
    pub profiles: Arc<RwLock<Vec<ProfileConfig>>>,
    pub filters: Arc<RwLock<filter::FilterStore>>,
    pub runners: Arc<RwLock<Vec<ProfileRunner>>>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ProfileConfig {
    pub id: i32,
    pub name: String,
    pub portal_url: String,
    pub mac: String,
    pub username: String,
    pub password: String,
    pub hls_port: u16,
    pub proxy_port: u16,
    pub timezone: String,
    pub serial_number: String,
    pub device_id: String,
    pub device_id2: String,
    pub signature: String,
    pub model: String,
    pub watchdog_interval: u32,
    pub device_id_auth: bool,
    pub hls_enabled: bool,
    pub proxy_enabled: bool,
    pub proxy_rewrite: bool,
}

impl Default for ProfileConfig {
    fn default() -> Self {
        Self {
            id: 0,
            name: String::new(),
            portal_url: String::new(),
            mac: String::new(),
            username: String::new(),
            password: String::new(),
            hls_port: 4600,
            proxy_port: 4800,
            timezone: "UTC".to_string(),
            serial_number: "0000000000000".to_string(),
            device_id: "f".repeat(64),
            device_id2: "f".repeat(64),
            signature: "f".repeat(64),
            model: "MAG254".to_string(),
            watchdog_interval: 5,
            device_id_auth: true,
            hls_enabled: true,
            proxy_enabled: true,
            proxy_rewrite: true,
        }
    }
}

pub struct ProfileRunner {
    pub config: ProfileConfig,
    pub cancel_hls: Option<tokio::sync::oneshot::Sender<()>>,
    pub cancel_proxy: Option<tokio::sync::oneshot::Sender<()>>,
    pub channels: Arc<RwLock<Option<Vec<stalker::Channel>>>>,
    pub vod_channels: Arc<RwLock<Option<Vec<stalker::Channel>>>>,
    pub series_channels: Arc<RwLock<Option<Vec<stalker::Channel>>>>,
    pub portal_token: Arc<RwLock<String>>,
    pub status: Arc<RwLock<ProfileStatus>>,
    pub vod_categories: Arc<RwLock<Vec<serde_json::Value>>>,
    pub series_categories: Arc<RwLock<Vec<serde_json::Value>>>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ProfileStatus {
    pub phase: String,
    pub message: String,
    pub channels_count: usize,
    pub hls_addr: String,
    pub proxy_addr: String,
    pub running: bool,
}

impl Default for ProfileStatus {
    fn default() -> Self {
        Self {
            phase: "idle".to_string(),
            message: "Not started".to_string(),
            channels_count: 0,
            hls_addr: String::new(),
            proxy_addr: String::new(),
            running: false,
        }
    }
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let state = AppState {
        profiles: Arc::new(RwLock::new(Vec::new())),
        filters: Arc::new(RwLock::new(filter::FilterStore::new())),
        runners: Arc::new(RwLock::new(Vec::new())),
    };

    // Load persisted filters from disk so they're available before Kotlin sync
    {
        let mut filters = state.filters.write().await;
        if let Ok(data) = std::fs::read_to_string("data/filters.json") {
            if let Ok(snapshot) = serde_json::from_str::<std::collections::HashMap<i32, crate::api::SyncFilterState>>(&data) {
                let count = snapshot.len();
                filters.load_snapshot(snapshot);
                tracing::info!("Loaded filters for {count} profile(s) from data/filters.json");
            }
        }
    }

    // Load persisted profiles from disk and populate the state
    let saved_profiles = api::load_profiles();
    {
        let mut profiles = state.profiles.write().await;
        for p in saved_profiles {
            profiles.push(p);
        }
        tracing::info!("Loaded {} profile(s) from data/profiles.json", profiles.len());
    }

    // Auto-start the first profile (lowest ID)
    let first_id = {
        let profiles = state.profiles.read().await;
        profiles.iter().map(|p| p.id).min()
    };
    if let Some(pid) = first_id {
        tracing::info!("Auto-starting profile {pid}");
        match api::start_profile_by_id(&state, pid).await {
            Ok(v) => tracing::info!("Profile {pid} started: {v}"),
            Err(e) => tracing::error!("Failed to auto-start profile {pid}: {e:?}"),
        }
    }

    let app = api::build_router(state.clone());

    let bind_addr = std::env::var("STALKERHEK_BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:9900".to_string());
    tracing::info!("Stalkerhek Engine starting on {bind_addr}");
    let listener = tokio::net::TcpListener::bind(&bind_addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
