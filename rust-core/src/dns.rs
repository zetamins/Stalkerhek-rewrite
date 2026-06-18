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

/// European residential identity block with country metadata.
pub(crate) struct IdentityEntry {
    pub(crate) ip: String,
    pub(crate) tz: String,
    pub(crate) expires: Instant,
    pub(crate) accept_lang: String,
    pub(crate) country_code: String,
}

/// European residential ISP blocks used for EDNS Client Subnet and IP spoofing.
/// 22 blocks across 10 countries -- each with verified ISP and correct timezone.
struct EuBlock {
    prefix: &'static str,
    tz: &'static str,
    country: &'static str,
    lang: &'static str,
    #[allow(dead_code)]
    isp: &'static str,
}

fn european_blocks() -> &'static [EuBlock] {
    &[
        // Germany
        EuBlock { prefix: "85.214", tz: "Europe/Berlin", country: "DE", lang: "de-DE,de;q=0.9,en;q=0.8", isp: "Strato" },
        EuBlock { prefix: "88.130", tz: "Europe/Berlin", country: "DE", lang: "de-DE,de;q=0.9,en;q=0.8", isp: "Deutsche Telekom" },
        EuBlock { prefix: "91.66",  tz: "Europe/Berlin", country: "DE", lang: "de-DE,de;q=0.9,en;q=0.8", isp: "Vodafone DE" },
        EuBlock { prefix: "79.193", tz: "Europe/Berlin", country: "DE", lang: "de-DE,de;q=0.9,en;q=0.8", isp: "1&1" },
        // France
        EuBlock { prefix: "92.184", tz: "Europe/Paris",  country: "FR", lang: "fr-FR,fr;q=0.9,en;q=0.8", isp: "Orange" },
        EuBlock { prefix: "82.127", tz: "Europe/Paris",  country: "FR", lang: "fr-FR,fr;q=0.9,en;q=0.8", isp: "SFR" },
        EuBlock { prefix: "78.112", tz: "Europe/Paris",  country: "FR", lang: "fr-FR,fr;q=0.9,en;q=0.8", isp: "Free" },
        // United Kingdom
        EuBlock { prefix: "81.130",  tz: "Europe/London", country: "GB", lang: "en-GB,en;q=0.9", isp: "BT" },
        EuBlock { prefix: "213.205", tz: "Europe/London", country: "GB", lang: "en-GB,en;q=0.9", isp: "O2" },
        EuBlock { prefix: "86.147",  tz: "Europe/London", country: "GB", lang: "en-GB,en;q=0.9", isp: "Sky Broadband" },
        // Italy
        EuBlock { prefix: "87.213",  tz: "Europe/Rome",   country: "IT", lang: "it-IT,it;q=0.9,en;q=0.8", isp: "Telecom Italia" },
        EuBlock { prefix: "151.44",  tz: "Europe/Rome",   country: "IT", lang: "it-IT,it;q=0.9,en;q=0.8", isp: "Wind Tre" },
        // Spain
        EuBlock { prefix: "80.28",   tz: "Europe/Madrid", country: "ES", lang: "es-ES,es;q=0.9,en;q=0.8", isp: "Telefonica" },
        EuBlock { prefix: "83.44",   tz: "Europe/Madrid", country: "ES", lang: "es-ES,es;q=0.9,en;q=0.8", isp: "Movistar" },
        // Netherlands
        EuBlock { prefix: "82.161",  tz: "Europe/Amsterdam", country: "NL", lang: "nl-NL,nl;q=0.9,en;q=0.8", isp: "KPN" },
        EuBlock { prefix: "77.167",  tz: "Europe/Amsterdam", country: "NL", lang: "nl-NL,nl;q=0.9,en;q=0.8", isp: "Ziggo" },
        // Poland
        EuBlock { prefix: "83.8",    tz: "Europe/Warsaw", country: "PL", lang: "pl-PL,pl;q=0.9,en;q=0.8", isp: "Orange Polska" },
        EuBlock { prefix: "79.185",  tz: "Europe/Warsaw", country: "PL", lang: "pl-PL,pl;q=0.9,en;q=0.8", isp: "Neostrada" },
        // Portugal
        EuBlock { prefix: "85.243",  tz: "Europe/Lisbon", country: "PT", lang: "pt-PT,pt;q=0.9,en;q=0.8", isp: "MEO" },
        // Sweden
        EuBlock { prefix: "90.224",  tz: "Europe/Stockholm", country: "SE", lang: "sv-SE,sv;q=0.9,en;q=0.8", isp: "Telia" },
        // Belgium
        EuBlock { prefix: "81.240",  tz: "Europe/Brussels", country: "BE", lang: "nl-BE,nl;q=0.9,fr;q=0.8,en;q=0.7", isp: "Proximus" },
        // Austria
        EuBlock { prefix: "91.115",  tz: "Europe/Vienna", country: "AT", lang: "de-AT,de;q=0.9,en;q=0.8", isp: "A1 Telekom" },
    ]
}

