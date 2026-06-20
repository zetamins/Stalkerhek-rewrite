#![cfg(feature = "android")]

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::collections::HashMap as Map;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::OnceLock;
use tokio::sync::RwLock;

use crate::api;
use crate::discover;
use crate::filter::FilterStore;
use crate::{AppState, ProfileConfig};

struct GlobalEngine {
    state: AppState,
    data_dir: PathBuf,
    runtime: tokio::runtime::Runtime,
}

static ENGINE: OnceLock<GlobalEngine> = OnceLock::new();

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).unwrap_or_else(|_| env.new_string("{}").unwrap()).into_raw()
}

fn get_engine() -> &'static GlobalEngine {
    ENGINE.get().expect("Engine not initialized")
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

// ─── nativeInit ────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeInit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data_dir: JString<'local>,
) -> jstring {
    let dir: String = jstring_to_string(&mut env, &data_dir);
    let data_path = PathBuf::from(&dir);

    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("Stalkerhek"),
    );

    let runtime = tokio::runtime::Runtime::new().unwrap();
    let state = runtime.block_on(async {
        let state = AppState {
            profiles: Arc::new(RwLock::new(Vec::new())),
            filters: Arc::new(RwLock::new(FilterStore::new())),
            runners: Arc::new(RwLock::new(Vec::new())),
            data_dir: data_path.clone(),
        };

        // Load persisted filters
        {
            let mut filters = state.filters.write().await;
            let filters_path = data_path.join("filters.json");
            if let Ok(data) = std::fs::read_to_string(&filters_path) {
                if let Ok(snapshot) = serde_json::from_str::<
                    std::collections::HashMap<i32, crate::api::SyncFilterState>,
                >(&data)
                {
                    filters.load_snapshot(snapshot);
                }
            }
        }

        // Load profiles
        let saved_profiles = api::load_profiles(&data_path);
        {
            let mut profiles = state.profiles.write().await;
            for p in saved_profiles {
                profiles.push(p);
            }
        }

        // Auto-start first profile
        let first_id = {
            let profiles = state.profiles.read().await;
            profiles.iter().map(|p| p.id).min()
        };
        if let Some(pid) = first_id {
            let _ = api::start_profile_by_id(&state, pid).await;
        }

        state
    });

    let profiles_loaded = runtime.block_on(async {
        state.profiles.read().await.len()
    });

    let ok = ENGINE.set(GlobalEngine {
        state,
        data_dir: data_path,
        runtime,
    });
    if ok.is_err() {
        return to_jstring(
            &mut env,
            &serde_json::json!({"ok": false, "error": "Engine already initialized"}).to_string(),
        );
    }

    let resp = serde_json::json!({"ok": true, "profiles_loaded": profiles_loaded});
    to_jstring(&mut env, &resp.to_string())
}

// ─── nativeShutdown ────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeShutdown<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    if let Some(engine) = ENGINE.get() {
        let _ = serde_json::json!({"ok": true});
        // Can't take ownership out of OnceLock, so we stop runners
        engine.runtime.block_on(async {
            let mut runners = engine.state.runners.write().await;
            for runner in runners.drain(..) {
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
        });
    }
    to_jstring(&mut env, r#"{"ok":true}"#)
}

