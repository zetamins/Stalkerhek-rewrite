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

pub async fn discover_portals(portal_url: &str) -> DiscoverResult {
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
                        discover_from_subnets(KNOWN_SUBNETS, &mut domain_portals, &mut ip_fallback).await;
                        break;
                    }

                    // Direct IP — reverse-IP lookup
                    tracing::info!("[discover] direct IP {} — reverse lookup", v4);
                    let domains = reverse_ip_lookup(&v4.to_string()).await;
                    if !domains.is_empty() {
                        tracing::info!("[discover] {} domains found via reverse-IP", domains.len());
                        test_domains(&domains, &mut domain_portals).await;
                    }
                    // Also scan subnet for neighboring IPs
                    let subnet = format!("{}.{}.{}", octets[0], octets[1], octets[2]);
                    discover_from_subnets(&[&subnet], &mut domain_portals, &mut ip_fallback).await;
                    break;
                }
            }
        }
    }

    // Fallback: scan known subnets
    if domain_portals.is_empty() {
        discover_from_subnets(KNOWN_SUBNETS, &mut domain_portals, &mut ip_fallback).await;
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
            test_domains(&domains, domain_portals).await;
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
    // Provider 1: hackertarget (most comprehensive, free, but rate-limited)
    let domains = reverse_ip_hackertarget(ip).await;
    if !domains.is_empty() {
        return domains;
    }
    // Provider 2: yougetsignal (free, no key, limited results)
    reverse_ip_yougetsignal(ip).await
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
async fn test_domains(domains: &[String], results: &mut Vec<(String, f64)>) {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
    {
        Ok(c) => c,
        Err(_) => return,
    };

    let mut handles = Vec::new();
    for domain in domains {
        let c = client.clone();
        let d = domain.clone();
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
                                // Verify with handshake
                                if test_handshake_url(&format!("http://{}:80", d)).await {
                                    let elapsed = start.elapsed().as_secs_f64();
                                    return Some((format!("http://{}:80", d), elapsed));
                                }
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
