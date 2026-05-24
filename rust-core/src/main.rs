use std::sync::Arc;
use tokio::sync::RwLock;
use tracing_subscriber::EnvFilter;

use stalkerhek_engine::api;
use stalkerhek_engine::filter;
use stalkerhek_engine::AppState;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let data_dir = std::path::Path::new("data");

    let state = AppState {
        profiles: Arc::new(RwLock::new(Vec::new())),
        filters: Arc::new(RwLock::new(filter::FilterStore::new())),
        runners: Arc::new(RwLock::new(Vec::new())),
        data_dir: data_dir.to_path_buf(),
    };

    // Load persisted filters from disk so they're available before Kotlin sync
    {
        let mut filters = state.filters.write().await;
        let filters_path = data_dir.join("filters.json");
        if let Ok(data) = std::fs::read_to_string(&filters_path) {
            if let Ok(snapshot) = serde_json::from_str::<std::collections::HashMap<i32, stalkerhek_engine::api::SyncFilterState>>(&data) {
                let count = snapshot.len();
                filters.load_snapshot(snapshot);
                tracing::info!("Loaded filters for {count} profile(s) from data/filters.json");
            }
        }
    }

    // Load persisted profiles from disk and populate the state
    let saved_profiles = api::load_profiles(data_dir);
    {
        let mut profiles = state.profiles.write().await;
        for p in saved_profiles {
            profiles.push(p);
        }
        tracing::info!("Loaded {} profile(s) from data/profiles.json", profiles.len());
    }

    // Load persisted favourites
    let favs = Arc::new(RwLock::new(api::load_favourites(data_dir)));

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

    // Use build_router_v2 to include favourites, search, EPG, and health endpoints
    let app = api::build_router_v2(state.clone(), favs);

    let bind_addr = std::env::var("STALKERHEK_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:9900".to_string());
    tracing::info!("Stalkerhek Engine starting on {bind_addr}");
    let listener = tokio::net::TcpListener::bind(&bind_addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