// ─── nativeStartProfile ────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeStartProfile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_json: JString<'local>,
) -> jstring {
    let json_str: String = jstring_to_string(&mut env, &profile_json);
    let profile: ProfileConfig = match serde_json::from_str(&json_str) {
        Ok(p) => p,
        Err(e) => {
            return to_jstring(
                &mut env,
                &serde_json::json!({"ok": false, "error": format!("Invalid profile: {e}")})
                    .to_string(),
            );
        }
    };

    let engine = get_engine();

    // Write the profile into state first (create or update)
    engine.runtime.block_on(async {
        let mut profiles = engine.state.profiles.write().await;
        if let Some(pos) = profiles.iter().position(|p| p.id == profile.id) {
            profiles[pos] = profile.clone();
        } else {
            profiles.push(profile.clone());
        }
        api::save_profiles(&profiles, &engine.data_dir);
    });

    // Start the profile
    let result = engine
        .runtime
        .block_on(async { api::start_profile_by_id(&engine.state, profile.id).await });

    match result {
        Ok(_) => {
            // Return full profile status
            let status = engine.runtime.block_on(async {
                let runners = engine.state.runners.read().await;
                if let Some(runner) = runners.iter().find(|r| r.config.id == profile.id) {
                    Some(runner.status.read().await.clone())
                } else {
                    None
                }
            });
            let json = serde_json::to_string(&status.unwrap_or_default()).unwrap_or_default();
            to_jstring(&mut env, &json)
        }
        Err(status_code) => to_jstring(
            &mut env,
            &serde_json::json!({
                "ok": false,
                "phase": "error",
                "message": format!("Start failed: {}", status_code),
                "running": false
            })
            .to_string(),
        ),
    }
}

// ─── nativeStopProfile ─────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeStopProfile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
) -> jstring {
    let engine = get_engine();
    engine.runtime.block_on(async {
        let mut runners = engine.state.runners.write().await;
        if let Some(pos) = runners.iter().position(|r| r.config.id == profile_id) {
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
    });
    to_jstring(&mut env, r#"{"ok":true}"#)
}

// ─── nativeGetProfiles ─────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeGetProfiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let engine = get_engine();
    let json = engine.runtime.block_on(async {
        let profiles = engine.state.profiles.read().await;
        serde_json::to_string(&*profiles).unwrap_or_else(|_| "[]".to_string())
    });
    to_jstring(&mut env, &json)
}

// ─── nativeGetProfileStatus ────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeGetProfileStatus<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
) -> jstring {
    let engine = get_engine();
    let json = engine.runtime.block_on(async {
        let runners = engine.state.runners.read().await;
        if let Some(runner) = runners.iter().find(|r| r.config.id == profile_id) {
            let status = runner.status.read().await;
            serde_json::to_string(&*status).unwrap_or_default()
        } else {
            // Profile not running — include discovery_done from config so dashboard can show spinner
            let profiles = engine.state.profiles.read().await;
            let discovery_done = profiles
                .iter()
                .find(|p| p.id == profile_id)
                .map(|p| p.discovery_done)
                .unwrap_or(true); // unknown profile → assume done
            serde_json::json!({
                "id": profile_id,
                "phase": "idle",
                "message": "Not running",
                "running": false,
                "discovery_done": discovery_done
            })
            .to_string()
        }
    });
    to_jstring(&mut env, &json)
}

// ─── nativeGetChannels ─────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeGetChannels<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
    media_type: JString<'local>,
) -> jstring {
    let type_str: String = jstring_to_string(&mut env, &media_type);
    let engine = get_engine();
    let json = engine.runtime.block_on(async {
        let runners = engine.state.runners.read().await;
        if let Some(runner) = runners.iter().find(|r| r.config.id == profile_id) {
            let channels = match type_str.as_str() {
                "vod" => runner.vod_channels.read().await.clone(),
                "series" => runner.series_channels.read().await.clone(),
                _ => runner.channels.read().await.clone(),
            };
            let filters = engine.state.filters.read().await;
            let disabled_channels = filters.disabled_channels.get(&profile_id).cloned().unwrap_or_default();
            let disabled_genres = filters.disabled_genres.get(&profile_id).cloned().unwrap_or_default();
            let enabled_channels = filters.enabled_channels.get(&profile_id).cloned().unwrap_or_default();
            let items: Vec<serde_json::Value> = channels
                .unwrap_or_default()
                .into_iter()
                .map(|ch| {
                    let is_disabled = disabled_channels.contains(&ch.cmd)
                        || (disabled_genres.contains(&ch.genre_id)
                            && !enabled_channels.contains(&ch.cmd));
                    // Apply rename so the title returned to the UI matches the
                    // HLS server's URL path (which is built from the renamed title).
                    let display_title = filters.apply_rename(profile_id, &ch.title);
                    let display_genre = filters.apply_genre_rename(profile_id, &ch.genre_id, &ch.genre);
                    serde_json::json!({
                        "cmd": ch.cmd,
                        "title": display_title,
                        "genre": display_genre,
                        "genreId": ch.genre_id,
                        "logo": ch.logo,
                        "enabled": !is_disabled,
                    })
                })
                .collect();
            serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string())
        } else {
            "[]".to_string()
        }
    });
    to_jstring(&mut env, &json)
}