pub(crate) static IDENTITY_CACHE: std::sync::LazyLock<Mutex<HashMap<String, IdentityEntry>>> = std::sync::LazyLock::new(|| {
    Mutex::new(HashMap::new())
});

/// Generate or retrieve a "Sticky" European identity for a host.
/// Locks the identity (IP, timezone, language, country) for 4 hours.
pub fn get_sticky_european_identity(hostname: &str) -> (String, String) {
    let entry = get_or_create_identity(hostname);
    (entry.ip, entry.tz)
}

/// Full European identity -- returns IP, timezone, Accept-Language, and country code.
fn get_or_create_identity(hostname: &str) -> IdentityEntry {
    let mut cache = IDENTITY_CACHE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(entry) = cache.get(hostname) {
        if entry.expires > Instant::now() {
            let ip = entry.ip.clone();
            let tz = entry.tz.clone();
            return IdentityEntry {
                ip, tz,
                expires: entry.expires,
                accept_lang: entry.accept_lang.clone(),
                country_code: entry.country_code.clone(),
            };
        }
    }
    // Generate and cache a new identity
    use rand::Rng;
    let block = random_eu_block();
    let ip = format!("{}.{}.{}", block.prefix, rand::thread_rng().gen_range(1..254), rand::thread_rng().gen_range(1..254));
    let entry = IdentityEntry {
        ip: ip.clone(),
        tz: block.tz.to_string(),
        expires: Instant::now() + Duration::from_secs(14400), // 4 hours
        accept_lang: block.lang.to_string(),
        country_code: block.country.to_string(),
    };
    cache.insert(hostname.to_string(), IdentityEntry {
        ip: entry.ip.clone(),
        tz: entry.tz.clone(),
        expires: entry.expires,
        accept_lang: entry.accept_lang.clone(),
        country_code: entry.country_code.clone(),
    });
    entry
}

/// Get the Accept-Language header value for the current sticky identity of a host.
pub fn get_sticky_accept_language(hostname: &str) -> String {
    get_or_create_identity(hostname).accept_lang
}

/// Get the country code (ISO 3166-1 alpha-2) for the current sticky identity.
pub fn get_sticky_country_code(hostname: &str) -> String {
    get_or_create_identity(hostname).country_code
}

/// Generate a random European IP and its matching timezone from major residential blocks.
pub fn get_random_european_identity() -> (String, String) {
    use rand::Rng;
    let block = random_eu_block();
    let ip = format!("{}.{}.{}", block.prefix, rand::thread_rng().gen_range(1..254), rand::thread_rng().gen_range(1..254));
    (ip, block.tz.to_string())
}

/// Generate a random European IP for header spoofing.
pub fn get_random_european_ip() -> String {
    get_random_european_identity().0
}

fn random_eu_block() -> &'static EuBlock {
    use rand::Rng;
    let blocks = european_blocks();
    &blocks[rand::thread_rng().gen_range(0..blocks.len())]
}

/// Remove the sticky identity entry for a host, forcing regeneration.
pub(crate) fn clear_sticky_identity(hostname: &str) {
    let mut cache = IDENTITY_CACHE.lock().unwrap_or_else(|e| e.into_inner());
    cache.remove(hostname);
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
        .local_address(Some(std::net::Ipv4Addr::UNSPECIFIED.into()))
        .build()
        .expect("Failed to build DNS HTTP client")
});

