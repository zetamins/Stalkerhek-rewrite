//! Portal Discovery — scans subnets for no-Cloudflare Stalker portals.
//! Runs before profile save via API endpoint POST /api/v1/discover.

use std::time::Duration;

#[derive(Debug, Clone, serde::Serialize)]
pub struct DiscoverResult {
    pub best_portal: String,
    pub all_portals: Vec<String>,
    pub discovered: bool,
}

const KNOWN_SUBNETS: &[&str] = &["103.176.90", "185.245.0"];

pub async fn discover_portals(portal_url: &str) -> DiscoverResult {
    let mut portals: Vec<(String, f64)> = Vec::new();

    // Step 1: Try to get origin IP, scan relevant subnet
    if let Ok(parsed) = url::Url::parse(portal_url) {
        if let Some(host) = parsed.host_str() {
            let ips: Vec<std::net::IpAddr> = tokio::net::lookup_host((host, 80))
                .await
                .map(|iter| iter.map(|a| a.ip()).collect())
                .unwrap_or_default();

            for ip in ips {
                if let std::net::IpAddr::V4(v4) = ip {
                    let octets = v4.octets();

                    // Cloudflare IP? Scan all known subnets
                    if (octets[0] == 104 && octets[1] >= 16 && octets[1] <= 31)
                        || (octets[0] == 172 && octets[1] >= 64 && octets[1] <= 71)
                    {
                        tracing::info!("[discover] {} is Cloudflare, scanning all subnets", host);
                        for subnet in KNOWN_SUBNETS {
                            scan_subnet(subnet, &mut portals).await;
                        }
                        break;
                    }

                    // Direct IP — scan its /24
                    let subnet = format!("{}.{}.{}", octets[0], octets[1], octets[2]);
                    tracing::info!("[discover] direct IP {}, scanning {}", ip, subnet);
                    scan_subnet(&subnet, &mut portals).await;
                    break;
                }
            }
        }
    }

    // Step 2: Nothing found? Scan all known subnets anyway
    if portals.is_empty() {
        for subnet in KNOWN_SUBNETS {
            scan_subnet(subnet, &mut portals).await;
        }
    }

    // Sort by latency
    portals.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
    portals.dedup_by(|a, b| a.0 == b.0);

    let all: Vec<String> = portals.iter().map(|(url, _)| format!("{}/c/", url)).collect();
    let best = all.first().cloned().unwrap_or_else(|| portal_url.to_string());

    DiscoverResult {
        best_portal: best,
        discovered: !all.is_empty(),
        all_portals: all,
    }
}

async fn scan_subnet(subnet: &str, portals: &mut Vec<(String, f64)>) {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
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
            match c.get(&url).header("User-Agent", "MAG254").send().await {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let is_cf = resp.headers().get("cf-ray").is_some()
                        || resp.headers().get("server").map_or(false, |v| {
                            v.to_str().unwrap_or("").contains("cloudflare")
                        });
                    // 200/401 = Stalker portal, 302 = redirect (admin login)
                    if (status == 200 || status == 401 || status == 302) && !is_cf {
                        let elapsed = start.elapsed().as_secs_f64();
                        // Quick handshake test
                        if test_handshake(&ip).await {
                            return Some((format!("http://{}:80", ip), elapsed));
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
}

async fn test_handshake(ip: &str) -> bool {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    for path in &["/c/portal.php", "/portal.php"] {
        let url = format!("http://{}{}", ip, path);
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
