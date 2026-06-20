//! Portal Discovery — reverse-IP lookup to find no-Cloudflare Stalker portals.
//! Runs via API endpoint POST /api/v1/discover.

use std::time::Duration;

use regex::Regex;

#[derive(Debug, Clone, serde::Serialize)]
pub struct DiscoverResult {
    pub best_portal: String,
    pub all_portals: Vec<String>,
    pub discovered: bool,
}

const KNOWN_SUBNETS: &[&str] = &["103.176.90", "185.245.0"];

/// Discover portals without MAC validation (for the public /api/v1/discover endpoint).
pub async fn discover_portals(portal_url: &str) -> DiscoverResult {
    discover_portals_inner(portal_url, None).await
}

/// Discover portals and validate that the given MAC has an active subscription (channels > 0).
/// Used when creating a profile — only promotes a portal if the user can actually use it.
pub async fn discover_portals_with_mac(portal_url: &str, mac: &str) -> DiscoverResult {
    discover_portals_inner(portal_url, Some(mac)).await
}

async fn discover_portals_inner(portal_url: &str, mac: Option<&str>) -> DiscoverResult {
    let mut domain_portals: Vec<(String, f64)> = Vec::new();
    let mut ip_fallback: Vec<(String, f64)> = Vec::new();

    if let Ok(parsed) = url::Url::parse(portal_url) {
        if let Some(host) = parsed.host_str() {
            let ips: Vec<std::net::IpAddr> = tokio::net::lookup_host((host, 80))
                .await
                .map(|iter| iter.map(|a| a.ip()).collect())
                .unwrap_or_default();

            for ip in ips {
                if let std::net::IpAddr::V4(v4) = ip {
                    let octets = v4.octets();
                    let is_cf = (octets[0] == 104 && octets[1] >= 16 && octets[1] <= 31)
                        || (octets[0] == 172 && octets[1] >= 64 && octets[1] <= 71);

                    if is_cf {
                        tracing::info!("[discover] {} is Cloudflare — scanning known subnets", host);
                        discover_from_subnets(KNOWN_SUBNETS, &mut domain_portals, &mut ip_fallback, mac).await;
                        break;
                    }

                    // Direct IP — reverse-IP lookup
                    tracing::info!("[discover] direct IP {} — reverse lookup", v4);
                    let domains = reverse_ip_lookup(&v4.to_string()).await;
                    if !domains.is_empty() {
                        tracing::info!("[discover] {} domains found via reverse-IP", domains.len());
                        test_domains(&domains, &mut domain_portals, mac).await;
                    }
                    // Also scan subnet for neighboring IPs
                    let subnet = format!("{}.{}.{}", octets[0], octets[1], octets[2]);
                    discover_from_subnets(&[&subnet], &mut domain_portals, &mut ip_fallback, mac).await;
                    break;
                }
            }
        }
    }

    // Fallback: scan known subnets
    if domain_portals.is_empty() {
        discover_from_subnets(KNOWN_SUBNETS, &mut domain_portals, &mut ip_fallback, mac).await;
    }

    // Prefer domain portals over bare IPs. Only use IPs if no domains found.
    if domain_portals.is_empty() {
        domain_portals = ip_fallback;
    }

    domain_portals.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
    domain_portals.dedup_by(|a, b| a.0 == b.0);

    let all: Vec<String> = domain_portals.iter().map(|(url, _)| format!("{}/c/", url)).collect();
    let best = all.first().cloned().unwrap_or_else(|| portal_url.to_string());

    DiscoverResult {
        best_portal: best,
        discovered: !all.is_empty(),
        all_portals: all,
    }
}

/// Scan subnets, do reverse-IP on found IPs. Domains go into `domain_portals`,
/// bare IPs (where reverse-IP returned nothing) go into `ip_fallback` as last resort.
/// Only does one reverse-IP lookup per subnet — all IPs on same /24 share domains.
async fn discover_from_subnets(
    subnets: &[&str],
    domain_portals: &mut Vec<(String, f64)>,
    ip_fallback: &mut Vec<(String, f64)>,
    mac: Option<&str>,
) {
    for subnet in subnets {
        let mut found = Vec::new();
        scan_subnet(subnet, &mut found).await;
        if found.is_empty() {
            continue;
        }
        // One reverse-IP lookup per subnet (all IPs share same domains)
        let sample_ip = found[0].0.trim_start_matches("http://").trim_end_matches(":80");
        let domains = reverse_ip_lookup(sample_ip).await;
        if !domains.is_empty() {
            tracing::info!("[discover] subnet {} → {} domains, testing...", subnet, domains.len());
            test_domains(&domains, domain_portals, mac).await;
        }
        // Only use IP fallback if no domains at all
        if domain_portals.is_empty() {
            ip_fallback.extend(found);
        }
    }
}

