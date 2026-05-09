package com.stalkerhek.api

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.Duration

fun Routing.healthRoutes(profileService: ProfileService) {
    val startTime = System.currentTimeMillis()

    fun uptime(): String {
        val seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).seconds
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%dh %dm %ds", h, m, s)
    }

    get("/health") {
        val profiles = profileService.listProfiles()
        val statuses = profileService.listStatuses()
        var running = 0
        var errors = 0
        for (st in statuses) {
            if (st.running) running++
            if (st.phase == "error") errors++
        }
        val status = when {
            profiles.isEmpty() -> "no_profiles"
            errors > 0 -> "degraded"
            else -> "healthy"
        }
        val checks = mapOf(
            "webui" to "ok",
            "storage" to "ok",
            "profiles" to "${profiles.size} total, $running running, $errors errors"
        )
        val h = HealthResponse(
            status = status,
            uptime = uptime(),
            version = "dev",
            profiles = profiles.size,
            running = running,
            errors = errors,
            webui = true,
            checks = checks,
            timestamp = Instant.now().toString()
        )
        if (status == "healthy") {
            call.respondText(json.encodeToString(h), ContentType.Application.Json)
        } else {
            call.respondText(json.encodeToString(h), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        }
    }

    get("/api/info") {
        val profiles = profileService.listProfiles()
        val statuses = profileService.listStatuses()
        val host = call.request.headers["Host"] ?: "localhost"
        val numGo = Runtime.getRuntime().availableProcessors()

        call.respondText(renderInfoPage(host, uptime(), profiles, statuses, numGo), ContentType.Text.Html)
    }
}

fun renderInfoPage(host: String, uptime: String, profiles: List<Profile>, statuses: List<ProfileStatus>, numGo: Int): String {
    val profileRows = if (profiles.isEmpty()) {
        """<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:20px">No profiles configured</td></tr>"""
    } else {
        profiles.joinToString("\n") { p ->
            val st = statuses.find { it.id == p.id }
            val running = st?.running ?: false
            val phase = st?.phase ?: "idle"
            val badgeClass = when {
                running -> "ok"
                phase == "error" -> "err"
                else -> ""
            }
            val badgeText = when {
                running -> "Running"
                phase == "error" -> "Error"
                else -> "Idle"
            }
            """<tr>
<td style="font-weight:600">${if (p.name.isNotEmpty()) p.name else "Profile ${p.id}"}</td>
<td style="word-break:break-all;font-size:12px">${p.portalUrl}</td>
<td class="mono">${p.mac}</td>
<td>:${p.hlsPort}</td>
<td>:${p.proxyPort}</td>
<td><span class="badge $badgeClass">$badgeText</span></td>
</tr>"""
        }
    }

    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Stalkerhek Info</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--ok:#3fb970;--bad:#e85d4d;--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:960px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s}
.card:hover{border-color:var(--border-light)}
h1{font-size:clamp(18px,2.8vw,24px);font-weight:700;margin-bottom:4px}
h2{font-size:17px;font-weight:700;margin-bottom:12px}
.sub{color:var(--muted);font-size:13px;margin-bottom:16px}
.grid{display:grid;grid-template-columns:repeat(auto-fit, minmax(170px, 1fr));gap:10px}
.stat{background:var(--bg);border:1px solid var(--border);border-radius:12px;padding:14px}
.stat-label{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.5px;font-weight:600}
.stat-value{font-size:22px;font-weight:700;color:var(--text);margin-top:4px}
table{width:100%;border-collapse:collapse;margin-top:4px}
th{text-align:left;padding:10px 12px;border-bottom:1px solid var(--border);font-size:12px;text-transform:uppercase;letter-spacing:.5px;font-weight:600;color:var(--muted)}
td{padding:10px 12px;border-bottom:1px solid rgba(31,46,35,.55);font-size:13px;color:var(--text)}
td:first-child{padding-left:0}th:first-child{padding-left:0}td:last-child{padding-right:0}th:last-child{padding-right:0}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono","Courier New",monospace;font-size:12px}
.badge{display:inline-block;padding:3px 8px;border-radius:999px;font-size:11px;font-weight:600}
.badge.ok{background:rgba(63,185,112,.15);color:var(--ok);border:1px solid rgba(63,185,112,.25)}
.badge.err{background:rgba(232,93,77,.15);color:var(--bad);border:1px solid rgba(232,93,77,.25)}
.badge.idle,div:not(.badge) > .badge:not(.ok):not(.err){background:rgba(139,163,141,.1);color:var(--muted);border:1px solid var(--border)}
::-webkit-scrollbar{width:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
@media(max-width:600px){.nav-links .nav-link span{display:none}}
</style>
</head>
<body>
<div class="wrap">
  <div class="top-bar">
    <div class="logo">
      <img src="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png" alt="" onerror="this.style.display='none'" />
      <h1>Stalkerhek</h1>
    </div>
    <div class="nav-links">
      <a class="nav-link" href="/dashboard"><i class="fa-solid fa-arrow-left"></i><span>Dashboard</span></a>
      <a class="nav-link" href="/logs" target="_blank"><i class="fa-regular fa-file-lines"></i><span>Logs</span></a>
    </div>
  </div>
  <div class="card">
    <h1>System Info</h1>
    <div class="sub">Uptime: $uptime | Kotlin ${System.getProperty("java.version")} | $numGo processors</div>
    <div class="grid">
      <div class="stat"><div class="stat-label">Host</div><div class="stat-value" style="font-size:16px">$host</div></div>
      <div class="stat"><div class="stat-label">Profiles</div><div class="stat-value">${profiles.size}</div></div>
      <div class="stat"><div class="stat-label">Running</div><div class="stat-value" style="color:var(--ok)">${statuses.count { it.running }}</div></div>
      <div class="stat"><div class="stat-label">Errors</div><div class="stat-value" style="color:var(--bad)">${statuses.count { it.phase == "error" }}</div></div>
    </div>
  </div>
  <div class="card">
    <h2><i class="fa-solid fa-list"></i> Profiles</h2>
    <table>
      <thead><tr><th>Name</th><th>Portal</th><th>MAC</th><th>HLS</th><th>Proxy</th><th>Status</th></tr></thead>
      <tbody>$profileRows</tbody>
    </table>
  </div>
</div>
</body>
</html>"""
}
