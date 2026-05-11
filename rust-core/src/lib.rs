pub mod stalker;
pub mod hls;
pub mod proxy;
pub mod filter;
pub mod api;
pub mod mag;
pub mod dns;

#[cfg(feature = "android")]
pub mod jni_bridge;

use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Clone)]
pub struct AppState {
    pub profiles: Arc<RwLock<Vec<ProfileConfig>>>,
    pub filters: Arc<RwLock<filter::FilterStore>>,
    pub runners: Arc<RwLock<Vec<ProfileRunner>>>,
    pub data_dir: PathBuf,
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
