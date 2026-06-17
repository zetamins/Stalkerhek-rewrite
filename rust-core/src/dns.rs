use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

struct DnsEntry {
    ips: Vec<IpAddr>,
    expires: Instant,
}

struct TzEntry {
    tz: String,
    expires: Instant,
}

/// Generate a random European IP and its matching timezone from major residential blocks.
pub fn get_random_european_identity() -> (String, String) {
    let data = [
        ("85.214", "Europe/Berlin"),   // Germany
        ("92.184", "Europe/Paris"),    // France
        ("81.130", "Europe/London"),   // UK
        ("87.213", "Europe/Rome"),     // Italy
        ("213.205", "Europe/London"),  // O2 UK
    ];
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let (block, tz) = data[rng.gen_range(0..data.len())];
    let ip = format!("{}.{}.{}", block, rng.gen_range(1..254), rng.gen_range(1..254));
    (ip, tz.to_string())
}

/// Generate a random European IP for header spoofing.
pub fn get_random_european_ip() -> String {
    get_random_european_identity().0
}

static DNS_CACHE: std::sync::LazyLock<Mutex<HashMap<String, DnsEntry>>> = std::sync::LazyLock::new(|| {
    Mutex::new(HashMap::new())
});

static TZ_CACHE: std::sync::LazyLock<Mutex<HashMap<String, TzEntry>>> = std::sync::LazyLock::new(|| {
    Mutex::new(HashMap::new())
});

static HTTP_CLIENT: std::sync::LazyLock<reqwest::Client> = std::sync::LazyLock::new(|| {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .expect("Failed to build DNS HTTP client")
});

/// Resolve a hostname via Google DoH with an EDNS Client Subnet hint
/// pointing to a European IP range. This causes the authoritative DNS
/// to return European CDN/Cloudflare edge IPs.
pub async fn resolve_european(hostname: &str) -> Vec<IpAddr> {
    {
        let cache = DNS_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = cache.get(hostname) {
            if entry.expires > Instant::now() {
                return entry.ips.clone();
            }
        }
    }

    // Dynamic ECS Rotation: Pick a random European residential subnet
    let data = [
        ("85.214", "Europe/Berlin"),   // Germany (Strato)
        ("92.184", "Europe/Paris"),    // France (Orange)
        ("81.130", "Europe/London"),   // UK (BT)
        ("87.213", "Europe/Rome"),     // Italy (Telecom Italia)
        ("213.205", "Europe/London"),  // UK (O2)
        ("80.28", "Europe/Madrid"),    // Spain (Telefonica)
        ("82.161", "Europe/Amsterdam"),// Netherlands (KPN)
    ];
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let (block, _tz) = data[rng.gen_range(0..data.len())];
    let ecs = format!("{}.{}.{}", block, rng.gen_range(1..254), rng.gen_range(1..254));

    let url = format!(
        "https://dns.google/resolve?name={}&type=A&edns_client_subnet={}/24",
        hostname, ecs
    );

    let ips = match resolve_doh(&url).await {
        Ok(ips) => ips,
        Err(e) => {
            tracing::warn!("[DNS] European resolution failed for {hostname}: {e}");
            let fallback = format!("https://cloudflare-dns.com/dns-query?name={}&type=A", hostname);
            resolve_doh(&fallback).await.unwrap_or_default()
        }
    };

    if !ips.is_empty() {
        let mut cache = DNS_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        cache.insert(hostname.to_string(), DnsEntry {
            ips: ips.clone(),
            expires: Instant::now() + Duration::from_secs(300),
        });
    }

    ips
}

async fn resolve_doh(url: &str) -> Result<Vec<IpAddr>, Box<dyn std::error::Error + Send + Sync>> {
    let resp = HTTP_CLIENT.get(url)
        .header("Accept", "application/dns-json")
        .send()
        .await?;
    let data: serde_json::Value = resp.json().await?;
    let ips = data["Answer"].as_array()
        .map(|answers| {
            answers.iter()
                .filter_map(|a| a["data"].as_str())
                .filter_map(|s| s.parse::<IpAddr>().ok())
                .collect()
        })
        .unwrap_or_default();
    Ok(ips)
}

/// Look up the European timezone for a portal hostname using ip-api.com
/// with a fixed Deutsche Telekom IP (avoids Cloudflare Anycast skew).
/// Cached for 1 hour keyed by hostname.
pub async fn get_european_timezone(hostname: &str) -> String {
    // Check cache first
    {
        let cache = TZ_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = cache.get(hostname) {
            if entry.expires > Instant::now() {
                return entry.tz.clone();
            }
        }
    }

    // Don't use the resolved IP for geolocation — Cloudflare Anycast IPs
    // geolocate to their datacenter (e.g. Toronto) rather than the origin.
    // Instead, geolocate a known European IP from the ECS subnet (Deutsche Telekom).
    let url = "http://ip-api.com/json/85.214.0.1?fields=timezone".to_string();
    let tz = match HTTP_CLIENT.get(&url).send().await {
        Ok(resp) => {
            if let Ok(data) = resp.json::<serde_json::Value>().await {
                data["timezone"].as_str().unwrap_or("Europe/London").to_string()
            } else {
                "Europe/London".to_string()
            }
        }
        Err(_) => "Europe/London".to_string(),
    };

    {
        let mut cache = TZ_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        cache.insert(hostname.to_string(), TzEntry {
            tz: tz.clone(),
            expires: Instant::now() + Duration::from_secs(3600),
        });
    }

    tz
}