// ─── nativeGetCategories ───────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeGetCategories<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
    media_type: JString<'local>,
) -> jstring {
    let type_str: String = jstring_to_string(&mut env, &media_type);
    let engine = get_engine();
    let json = engine.runtime.block_on(async {
        let runners = engine.state.runners.read().await;
        if let Some(runner) = runners.iter().find(|r| r.config.id == profile_id) {
            let cats = match type_str.as_str() {
                "series" => runner.series_categories.read().await.clone(),
                _ => runner.vod_categories.read().await.clone(),
            };
            let filtered: Vec<serde_json::Value> = cats
                .into_iter()
                .filter(|item| {
                    item["id"]
                        .as_str()
                        .map_or(true, |id| id != "*")
                })
                .map(|item| {
                    serde_json::json!({
                        "id": item["id"].as_str().unwrap_or(""),
                        "title": item["title"].as_str().unwrap_or(""),
                        "name": item["title"].as_str().unwrap_or(""),
                    })
                })
                .collect();
            serde_json::to_string(&filtered).unwrap_or_else(|_| "[]".to_string())
        } else {
            "[]".to_string()
        }
    });
    to_jstring(&mut env, &json)
}

// ─── nativeCreateProfile ───────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeCreateProfile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_json: JString<'local>,
) -> jstring {
    let json_str: String = jstring_to_string(&mut env, &profile_json);
    let profile: ProfileConfig = match serde_json::from_str(&json_str) {
        Ok(p) => p,
        Err(e) => {
            return to_jstring(
                &mut env,
                &serde_json::json!({"ok": false, "error": format!("Invalid profile: {e}")})
                    .to_string(),
            );
        }
    };

    let engine = get_engine();
    let (profiles_ref, runners_ref, data_dir, profile_id, portal_url, mac, has_fallbacks) =
        engine.runtime.block_on(async {
            let mut profiles = engine.state.profiles.write().await;
            // Upsert
            if let Some(pos) = profiles.iter().position(|p| p.id == profile.id) {
                profiles[pos] = profile.clone();
            } else {
                profiles.push(profile.clone());
            }
            api::save_profiles(&profiles, &engine.data_dir);
            (
                engine.state.profiles.clone(),
                engine.state.runners.clone(),
                engine.data_dir.clone(),
                profile.id,
                profile.portal_url.clone(),
                profile.mac.clone(),
                profile.fallback_portals.is_empty(),
            )
        });

    // Spawn background discovery (same as HTTP create_profile path)
    if has_fallbacks {
        engine.runtime.spawn(async move {
            let discover = crate::discover::discover_portals_with_mac(&portal_url, &mac).await;
            if discover.discovered {
                tracing::info!(
                    "[discover JNI] profile {}: found {} portals, best: {}",
                    profile_id,
                    discover.all_portals.len(),
                    discover.best_portal
                );
                let mut profiles = profiles_ref.write().await;
                if let Some(p) = profiles.iter_mut().find(|p| p.id == profile_id) {
                    p.fallback_portals = discover.all_portals;
                    p.portal_url = discover.best_portal;
                    p.discovery_done = true;
                    api::save_profiles(&profiles, &data_dir);
                }
                let runners = runners_ref.read().await;
                if let Some(r) = runners.iter().find(|r| r.config.id == profile_id) {
                    let mut status = r.status.write().await;
                    status.discovery_done = true;
                }
            } else {
                let mut profiles = profiles_ref.write().await;
                if let Some(p) = profiles.iter_mut().find(|p| p.id == profile_id) {
                    p.discovery_done = true;
                    api::save_profiles(&profiles, &data_dir);
                }
                let runners = runners_ref.read().await;
                if let Some(r) = runners.iter().find(|r| r.config.id == profile_id) {
                    let mut status = r.status.write().await;
                    status.discovery_done = true;
                }
            }
        });
    }

    to_jstring(&mut env, &serde_json::to_string(&profile).unwrap_or_default())
}