/// Resolve a hostname via a pool of DoH providers with EDNS Client Subnet hints
/// pointing to European IP ranges. This causes authoritative DNS servers to return
/// European CDN/Cloudflare edge IPs, bypassing geo-DNS.
///
/// This is how the engine avoids VPNs: it doesn't tunnel traffic -- it tricks DNS into
/// giving European IPs, then connects directly to those European CDN edges. The TCP
/// source IP is still the real one, but the connection terminates at a European edge
/// node that sees European HTTP headers and passes the traffic through.
pub async fn resolve_european(hostname: &str) -> Vec<IpAddr> {
    {
        let cache = DNS_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = cache.get(hostname) {
            if entry.expires > Instant::now() {
                return entry.ips.clone();
            }
        }
    }

    // Pick a random European residential block for ECS
    let block = random_eu_block();
    let ecs = {
        use rand::Rng;
        format!("{}.{}.{}", block.prefix, rand::thread_rng().gen_range(1..254), rand::thread_rng().gen_range(1..254))
    };

    // Multi-DoH provider pool -- only providers verified working with ECS from all regions.
    // Google DNS returns the most answers (best ECS support), Cloudflare + NextDNS as rotation.
    let providers: &[(&str, &str)] = &[
        ("https://dns.google/resolve",           "application/dns-json"),  // Google -- excellent ECS support
        ("https://cloudflare-dns.com/dns-query", "application/dns-json"),  // Cloudflare
        ("https://dns.nextdns.io/dns-query",     "application/dns-json"),  // NextDNS
    ];
    let (provider_url, accept_type) = {
        use rand::Rng;
        providers[rand::thread_rng().gen_range(0..providers.len())]
    };

    let url = format!(
        "{}?name={}&type=A&edns_client_subnet={}/24",
        provider_url, hostname, ecs
    );

    let ips = match resolve_doh(&url, accept_type).await {
        Ok(ips) if !ips.is_empty() => ips,
        Ok(_) | Err(_) => {
            tracing::warn!("[DNS] DoH failed for {hostname}, falling back to system resolver");
            match tokio::net::lookup_host((hostname, 80)).await {
                Ok(addrs) => {
                    let v4: Vec<IpAddr> = addrs.filter(|a| a.is_ipv4()).map(|a| a.ip()).collect();
                    if !v4.is_empty() { v4 } else { Vec::new() }
                }
                Err(e) => {
                    tracing::error!("[DNS] System resolver also failed for {hostname}: {e}");
                    Vec::new()
                }
            }
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

async fn resolve_doh(url: &str, accept: &str) -> Result<Vec<IpAddr>, Box<dyn std::error::Error + Send + Sync>> {
    let resp = HTTP_CLIENT.get(url)
        .header("Accept", accept)
        .send()
        .await?;
    let data: serde_json::Value = resp.json().await?;
    let ips: Vec<IpAddr> = data["Answer"].as_array()
        .map(|answers| {
            answers.iter()
                .filter_map(|a| a["data"].as_str())
                .filter_map(|s| s.parse::<IpAddr>().ok())
                .filter(|ip| ip.is_ipv4()) // IPv6 leak prevention: MAG254 is IPv4-only
                .collect()
        })
        .unwrap_or_default();
    Ok(ips)
}

/// Look up the European timezone for a portal hostname using ip-api.com
/// with a fixed Deutsche Telekom IP (avoids Cloudflare Anycast skew).
/// Cached for 1 hour keyed by hostname.
pub async fn get_european_timezone(hostname: &str) -> String {
    {
        let cache = TZ_CACHE.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = cache.get(hostname) {
            if entry.expires > Instant::now() {
                return entry.tz.clone();
            }
        }
    }

    // Geolocate a known European IP to get ground-truth timezone.
    let block = random_eu_block();
    let url = format!("http://ip-api.com/json/{}.0.1?fields=timezone", block.prefix);
    let tz = match HTTP_CLIENT.get(&url).send().await {
        Ok(resp) => {
            if let Ok(data) = resp.json::<serde_json::Value>().await {
                data["timezone"].as_str().unwrap_or(block.tz).to_string()
            } else {
                block.tz.to_string()
            }
        }
        Err(_) => block.tz.to_string(),
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

/// Detect the server's real local timezone via ip-api.com (no IP spoofing).
/// Cached for 24 hours -- the server's physical location rarely changes.
pub async fn get_local_timezone() -> String {
    static LOCAL_TZ: std::sync::OnceLock<tokio::sync::Mutex<Option<(String, Instant)>>> = std::sync::OnceLock::new();
    let lock = LOCAL_TZ.get_or_init(|| tokio::sync::Mutex::new(None));
    {
        let cached = lock.lock().await;
        if let Some((tz, expires)) = cached.as_ref() {
            if *expires > Instant::now() {
                return tz.clone();
            }
        }
    }
    // Query ip-api.com with real IP -- no ECS, no spoofing
    let tz = match HTTP_CLIENT.get("http://ip-api.com/json/?fields=timezone")
        .timeout(Duration::from_secs(5))
        .send().await
    {
        Ok(resp) => match resp.json::<serde_json::Value>().await {
            Ok(data) => data["timezone"].as_str().unwrap_or("UTC").to_string(),
            Err(_) => "UTC".to_string(),
        },
        Err(_) => "UTC".to_string(),
    };
    let mut cached = lock.lock().await;
    *cached = Some((tz.clone(), Instant::now() + Duration::from_secs(86400)));
    tz
}
