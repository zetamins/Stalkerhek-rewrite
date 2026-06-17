use reqwest::RequestBuilder;
use serde_json;

/// Deep Scrubber: Sanitizes hidden identity data in the 'metrics' query parameter.
pub fn scrub_metrics(query_params: &mut Vec<(String, String)>, serial: &str, mac: &str) {
    for (key, value) in query_params.iter_mut() {
        if key == "metrics" {
            if let Ok(mut json) = serde_json::from_str::<serde_json::Value>(value) {
                if let Some(obj) = json.as_object_mut() {
                    // Scrub all possible identity leaks in the JSON block
                    obj.insert("mac".to_string(), serde_json::Value::String(mac.to_string()));
                    obj.insert("sn".to_string(), serde_json::Value::String(serial.to_string()));
                    obj.insert("ver".to_string(), serde_json::Value::String("Image_0.2.18-r14-254".to_string()));
                    if let Ok(scrubbed) = serde_json::to_string(&json) {
                        *value = scrubbed;
                    }
                }
            }
        }
    }
}

pub fn apply_mag_headers(
    req: RequestBuilder,
    token: &str,
    serial_number: &str,
    mac: &str,
    _timezone: &str,
    model: &str,
    host: &str, // Pass host for sticky identity
) -> RequestBuilder {
    // Transcendental Method 1: Sticky identity per host (4 hours)
    let (eur_ip, eur_tz) = crate::dns::get_sticky_european_identity(host);
    
    // Transcendental Method 5: Micro-Jitter (1-5ms) to mimic human timing
    use rand::Rng;
    let mut rng = rand::thread_rng();
    std::thread::sleep(std::time::Duration::from_millis(rng.gen_range(1..5)));

    // Method 2: Strict Header Sequencing (Physical MAG254 Order)
    // Absolute Method 1: Packet Length Obfuscation (DPI Death)
    // Add randomized junk padding so every request has a different byte size.
    let padding_len = rng.gen_range(32..128);
    let padding: String = (0..padding_len).map(|_| (rng.gen_range(33..126) as u8) as char).collect();

    req.header("User-Agent", format!("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) {} stbapp ver: 4 rev: {} Mobile Safari/533.3", model, rev))
        .header("X-User-Agent", format!("Model: {}; Link: Ethernet", model))
        .header("Authorization", format!("Bearer {}", token))
        .header("X-DPI-Padding", padding) // Randomized packet size
        // Transcendental Method 2: Stealth Cookie Jar (drop tracking cookies)

        .header("Cookie", format!("PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};", serial_number, mac, eur_tz))
        .header("Accept", "*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("X-Forwarded-For", &eur_ip)
        .header("X-Real-IP", &eur_ip)
        .header("CF-Connecting-IP", &eur_ip)
        .header("True-Client-IP", &eur_ip)
        .header("X-Originating-IP", &eur_ip)
        .header("Connection", "keep-alive")
}