// ─── nativeDeleteProfile ───────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeDeleteProfile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
) -> jstring {
    let engine = get_engine();
    engine.runtime.block_on(async {
        // Stop if running
        {
            let mut runners = engine.state.runners.write().await;
            if let Some(pos) = runners.iter().position(|r| r.config.id == profile_id) {
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
        // Remove from profiles
        let mut profiles = engine.state.profiles.write().await;
        if let Some(pos) = profiles.iter().position(|p| p.id == profile_id) {
            profiles.remove(pos);
        }
        api::save_profiles(&profiles, &engine.data_dir);
    });
    to_jstring(&mut env, r#"{"ok":true}"#)
}

// ─── nativeFilterUpdate ────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeFilterUpdate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    action_json: JString<'local>,
) -> jstring {
    let json_str: String = jstring_to_string(&mut env, &action_json);
    let req: Map<String, String> = match serde_json::from_str(&json_str) {
        Ok(m) => m,
        Err(_) => {
            return to_jstring(&mut env, r#"{"ok":false,"error":"Invalid JSON"}"#);
        }
    };

    let engine = get_engine();
    engine.runtime.block_on(async {
        let mut filters = engine.state.filters.write().await;
        apply_filter_action_mut(&mut filters, &req);
        api::save_filters(&filters, &engine.data_dir);
    });

    to_jstring(&mut env, r#"{"ok":true}"#)
}

fn apply_filter_action_mut(filters: &mut FilterStore, req: &Map<String, String>) {
    let profile_id = req
        .get("profile_id")
        .and_then(|s| s.parse::<i32>().ok())
        .unwrap_or(0);
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
        _ => {}
    }
}

// ─── nativeSyncFilters ─────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeSyncFilters<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    snapshot_json: JString<'local>,
) -> jstring {
    let json_str: String = jstring_to_string(&mut env, &snapshot_json);
    let snapshot: std::collections::HashMap<i32, crate::api::SyncFilterState> =
        match serde_json::from_str(&json_str) {
            Ok(s) => s,
            Err(_) => {
                return to_jstring(&mut env, r#"{"ok":false,"error":"Invalid snapshot"}"#);
            }
        };

    let engine = get_engine();
    let count = snapshot.len();
    engine.runtime.block_on(async {
        let mut filters = engine.state.filters.write().await;
        filters.load_snapshot(snapshot);
        api::save_filters(&filters, &engine.data_dir);
    });

    let resp = serde_json::json!({"ok": true, "synced": count});
    to_jstring(&mut env, &resp.to_string())
}

// ─── nativeGetFilterState (debug) ─────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_streamhek_tv_engine_RustEngineBridge_nativeGetFilterState<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile_id: jint,
) -> jstring {
    let engine = get_engine();
    let json = engine.runtime.block_on(async {
        let filters = engine.state.filters.read().await;
        let dg = filters.disabled_genres.get(&profile_id).cloned().unwrap_or_default();
        let dc = filters.disabled_channels.get(&profile_id).cloned().unwrap_or_default();
        let ec = filters.enabled_channels.get(&profile_id).cloned().unwrap_or_default();
        serde_json::json!({
            "disabled_genres": dg.iter().collect::<Vec<_>>(),
            "disabled_channels": dc.iter().collect::<Vec<_>>(),
            "enabled_channels": ec.iter().collect::<Vec<_>>(),
        }).to_string()
    });
    to_jstring(&mut env, &json)
}
