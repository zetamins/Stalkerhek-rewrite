use std::collections::{HashMap, HashSet};
use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterStore {
    pub disabled_genres: HashMap<i32, HashSet<String>>,
    pub disabled_channels: HashMap<i32, HashSet<String>>,
    pub enabled_channels: HashMap<i32, HashSet<String>>,
    pub rename_prefix: HashMap<i32, String>,
    pub rename_suffix: HashMap<i32, String>,
    pub genre_renames: HashMap<i32, HashMap<String, String>>,
    pub versions: HashMap<i32, u64>,
}

impl FilterStore {
    pub fn new() -> Self {
        Self {
            disabled_genres: HashMap::new(),
            disabled_channels: HashMap::new(),
            enabled_channels: HashMap::new(),
            rename_prefix: HashMap::new(),
            rename_suffix: HashMap::new(),
            genre_renames: HashMap::new(),
            versions: HashMap::new(),
        }
    }

    pub fn apply_rename(&self, profile_id: i32, title: &str) -> String {
        let mut t = title.to_string();
        if let Some(prefix) = self.rename_prefix.get(&profile_id) {
            if !prefix.is_empty() && t.starts_with(prefix) {
                t = t[prefix.len()..].to_string();
            }
        }
        if let Some(suffix) = self.rename_suffix.get(&profile_id) {
            if !suffix.is_empty() && t.ends_with(suffix) {
                t = t[..t.len() - suffix.len()].to_string();
            }
        }
        t.trim().to_string()
    }

    /// Apply genre rename for a given genre_id, falling back to original name.
    pub fn apply_genre_rename(&self, profile_id: i32, genre_id: &str, original_name: &str) -> String {
        self.genre_renames.get(&profile_id)
            .and_then(|m| m.get(genre_id))
            .filter(|n| !n.is_empty())
            .cloned()
            .unwrap_or_else(|| original_name.to_string())
    }

    pub fn is_genre_disabled(&self, profile_id: i32, genre_id: &str) -> bool {
        self.disabled_genres.get(&profile_id)
            .map(|s| s.contains(genre_id))
            .unwrap_or(false)
    }

    pub fn is_channel_allowed(&self, profile_id: i32, cmd: &str, genre_id: &str) -> bool {
        let cmd_blocked = self.disabled_channels.get(&profile_id)
            .map(|s| s.contains(cmd))
            .unwrap_or(false);
        if cmd_blocked {
            return false;
        }
        let genre_blocked = if !genre_id.is_empty() {
            self.disabled_genres.get(&profile_id)
                .map(|s| s.contains(genre_id))
                .unwrap_or(false)
        } else {
            false
        };
        if genre_blocked {
            return self.enabled_channels.get(&profile_id)
                .map(|s| s.contains(cmd))
                .unwrap_or(false);
        }
        true
    }

    /// Returns true if this profile has any non-empty filter rules configured.
    pub fn has_filters(&self, profile_id: i32) -> bool {
        self.disabled_genres.get(&profile_id).map_or(false, |s| !s.is_empty())
            || self.disabled_channels.get(&profile_id).map_or(false, |s| !s.is_empty())
            || self.enabled_channels.get(&profile_id).map_or(false, |s| !s.is_empty())
            || self.rename_prefix.get(&profile_id).map_or(false, |s| !s.is_empty())
            || self.rename_suffix.get(&profile_id).map_or(false, |s| !s.is_empty())
            || self.genre_renames.get(&profile_id).map_or(false, |m| !m.is_empty())
    }

    pub fn set_genre_disabled(&mut self, profile_id: i32, genre_id: String, disabled: bool) {
        let entry = self.disabled_genres.entry(profile_id).or_default();
        if disabled { entry.insert(genre_id); }
        else { entry.remove(&genre_id); }
        *self.versions.entry(profile_id).or_insert(0) += 1;
    }

    pub fn set_channel_disabled(&mut self, profile_id: i32, cmd: String, disabled: bool) {
        let blocked = self.disabled_channels.entry(profile_id).or_default();
        let enabled = self.enabled_channels.entry(profile_id).or_default();
        if disabled {
            blocked.insert(cmd.clone());
            enabled.remove(&cmd);
        } else {
            blocked.remove(&cmd);
            enabled.insert(cmd);
        }
        *self.versions.entry(profile_id).or_insert(0) += 1;
    }

    pub fn reset_profile(&mut self, profile_id: i32) {
        self.disabled_genres.remove(&profile_id);
        self.disabled_channels.remove(&profile_id);
        self.enabled_channels.remove(&profile_id);
        self.genre_renames.remove(&profile_id);
        *self.versions.entry(profile_id).or_insert(0) += 1;
    }

    pub fn set_rename(&mut self, profile_id: i32, prefix: String, suffix: String) {
        if prefix.is_empty() { self.rename_prefix.remove(&profile_id); }
        else { self.rename_prefix.insert(profile_id, prefix); }
        if suffix.is_empty() { self.rename_suffix.remove(&profile_id); }
        else { self.rename_suffix.insert(profile_id, suffix); }
        *self.versions.entry(profile_id).or_insert(0) += 1;
    }

    /// Replace the entire filter store with a snapshot from persistence (used at startup).
    pub fn load_snapshot(&mut self, snapshot: std::collections::HashMap<i32, crate::api::SyncFilterState>) {
        self.disabled_genres.clear();
        self.disabled_channels.clear();
        self.enabled_channels.clear();
        self.rename_prefix.clear();
        self.rename_suffix.clear();
        self.genre_renames.clear();
        self.versions.clear();
        for (profile_id, state) in snapshot {
            let dg: HashSet<String> = state.disabled_genres.into_iter()
                .filter(|(_, v)| *v).map(|(k, _)| k).collect();
            if !dg.is_empty() { self.disabled_genres.insert(profile_id, dg); }

            let dc: HashSet<String> = state.disabled_channels.into_iter()
                .filter(|(_, v)| *v).map(|(k, _)| k).collect();
            if !dc.is_empty() { self.disabled_channels.insert(profile_id, dc); }

            let ec: HashSet<String> = state.enabled_channels.into_iter()
                .filter(|(_, v)| *v).map(|(k, _)| k).collect();
            if !ec.is_empty() { self.enabled_channels.insert(profile_id, ec); }

            if !state.rename_prefix.is_empty() {
                self.rename_prefix.insert(profile_id, state.rename_prefix);
            }
            if !state.rename_suffix.is_empty() {
                self.rename_suffix.insert(profile_id, state.rename_suffix);
            }
            let gr: HashMap<String, String> = state.genre_renames.into_iter()
                .filter(|(_, v)| !v.is_empty()).collect();
            if !gr.is_empty() { self.genre_renames.insert(profile_id, gr); }

            self.versions.insert(profile_id, state.version);
        }
    }

    pub fn set_genre_rename(&mut self, profile_id: i32, genre_id: String, new_name: String) {
        if new_name.is_empty() {
            if let Some(map) = self.genre_renames.get_mut(&profile_id) {
                map.remove(&genre_id);
                if map.is_empty() { self.genre_renames.remove(&profile_id); }
            }
        } else {
            self.genre_renames.entry(profile_id).or_default().insert(genre_id, new_name);
        }
        *self.versions.entry(profile_id).or_insert(0) += 1;
    }
}
