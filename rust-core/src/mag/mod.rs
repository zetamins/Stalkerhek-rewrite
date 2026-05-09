use reqwest::RequestBuilder;

pub fn apply_mag_headers(
    req: RequestBuilder,
    token: &str,
    serial_number: &str,
    mac: &str,
    timezone: &str,
    model: &str,
) -> RequestBuilder {
    req.header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2116 Mobile Safari/533.3")
        .header("X-User-Agent", format!("Model: {}; Link: Ethernet", model))
        .header("Authorization", format!("Bearer {}", token))
        .header("Cookie", format!("PHPSESSID=null; sn={}; mac={}; stb_lang=en; timezone={};", serial_number, mac, timezone))
        .header("Accept", "*/*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
}