/// Query reverse-IP providers for all domains hosted on an IP.
/// Falls back through multiple providers.
async fn reverse_ip_lookup(ip: &str) -> Vec<String> {
    // Try the given IP first, then fall back to known-good IPs on the same subnet
    let candidates = [ip.to_string(), format!("{}.144", subnet_of(ip)), format!("{}.1", subnet_of(ip))];
    for candidate in &candidates {
        let domains = reverse_ip_hackertarget(candidate).await;
        if !domains.is_empty() {
            return domains;
        }
    }
    // Provider 2: yougetsignal (free, no key, limited results)
    reverse_ip_yougetsignal(ip).await
}

/// Extract the /24 prefix from an IP like "103.176.90.139" → "103.176.90"
fn subnet_of(ip: &str) -> &str {
    match ip.rfind('.') {
        Some(pos) => &ip[..pos],
        None => ip,
    }
}

async fn reverse_ip_hackertarget(ip: &str) -> Vec<String> {
    let url = format!("https://api.hackertarget.com/reverseiplookup/?q={}", ip);
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };

    match client.get(&url).header("User-Agent", "curl/7.88").send().await {
        Ok(resp) => {
            if !resp.status().is_success() {
                return Vec::new();
            }
            match resp.text().await {
                Ok(body) => {
                    let domain_re = Regex::new(
                        r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"
                    ).unwrap();
                    body.lines()
                        .map(|l| l.trim().to_lowercase())
                        .filter(|l| {
                            !l.is_empty()
                                && !l.contains("error")
                                && !l.contains("invalid")
                                && !l.contains("API")
                                && !l.starts_with('<')
                                && l.len() >= 4
                                && l.contains('.')
                                && domain_re.is_match(l)
                        })
                        .collect()
                }
                Err(_) => Vec::new(),
            }
        }
        Err(_) => Vec::new(),
    }
}

async fn reverse_ip_yougetsignal(ip: &str) -> Vec<String> {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };

    match client
        .post("https://domains.yougetsignal.com/domains.php")
        .header("User-Agent", "Mozilla/5.0")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(format!("remoteAddress={}&key=", ip))
        .send()
        .await
    {
        Ok(resp) => {
            if !resp.status().is_success() {
                return Vec::new();
            }
            match resp.text().await {
                Ok(body) => {
                    let mut domains = Vec::new();
                    // Response: {"domainArray":[["domain.com",""],...]}
                    let domain_re = Regex::new(
                        r#"\["([a-z0-9]([a-z0-9-]*[a-z0-9])?\.[a-z]{2,})""#
                    ).unwrap();
                    for cap in domain_re.captures_iter(&body) {
                        let d = cap[1].to_string();
                        if !domains.contains(&d) {
                            domains.push(d);
                        }
                    }
                    domains
                }
                Err(_) => Vec::new(),
            }
        }
        Err(_) => Vec::new(),
    }
}

/// Test a batch of domains in parallel — check Stalker markers + no Cloudflare + handshake.
/// If `mac` is provided, also verify that the MAC has channel access on this portal.
async fn test_domains(domains: &[String], results: &mut Vec<(String, f64)>, mac: Option<&str>) {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .pool_max_idle_per_host(200)
        .build()
    {
        Ok(c) => c,
        Err(_) => return,
    };

    let mut handles = Vec::new();
    for domain in domains {
        let c = client.clone();
        let d = domain.clone();
        let mac = mac.map(|m| m.to_string());
        handles.push(tokio::spawn(async move {
            let url = format!("http://{}/c/", d);
            let start = std::time::Instant::now();
            match c.get(&url).header("User-Agent", "MAG254").send().await {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let is_cf = resp.headers().get("cf-ray").is_some()
                        || resp.headers().get("server")
                            .and_then(|v| v.to_str().ok())
                            .unwrap_or("")
                            .to_lowercase()
                            .contains("cloudflare");
                    if (status == 200 || status == 401 || status == 302) && !is_cf {
                        if let Ok(body) = resp.text().await {
                            if body.len() > 100
                                && (body.contains("loadRequiredFiles")
                                    || body.contains("stb.")
                                    || body.contains("portal"))
                            {
                                let base = format!("http://{}:80", d);
                                // Verify handshake
                                if !test_handshake_url(&base).await {
                                    return None;
                                }
                                // If MAC provided, verify it has channel access
                                if let Some(ref m) = mac {
                                    if !test_channel_access(&base, m).await {
                                        return None;
                                    }
                                }
                                let elapsed = start.elapsed().as_secs_f64();
                                return Some((base, elapsed));
                            }
                        }
                    }
                    None
                }
                Err(_) => None,
            }
        }));
    }

    for h in handles {
        if let Ok(Some(result)) = h.await {
            results.push(result);
        }
    }
}

async fn scan_subnet(subnet: &str, portals: &mut Vec<(String, f64)>) {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_millis(800))
        .pool_max_idle_per_host(100)
        .build()
    {
        Ok(c) => c,
        Err(_) => return,
    };

    let mut handles = Vec::new();
    for i in 1u8..=254 {
        let ip = format!("{}.{}", subnet, i);
        let c = client.clone();
        handles.push(tokio::spawn(async move {
            let url = format!("http://{}/c/", ip);
            let start = std::time::Instant::now();
            let result = c.get(&url).header("User-Agent", "MAG254").send().await;
            match result {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let server_hdr = resp
                        .headers()
                        .get("server")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or("");
                    let is_cf =
                        resp.headers().get("cf-ray").is_some() || server_hdr.contains("cloudflare");
                    if (status == 200 || status == 401 || status == 302) && !is_cf {
                        if let Ok(body) = resp.text().await {
                            if body.len() > 100
                                && (body.contains("loadRequiredFiles")
                                    || body.contains("stb.")
                                    || body.contains("portal"))
                            {
                                let elapsed = start.elapsed().as_secs_f64();
                                return Some((format!("http://{}:80", ip), elapsed));
                            }
                        }
                    }
                    None
                }
                Err(_) => None,
            }
        }));
    }

    for h in handles {
        if let Ok(Some(result)) = h.await {
            portals.push(result);
        }
    }
    // Handshake verify found IPs
    let mut verified = Vec::new();
    let mut vh = Vec::new();
    for (url, time) in portals.drain(..) {
        vh.push(tokio::spawn(async move {
            if test_handshake_url(&url).await {
                Some((url, time))
            } else {
                None
            }
        }));
    }
    for h in vh {
        if let Ok(Some(result)) = h.await {
            verified.push(result);
        }
    }
    *portals = verified;
}

async fn test_handshake_url(base_url: &str) -> bool {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    for path in &[
        "c/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml",
        "portal.php?type=stb&action=handshake&JsHttpRequest=1-xml",
    ] {
        let url = format!("{}/{}", base_url, path);
        if let Ok(resp) = client
            .post(&url)
            .header("User-Agent", "MAG254")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("mac=00:1A:79:00:00:01&sn=0000000000000&stb_type=MAG254")
            .send()
            .await
        {
            if resp.status().is_success() {
                if let Ok(body) = resp.text().await {
                    if body.contains("\"token\"") {
                        return true;
                    }
                }
            }
        }
    }
    false
}

/// Check if a MAC has an active subscription on this portal (returns channels > 0).
/// Lightweight: requests page 1 with per_page=1, just need to know if any exist.
async fn test_channel_access(base_url: &str, mac: &str) -> bool {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .pool_max_idle_per_host(200)
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    let url = format!(
        "{}/c/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
        base_url
    );
    match client
        .post(&url)
        .header("User-Agent", "MAG254")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(format!(
            "mac={}&sn=0000000000000&stb_type=MAG254&auth_second_step=1&hd=1&not_valid_token=1",
            mac
        ))
        .send()
        .await
    {
        Ok(resp) => {
            if !resp.status().is_success() {
                return false;
            }
            match resp.text().await {
                Ok(body) => {
                    // Parse: either {"js":{"data":[...]}} or {"js":[...]}
                    if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&body) {
                        if let Some(js) = parsed.get("js") {
                            let count = if let Some(data) = js.get("data") {
                                data.as_array().map(|a| a.len()).unwrap_or(0)
                            } else if let Some(arr) = js.as_array() {
                                arr.len()
                            } else {
                                0
                            };
                            return count > 0;
                        }
                    }
                    false
                }
                Err(_) => false,
            }
        }
        Err(_) => false,
    }
}
