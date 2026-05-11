package com.stalkerhek.tv.management

import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.engine.ProfileConfig
import com.stalkerhek.tv.engine.ProfileStatus
import com.stalkerhek.tv.util.getLocalIpAddress
import kotlinx.coroutines.runBlocking

fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

fun renderDashboardHtml(engine: EngineController): String {
    val profiles = engine.profiles.value
    val localIp = getLocalIpAddress()
    val statuses = profiles.map { p ->
        try {
            runBlocking { engine.getProfileStatus(p.id) }
        } catch (_: Exception) { null }
    }

    val profileCards = if (profiles.isEmpty()) {
        """<div class="empty-state"><i class="fa-solid fa-plus-circle"></i><h3>No Profiles Yet</h3><p class="sub">Create your first profile to get started with IPTV streaming.</p></div>"""
    } else {
        profiles.joinToString("\n") { p ->
            val idx = profiles.indexOf(p)
            val st = statuses.getOrNull(idx)
            val phase = st?.phase ?: "idle"
            val message = st?.message ?: ""
            val running = st?.running ?: false
            val channels = st?.channelsCount ?: 0
            val badgeClass = when (phase) {
                "success" -> if (running) "run" else "ok"
                "error" -> "err"
                else -> ""
            }
            val busy = phase == "validating" || phase == "starting"
            val badgeText = when { busy -> "Starting..."; running -> "Running"; phase == "success" -> if (message.isNotEmpty()) message else "Ready"; phase == "error" -> "Error"; phase == "validating" -> "Working..."; else -> "Idle" }
            val badgeIcon = when { running -> "play"; phase == "error" -> "exclamation-triangle"; busy -> "spinner fa-spin"; else -> "pause" }
            """<div class="profile-card" data-id="${p.id}" data-name="${p.name.escapeHtml()}" data-portal="${p.portalUrl.escapeHtml()}" data-mac="${p.mac.escapeHtml()}" data-hls="${p.hlsPort}" data-proxy="${p.proxyPort}" data-model="${p.model.escapeHtml()}" data-serial="${p.serialNumber.escapeHtml()}" data-deviceid="${p.deviceId.escapeHtml()}" data-deviceid2="${p.deviceId2.escapeHtml()}" data-signature="${p.signature.escapeHtml()}" data-timezone="${p.timezone.escapeHtml()}" data-username="${p.username.escapeHtml()}" data-password="" data-watchdog="${p.watchdogInterval}">
  <div class="card-header">
    <div class="card-info">
      <div class="card-name">${if (p.name.isNotEmpty()) p.name.escapeHtml() else "Profile ${p.id}"}</div>
      <div class="card-meta"><i class="fa-solid fa-link"></i> ${p.portalUrl.escapeHtml()}</div>
      <div class="card-meta"><i class="fa-solid fa-network-wired"></i> ${p.mac.escapeHtml()}</div>
    </div>
    <span class="badge $badgeClass" id="badge-${p.id}"><i class="fa-solid fa-$badgeIcon" id="badge-icon-${p.id}"></i> <span id="badge-text-${p.id}">$badgeText</span></span>
  </div>
  <div class="card-details" id="meta-${p.id}">${if (message.isNotEmpty()) "<div class=\"detail-item\"><i class=\"fa-solid fa-info-circle\"></i> " + message.escapeHtml() + "</div>" else ""}${if (channels > 0) "<div class=\"detail-item\"><i class=\"fa-solid fa-satellite-dish\"></i> Channels: $channels</div>" else ""}</div>
  <div class="card-actions">
    <button class="btn btn-start" id="startbtn-${p.id}" onclick="postForm('/api/profiles/start',{id:'${p.id}'});showToast('Starting','Starting profile ${p.id}...');" ${if (busy || running) "disabled" else ""}><i class="fa-solid fa-play"></i> <span>Start</span></button>
    <button class="btn btn-stop" onclick="postForm('/api/profiles/stop',{id:'${p.id}'});showToast('Stopped','Profile ${p.id} stopped.');"><i class="fa-solid fa-stop"></i> <span>Stop</span></button>
    <button class="btn btn-ghost" data-action="edit"><i class="fa-solid fa-pen"></i> <span>Edit</span></button>
    <button class="btn btn-ghost" data-action="quickedit"><i class="fa-solid fa-sliders"></i> <span>Advanced</span></button>
    <form method="post" action="/profiles/delete" style="margin:0;display:inline-flex" onsubmit="return confirm('Delete this profile? This cannot be undone.')"><input type="hidden" name="id" value="${p.id}"/><button class="btn btn-danger" type="submit"><i class="fa-solid fa-trash"></i> <span>Delete</span></button></form>
    <span class="action-spacer"></span>
    <a class="btn btn-ghost" href="#" data-copy="http://$localIp:${p.hlsPort}/" onclick="copyLink(event,this)"><i class="fa-solid fa-film"></i> <span>HLS</span></a>
    <a class="btn btn-ghost link-proxy" href="#" data-copy="http://$localIp:${p.proxyPort}/" onclick="copyLink(event,this)"><i class="fa-solid fa-right-left"></i> <span>Proxy</span></a>
    <a class="btn btn-ghost" href="/filters?id=${p.id}" target="_blank" rel="noopener"><i class="fa-solid fa-filter"></i> <span>Filters</span></a>
  </div>
</div>"""
        }
    }

    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Stalkerhek Dashboard</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--ok:#3fb970;--warn:#d4a94a;--bad:#e85d4d;--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;display:flex;flex-direction:column;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:1120px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));flex:1;display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;color:var(--text);letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.tabs{display:flex;gap:4px;padding:4px;background:var(--surface);border:1px solid var(--border);border-radius:14px;overflow-x:auto;flex-shrink:0}
.tab{display:flex;align-items:center;gap:8px;padding:10px 18px;border-radius:10px;border:none;background:transparent;color:var(--muted);font-size:13px;font-weight:600;cursor:pointer;white-space:nowrap;transition:all .15s;font-family:var(--font)}
.tab:hover{color:var(--text);background:var(--surface2)}
.tab.active{background:var(--brand-glow);color:var(--brand);box-shadow:inset 0 0 0 1px rgba(45,138,78,.25)}
.tab i{font-size:14px}
.section{display:none;animation:fadeIn .25s ease}
.section.active{display:block}
@keyframes fadeIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:translateY(0)}}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s}
.card:hover{border-color:var(--border-light)}
.card-title{font-size:17px;font-weight:700;margin-bottom:4px;color:var(--text)}
.card-sub{color:var(--muted);font-size:13px;margin-bottom:16px}
label{display:block;font-size:12px;font-weight:600;color:var(--muted);margin:14px 0 5px;text-transform:uppercase;letter-spacing:.4px}
input,select{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--border);background:var(--bg);color:var(--text);outline:none;font-size:14px;transition:border-color .2s,box-shadow .2s;font-family:var(--font)}
input:focus,select:focus{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-glow)}
select option{background:var(--surface);color:var(--text)}
.form-row{display:grid;grid-template-columns:1fr;gap:10px}@media(min-width:540px){.form-row.two{grid-template-columns:1fr 1fr}}
.form-error{display:none;color:var(--bad);font-size:12px;margin-top:4px}
.btn-group{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}
.btn{display:inline-flex;align-items:center;gap:8px;padding:10px 16px;border-radius:10px;border:1px solid var(--border);background:var(--surface2);color:var(--text);font-size:13px;font-weight:600;cursor:pointer;transition:all .15s;font-family:var(--font);text-decoration:none;white-space:nowrap}
.btn:active{transform:scale(.97)}
.btn-primary{background:rgba(45,138,78,.15);border-color:var(--brand);color:var(--brand)}
.btn-primary:hover{background:rgba(45,138,78,.25);border-color:#3dba68}
.btn-start{background:rgba(63,185,112,.12);border-color:rgba(63,185,112,.3);color:var(--ok)}
.btn-start:hover{background:rgba(63,185,112,.22);border-color:var(--ok)}
.btn-stop{background:rgba(212,169,74,.1);border-color:rgba(212,169,74,.25);color:var(--warn)}
.btn-stop:hover{background:rgba(212,169,74,.2);border-color:var(--warn)}
.btn-danger{background:rgba(232,93,77,.1);border-color:rgba(232,93,77,.25);color:var(--bad)}
.btn-danger:hover{background:rgba(232,93,77,.2);border-color:var(--bad)}
.btn-ghost{background:transparent;color:var(--muted)}
.btn-ghost:hover{background:var(--surface2);color:var(--text);border-color:var(--border-light)}
.btn:disabled{opacity:.45;cursor:not-allowed;transform:none}
details.advanced-settings{margin-top:12px;border:1px solid var(--border);border-radius:12px;padding:12px;background:var(--bg)}
details.advanced-settings summary{cursor:pointer;color:var(--brand);font-size:13px;font-weight:600;user-select:none;display:flex;align-items:center;gap:8px}
details.advanced-settings[open]{border-color:var(--border-light)}
.profile-grid{display:grid;gap:12px}
.profile-card{padding:16px;border-radius:14px;border:1px solid var(--border);background:var(--surface2);transition:all .2s;display:flex;flex-direction:column;gap:12px}
.profile-card:hover{border-color:var(--border-light);box-shadow:0 4px 20px rgba(0,0,0,.25)}
.card-header{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
.card-info{min-width:0;flex:1}
.card-name{font-weight:700;font-size:15px;color:var(--text)}
.card-meta{font-size:12px;color:var(--muted);margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.card-meta i{width:14px;margin-right:2px}
.card-details{display:flex;flex-wrap:wrap;gap:4px 16px;font-size:12px;color:var(--muted)}
.detail-item{display:flex;align-items:center;gap:5px}
.card-actions{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
.card-actions .btn{padding:8px 10px;font-size:11px}
.action-spacer{flex:1;min-width:4px}
.badge{display:inline-flex;align-items:center;gap:6px;padding:5px 10px;border-radius:999px;font-size:11px;font-weight:700;border:1px solid var(--border);color:var(--muted);white-space:nowrap;flex-shrink:0}
.badge.ok{border-color:rgba(63,185,112,.25);color:var(--ok)}
.badge.err{border-color:rgba(232,93,77,.3);color:var(--bad)}
.badge.run{border-color:rgba(45,138,78,.35);color:#b8e6d0}
.empty-state{text-align:center;padding:40px 20px;color:var(--muted)}
.empty-state i{font-size:32px;color:var(--brand);opacity:.5;margin-bottom:12px}
.empty-state h3{font-size:16px;color:var(--text);margin-bottom:6px}
.settings-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;max-width:800px}
.setting-card{background:var(--bg);border:1px solid var(--border);border-radius:12px;padding:14px}
.setting-card .setting-label{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.4px;font-weight:600;margin-bottom:8px}
.setting-card input{margin-top:4px}
.setting-hint{font-size:11px;color:var(--muted);margin-top:6px;line-height:1.4}
.toast{position:fixed;top:calc(16px + env(safe-area-inset-top));left:50%;transform:translateX(-50%);z-index:100;background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:12px 18px;display:none;box-shadow:0 12px 40px rgba(0,0,0,.5);backdrop-filter:blur(12px);min-width:280px;max-width:calc(100vw - 32px);animation:slideDown .2s ease}
@keyframes slideDown{from{opacity:0;transform:translateX(-50%) translateY(-12px)}to{opacity:1;transform:translateX(-50%) translateY(0)}}
.toast-title{font-weight:700;font-size:13px}
.toast-msg{color:var(--muted);font-size:12px;margin-top:2px}
.bottom-nav{position:fixed;bottom:0;left:0;right:0;display:flex;justify-content:center;padding:10px 16px calc(10px + env(safe-area-inset-bottom));background:linear-gradient(transparent,var(--bg) 30%);pointer-events:none;z-index:50}
.bottom-nav-inner{display:flex;gap:6px;flex-wrap:wrap;justify-content:center;pointer-events:auto;background:rgba(12,18,14,.92);backdrop-filter:blur(14px);border:1px solid var(--border);border-radius:14px;padding:6px 12px;box-shadow:0 8px 32px rgba(0,0,0,.45)}
.bottom-link{display:inline-flex;align-items:center;gap:6px;padding:7px 12px;border-radius:8px;color:var(--muted);font-size:12px;font-weight:500;text-decoration:none;transition:all .15s}
.bottom-link:hover{background:var(--surface2);color:var(--text)}
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.65);z-index:200;align-items:center;justify-content:center;padding:16px;animation:fadeIn .15s ease}
.modal-overlay.open{display:flex}
.modal-box{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:20px;max-width:480px;width:100%;max-height:90vh;overflow-y:auto;box-shadow:0 20px 60px rgba(0,0,0,.5);animation:fadeIn .2s ease}
.modal-box h3{font-size:16px;font-weight:700;margin-bottom:14px;display:flex;align-items:center;gap:8px}
.modal-box h3 i{color:var(--brand)}
.modal-actions{display:flex;gap:10px;justify-content:flex-end;margin-top:18px}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--border-light)}
@media(max-width:600px){
  .nav-links .nav-link span{display:none}
  .card-title{font-size:15px}
  .tab{padding:9px 12px;font-size:12px}
  .profile-card{padding:14px}
  .btn span{display:none}
  .btn{padding:9px 11px}
  .bottom-link span{display:none}
  .card-header{flex-direction:column}
}
@media(max-width:420px){
  .tabs{gap:2px;padding:3px}
  .tab{padding:7px 9px;font-size:11px}
}
</style>
</head>
<body>
<div id="toast" class="toast"><div class="toast-title" id="toastTitle"></div><div class="toast-msg" id="toastMsg"></div></div>
<div class="wrap">
  <div class="top-bar">
    <div class="logo">
      <svg width="36" height="36" viewBox="0 0 96 96" style="flex-shrink:0"><circle cx="48" cy="48" r="46" fill="#080C09"/><circle cx="48" cy="48" r="38" fill="#2D8A4E"/><circle cx="48" cy="48" r="32" fill="#080C09"/><polygon points="40,36 40,60 60,48" fill="#2D8A4E"/></svg>
      <h1>Stalkerhek</h1>
    </div>
    <div class="nav-links">
      <a class="nav-link" href="/dashboard"><i class="fa-solid fa-gauge"></i><span>Dashboard</span></a>
      <a class="nav-link" href="/api/backup/export"><i class="fa-solid fa-download"></i><span>Export</span></a>
      <a class="nav-link" href="#" onclick="document.getElementById('import-file').click()"><i class="fa-solid fa-upload"></i><span>Import</span></a>
      <form id="import-form" method="post" action="/api/backup/import" enctype="multipart/form-data" style="display:none"><input id="import-file" type="file" name="backup" accept=".json" onchange="showToast('Importing','Restoring backup...');this.form.submit()"/></form>
      <a class="nav-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
    </div>
  </div>

  <div class="tabs" role="tablist">
    <button class="tab active" role="tab" data-tab="create"><i class="fa-solid fa-plus"></i> Create</button>
    <button class="tab" role="tab" data-tab="manage"><i class="fa-solid fa-layer-group"></i> Manage</button>
  </div>

  <div class="section active" id="section-create">
    <div class="card">
      <div class="card-title">Create / Edit Profile</div>
      <div class="card-sub">Configure portal credentials and ports for IPTV streaming.</div>
      <form id="addForm" method="post" action="/profiles" novalidate>
        <input type="hidden" id="edit_id" name="edit_id" value="" />
        <label for="name">Profile Name</label>
        <input id="name" name="name" placeholder="Living Room / Office / Backup" />
        <label for="portal">Portal URL <span style="color:var(--muted);font-weight:400;text-transform:none;letter-spacing:0">(portal.php or load.php)</span></label>
        <input id="portal" name="portal" required placeholder="http://example.com/stalker_portal/server/portal.php" />
        <div id="portalErr" class="form-error">Please paste a valid portal URL ending with /portal.php or /load.php</div>
        <label for="mac">MAC Address</label>
        <input id="mac" name="mac" required placeholder="00:1A:79:12:34:56" />
        <div id="macErr" class="form-error">MAC must look like 00:1A:79:12:34:56</div>
        <div class="form-row two">
          <div><label for="hls_port">HLS Port</label><input id="hls_port" name="hls_port" required inputmode="numeric" value="4600" /></div>
          <div><label for="proxy_port">Proxy Port</label><input id="proxy_port" name="proxy_port" required inputmode="numeric" value="4800" /></div>
        </div>
        <details class="advanced-settings">
          <summary><i class="fa-solid fa-sliders"></i> Advanced Portal Settings <span style="color:var(--muted);font-weight:400;font-size:12px">(optional)</span></summary>
          <div style="margin-top:14px">
            <div class="form-row two">
              <div><label>Username</label><input id="username" name="username" placeholder="Leave blank for Device ID auth" /></div>
              <div><label>Password</label><input id="password" name="password" type="password" placeholder="Leave blank for Device ID auth" /></div>
            </div>
            <div class="form-row two">
              <div><label>STB Model</label><input id="model" name="model" placeholder="MAG254" /></div>
              <div><label>Serial Number</label><input id="serial_number" name="serial_number" placeholder="0000000000000" /></div>
            </div>
            <label>Device ID</label><input id="device_id" name="device_id" placeholder="64-character hex (auto-generated if empty)" maxlength="64" />
            <label style="margin-top:10px">Device ID 2</label><input id="device_id2" name="device_id2" placeholder="64-character hex (auto-generated if empty)" maxlength="64" />
            <label style="margin-top:10px">Signature</label><input id="signature" name="signature" placeholder="64-character hex (auto-generated if empty)" maxlength="64" />
            <div class="form-row two" style="margin-top:10px">
              <div><label>Time Zone</label><input id="timezone" name="timezone" placeholder="UTC" /></div>
              <div><label>Watchdog (min)</label><input id="watchdog_time" name="watchdog_time" inputmode="numeric" placeholder="5" /></div>
            </div>
          </div>
        </details>
        <div class="btn-group">
          <button class="btn btn-primary" id="saveBtn" type="submit"><i class="fa-regular fa-floppy-disk"></i> <span>Save Profile</span></button>
          <button class="btn btn-ghost" type="button" id="cancelEdit" style="display:none"><i class="fa-solid fa-xmark"></i> <span>Cancel</span></button>
        </div>
        <p class="setting-hint" style="margin-top:12px">Tip: After saving, the profile will appear in the Manage tab.</p>
      </form>
    </div>
  </div>

  <div class="section" id="section-manage">
    <div class="card">
      <div class="card-title">Manage Profiles</div>
      <div class="card-sub">Start, stop, and configure your streaming profiles.</div>
      <div id="profiles" class="profile-grid">$profileCards</div>
    </div>
  </div>

</div>

<div class="bottom-nav">
  <div class="bottom-nav-inner">
    <span class="bottom-link"><i class="fa-solid fa-network-wired"></i><span>$localIp:4400</span></span>
    <a class="bottom-link" href="/dashboard"><i class="fa-solid fa-gauge"></i><span>Dashboard</span></a>
  </div>
</div>

<div id="qeModal" class="modal-overlay">
  <div class="modal-box">
    <h3><i class="fa-solid fa-sliders"></i> Quick Edit</h3>
    <form id="qeForm" method="post" action="/profiles">
      <input type="hidden" id="qe_edit_id" name="edit_id" />
      <input type="hidden" id="qe_name" name="name" />
      <input type="hidden" id="qe_portal" name="portal" />
      <input type="hidden" id="qe_mac" name="mac" />
      <input type="hidden" id="qe_hls_port" name="hls_port" />
      <input type="hidden" id="qe_proxy_port" name="proxy_port" />
      <div class="form-row two">
        <div><label>Username</label><input id="qe_username" name="username" /></div>
        <div><label>Password</label><input id="qe_password" name="password" type="password" /></div>
      </div>
      <div class="form-row two">
        <div><label>STB Model</label><input id="qe_model" name="model" placeholder="MAG254" /></div>
        <div><label>Serial</label><input id="qe_serial_number" name="serial_number" placeholder="0000000000000" /></div>
      </div>
      <label>Device ID</label><input id="qe_device_id" name="device_id" maxlength="64" />
      <label style="margin-top:10px">Device ID 2</label><input id="qe_device_id2" name="device_id2" maxlength="64" />
      <label style="margin-top:10px">Signature</label><input id="qe_signature" name="signature" maxlength="64" />
      <div class="form-row two" style="margin-top:10px">
        <div><label>Timezone</label><input id="qe_timezone" name="timezone" placeholder="UTC" /></div>
        <div><label>Watchdog</label><input id="qe_watchdog_time" name="watchdog_time" inputmode="numeric" placeholder="5" /></div>
      </div>
      <div class="modal-actions">
        <button type="button" id="qeCancel" class="btn btn-ghost">Cancel</button>
        <button type="submit" class="btn btn-primary"><i class="fa-regular fa-floppy-disk"></i> Save Changes</button>
      </div>
    </form>
  </div>
</div>

<script>
var macRe = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;

// Auto-detect browser timezone and set it in the profile form
var userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
if (userTimezone) {
  document.getElementById('timezone').value = userTimezone;
  document.getElementById('qe_timezone').value = userTimezone;
}

function normalizePortal(raw) {
  var s = (raw || '').trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try {
    var u = new URL(s);
    var p = (u.pathname || '/').trim().toLowerCase();
    if (!p || p === '/') { u.pathname = '/portal.php'; }
    else if (!/\/(portal|load)\.php$/i.test(p)) {
      if (/\.php$/i.test(p)) {
        var d = p.substring(0, p.lastIndexOf('/')) || '/';
        u.pathname = d + '/portal.php';
      } else {
        u.pathname = p.replace(/\/+$/, '') + '/portal.php';
      }
    }
    return u.toString();
  } catch (e) { return s; }
}

function showToast(t, m) {
  var el = document.getElementById('toast');
  document.getElementById('toastTitle').textContent = t;
  document.getElementById('toastMsg').textContent = m;
  el.style.display = 'block';
  clearTimeout(window.__tt);
  window.__tt = setTimeout(function () { el.style.display = 'none'; }, 3800);
}

async function postForm(url, data) {
  var fd = new URLSearchParams();
  for (var k in data) fd.append(k, data[k]);
  return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: fd });
}

function copyLink(e, el) {
  e.preventDefault();
  var t = el.getAttribute('data-copy') || '';
  if (!t) return;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(t).then(function () { showToast('Copied', t); });
  } else {
    var ta = document.createElement('textarea');
    ta.value = t; ta.style.position = 'fixed'; ta.style.top = '-1000px';
    document.body.appendChild(ta); ta.select();
    document.execCommand('copy'); document.body.removeChild(ta);
    showToast('Copied', t);
  }
}

// Tab switching
document.querySelectorAll('.tab').forEach(function(tab) {
  tab.addEventListener('click', function() {
    document.querySelectorAll('.tab').forEach(function(t) { t.classList.remove('active'); });
    document.querySelectorAll('.section').forEach(function(s) { s.classList.remove('active'); });
    tab.classList.add('active');
    document.getElementById('section-' + tab.dataset.tab).classList.add('active');
  });
});

if (document.querySelectorAll('.profile-card').length > 0) {
  document.querySelector('.tab[data-tab="manage"]').click();
}

// Form validation
document.getElementById('addForm').addEventListener('submit', function (e) {
  // Ensure timezone is always set to current browser timezone
  var detectedTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (detectedTz) document.getElementById('timezone').value = detectedTz;
  var v = normalizePortal(document.getElementById('portal').value || '');
  document.getElementById('portal').value = v;
  var m = (document.getElementById('mac').value || '').trim().toUpperCase();
  document.getElementById('mac').value = m;
  var ok = true;
  if (!/^https?:\/\//i.test(v) || !/\/(portal|load)\.php(\?.*)?$/i.test(v)) {
    document.getElementById('portalErr').style.display = 'block'; ok = false;
  } else { document.getElementById('portalErr').style.display = 'none'; }
  if (!macRe.test(m)) {
    document.getElementById('macErr').style.display = 'block'; ok = false;
  } else { document.getElementById('macErr').style.display = 'none'; }
  if (!ok) { e.preventDefault(); showToast('Fix required fields', 'Please correct Portal URL and MAC format.'); }
});

function resetEdit() {
  document.getElementById('edit_id').value = '';
  document.getElementById('saveBtn').innerHTML = '<i class="fa-regular fa-floppy-disk"></i> <span>Save Profile</span>';
  document.getElementById('cancelEdit').style.display = 'none';
}
document.getElementById('cancelEdit').addEventListener('click', function () {
  resetEdit();
  document.getElementById('addForm').reset();
  showToast('Edit canceled', 'Form reset.');
});

document.getElementById('profiles').addEventListener('click', function (e) {
  var btn = e.target.closest ? e.target.closest('button[data-action="edit"]') : null;
  if (!btn) return;
  var card = btn.closest('.profile-card');
  if (!card) return;
  fetch('/api/profiles/stop', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'id=' + encodeURIComponent(card.getAttribute('data-id') || '') }).catch(function () {});
  document.getElementById('edit_id').value = card.getAttribute('data-id') || '';
  ['name', 'portal', 'mac', 'hls_port', 'proxy_port'].forEach(function (f) { document.getElementById(f).value = card.getAttribute('data-' + f) || ''; });
  ['model', 'serial_number', 'device_id', 'device_id2', 'signature', 'timezone', 'username', 'watchdog_time'].forEach(function (f) {
    var el = document.getElementById(f);
    if (el) el.value = card.getAttribute('data-' + f) || '';
  });
  document.getElementById('password').value = '';
  document.getElementById('saveBtn').innerHTML = '<i class="fa-regular fa-floppy-disk"></i> <span>Save Changes</span>';
  document.getElementById('cancelEdit').style.display = 'inline-flex';
  showToast('Editing profile', 'Stopped the running playlist. Make changes and save.');
  document.querySelector('.tab[data-tab="create"]').click();
  window.scrollTo({ top: 0, behavior: 'smooth' });
});

// Quick Edit modal
var qeModal = document.getElementById('qeModal');
document.getElementById('profiles').addEventListener('click', function (e) {
  var btn = e.target.closest ? e.target.closest('button[data-action="quickedit"]') : null;
  if (!btn) return;
  var card = btn.closest('.profile-card');
  if (!card) return;
  var id = card.getAttribute('data-id') || '';
  if (id) fetch('/api/profiles/stop', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'id=' + encodeURIComponent(id) }).catch(function () {});
  document.getElementById('qe_edit_id').value = id;
  document.getElementById('qe_name').value = card.getAttribute('data-name') || '';
  document.getElementById('qe_portal').value = card.getAttribute('data-portal') || '';
  document.getElementById('qe_mac').value = card.getAttribute('data-mac') || '';
  document.getElementById('qe_hls_port').value = card.getAttribute('data-hls') || '';
  document.getElementById('qe_proxy_port').value = card.getAttribute('data-proxy') || '';
  document.getElementById('qe_username').value = card.getAttribute('data-username') || '';
  document.getElementById('qe_password').value = '';
  document.getElementById('qe_model').value = card.getAttribute('data-model') || '';
  document.getElementById('qe_serial_number').value = card.getAttribute('data-serial') || '';
  document.getElementById('qe_device_id').value = card.getAttribute('data-deviceid') || '';
  document.getElementById('qe_device_id2').value = card.getAttribute('data-deviceid2') || '';
  document.getElementById('qe_signature').value = card.getAttribute('data-signature') || '';
  document.getElementById('qe_timezone').value = card.getAttribute('data-timezone') || '';
  document.getElementById('qe_watchdog_time').value = card.getAttribute('data-watchdog') || '';
  qeModal.classList.add('open');
  showToast('Quick Edit', 'Profile stopped. Make changes and save.');
});
document.getElementById('qeCancel').addEventListener('click', function () { qeModal.classList.remove('open'); });
qeModal.addEventListener('click', function (e) { if (e.target === qeModal) qeModal.classList.remove('open'); });
document.getElementById('qeForm').addEventListener('submit', function () {
  var detectedTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (detectedTz) document.getElementById('qe_timezone').value = detectedTz;
  qeModal.classList.remove('open');
});

// Status polling
async function poll() {
  try {
    var r = await fetch('/api/profile_status', { cache: 'no-store' });
    var a = await r.json();
    for (var i = 0; i < a.length; i++) {
      var s = a[i];
      var badge = document.getElementById('badge-' + s.id);
      var meta = document.getElementById('meta-' + s.id);
      var startBtn = document.getElementById('startbtn-' + s.id);
      var badgeIcon = document.getElementById('badge-icon-' + s.id);
      var badgeText = document.getElementById('badge-text-' + s.id);
      if (!badge || !meta) continue;
      badge.className = 'badge';
      if (s.phase === 'success') badge.classList.add('ok');
      if (s.phase === 'error') badge.classList.add('err');
      if (s.running) badge.classList.add('run');
      var text = s.busy ? 'Starting...' : (s.running ? 'Running' : (s.phase === 'success' ? (s.message || 'Ready') : (s.phase === 'error' ? 'Error' : (s.phase === 'validating' ? 'Working...' : 'Idle'))));
      badgeText.textContent = text;
      if (badgeIcon) badgeIcon.className = 'fa-solid fa-' + (s.running ? 'play' : (s.phase === 'error' ? 'exclamation-triangle' : 'pause'));
      var lines = [];
      if (s.message) lines.push('<div><i class="fa-solid fa-info-circle"></i> ' + s.message.replace(/</g,'&lt;') + '</div>');
      if (s.channelsCount) lines.push('<div><i class="fa-solid fa-satellite-dish"></i> Channels: ' + s.channelsCount + '</div>');
      meta.innerHTML = lines.join('');
      if (startBtn) { startBtn.disabled = !!s.busy || !!s.running; }
    }
  } catch (e) {}
}
setInterval(poll, 1500); poll();
</script>
</body>
</html>"""
}

fun renderFiltersHtml(profileId: Int, profiles: List<ProfileConfig> = emptyList()): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Stalkerhek Filters</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--ok:#3fb970;--warn:#d4a94a;--bad:#e85d4d;--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:1200px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s}
.card:hover{border-color:var(--border-light)}
.card-title{font-size:17px;font-weight:700;margin-bottom:4px;display:flex;align-items:center;gap:8px}
.card-sub{color:var(--muted);font-size:13px;margin-bottom:12px}
.tabs{display:flex;gap:4px;padding:4px;background:var(--surface);border:1px solid var(--border);border-radius:14px;overflow-x:auto;flex-shrink:0;margin-bottom:4px}
.tab{display:flex;align-items:center;gap:8px;padding:10px 18px;border-radius:10px;border:none;background:transparent;color:var(--muted);font-size:13px;font-weight:600;cursor:pointer;white-space:nowrap;transition:all .15s;font-family:var(--font)}
.tab:hover{color:var(--text);background:var(--surface2)}
.tab.active{background:var(--brand-glow);color:var(--brand);box-shadow:inset 0 0 0 1px rgba(45,138,78,.25)}
.tab i{font-size:14px}
.section{display:none;animation:fadeIn .25s ease}
.section.active{display:block}
@keyframes fadeIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:translateY(0)}}
label{display:block;font-size:12px;font-weight:600;color:var(--muted);margin:14px 0 5px;text-transform:uppercase;letter-spacing:.4px}
input,select{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--border);background:var(--bg);color:var(--text);outline:none;font-size:14px;transition:border-color .2s,box-shadow .2s;font-family:var(--font)}
input:focus,select:focus{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-glow)}
select option{background:var(--surface);color:var(--text)}
.row{display:flex;gap:12px;flex-wrap:wrap;align-items:center}
.pill{display:inline-flex;align-items:center;gap:6px;padding:5px 10px;border:1px solid var(--border);border-radius:999px;color:var(--muted);font-size:11px;font-weight:600;white-space:nowrap}
.pill.ok{border-color:rgba(63,185,112,.3);color:var(--ok)}
.pill.bad{border-color:rgba(232,93,77,.35);color:var(--bad)}
.pill.mix{border-color:rgba(212,169,74,.45);color:var(--warn)}
.btn{display:inline-flex;align-items:center;gap:8px;padding:9px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface2);color:var(--text);font-size:12px;font-weight:600;cursor:pointer;transition:all .15s;font-family:var(--font);white-space:nowrap;text-decoration:none}
.btn:active{transform:scale(.97)}
.btn-primary{background:rgba(45,138,78,.15);border-color:var(--brand);color:var(--brand)}
.btn-primary:hover{background:rgba(45,138,78,.25);border-color:#3dba68}
.btn-danger{background:rgba(232,93,77,.1);border-color:rgba(232,93,77,.25);color:var(--bad)}
.btn-danger:hover{background:rgba(232,93,77,.2);border-color:var(--bad)}
.btn-ghost{background:transparent;color:var(--muted)}
.btn-ghost:hover{background:var(--surface2);color:var(--text);border-color:var(--border-light)}
.btn:disabled{opacity:.45;cursor:not-allowed;transform:none}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono","Courier New",monospace}
.small{color:var(--muted);font-size:12px;margin-top:4px}
.grid{display:grid;grid-template-columns:1fr;gap:14px;align-items:start}
@media(min-width:980px){.grid{grid-template-columns:minmax(280px,360px) minmax(0,1fr)}}
.list{display:grid;gap:8px}
.item{border:1px solid var(--border);border-radius:14px;background:var(--surface2);padding:12px 14px;display:flex;justify-content:space-between;gap:10px;align-items:center;min-width:0;cursor:pointer;transition:all .15s}
.item:hover{border-color:var(--border-light)}
.item .name{font-weight:700;font-size:13px}
.tableWrap{overflow:auto;max-width:100%;border:1px solid var(--border);border-radius:12px}
table{width:100%;border-collapse:collapse}
th{background:rgba(31,46,35,.25);padding:10px 12px;color:#cfe0cf;font-size:12px;text-transform:uppercase;letter-spacing:.6px;text-align:left;font-weight:600;border-bottom:1px solid var(--border)}
td{padding:10px 12px;border-top:1px solid rgba(31,46,35,.55);font-size:13px;vertical-align:middle}
tr{cursor:pointer;transition:background .1s}
tr:hover{background:rgba(45,138,78,.06)}
tr.active{background:rgba(45,138,78,.1)}
.ck{display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;position:relative}
.ck input{appearance:none;-webkit-appearance:none;width:16px;height:16px;border-radius:5px;border:1px solid rgba(31,46,35,.9);background:rgba(13,20,16,.55);cursor:pointer;margin:0;padding:0}
.ck input:checked{background:rgba(45,138,78,.25);border-color:rgba(45,138,78,.85)}
.ck input:checked::after{content:"";position:absolute;left:5px;top:2px;width:4px;height:8px;border:2px solid #bfffd3;border-top:0;border-left:0;transform:rotate(45deg)}
.f2{flex:1;min-width:0}
.err-banner{display:none;border:1px solid rgba(232,93,77,.45);background:rgba(232,93,77,.08);border-radius:12px;padding:14px}
.err-banner .title{font-weight:700;font-size:13px;margin-bottom:4px}
.err-banner .msg{color:var(--muted);font-size:12px}
.toast{position:fixed;top:calc(16px + env(safe-area-inset-top));left:50%;transform:translateX(-50%);z-index:100;background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:12px 18px;display:none;box-shadow:0 12px 40px rgba(0,0,0,.5);min-width:280px;max-width:calc(100vw - 32px);animation:slideDown .2s ease}
@keyframes slideDown{from{opacity:0;transform:translateX(-50%) translateY(-12px)}to{opacity:1;transform:translateX(-50%) translateY(0)}}
.toast-title{font-weight:700;font-size:13px}
.toast-msg{color:var(--muted);font-size:12px;margin-top:2px}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
.chip{display:inline-flex;gap:8px;align-items:center;padding:6px 10px;border-radius:999px;border:1px solid rgba(31,46,35,.7);background:rgba(13,20,16,.58);color:#cfe0cf;font-size:12px}
.chip button{padding:4px 6px;border-radius:999px;font-size:11px;background:transparent;color:var(--muted);border:1px solid var(--border);cursor:pointer;display:inline-flex;align-items:center}
.chip button:hover{color:var(--text);border-color:var(--border-light)}
.drawerBack{position:fixed;inset:0;background:rgba(0,0,0,.55);display:none;z-index:60}
.drawer{position:fixed;top:0;right:0;height:100dvh;width:min(420px,92vw);background:linear-gradient(180deg,rgba(17,24,21,.98),rgba(13,20,16,.98));border-left:1px solid var(--border);box-shadow:-18px 0 48px rgba(0,0,0,.45);display:none;z-index:61;padding:16px;overflow:auto}
.drawer.open,.drawerBack.open{display:block}
.drawer h2{margin:0 0 4px 0;font-size:16px}
.drawer .sub{color:var(--muted);font-size:13px;margin-bottom:12px}
.drawer .kv{display:grid;gap:6px;margin-top:12px}
.drawer .kv .k{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.6px}
.drawer .kv .v{font-size:13px;overflow-wrap:anywhere}
.drawer .btnrow{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}
.rename-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:4px}
@media(max-width:600px){.rename-grid{grid-template-columns:1fr}.nav-links .nav-link span{display:none}}
.skeleton{display:grid;gap:8px}
.skel-item{height:48px;border-radius:14px;background:rgba(31,46,35,.2);animation:pulse 1.5s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--border-light)}
</style>
</head>
<body>
<div id="toast" class="toast"><div class="toast-title" id="toastTitle"></div><div class="toast-msg" id="toastMsg"></div></div>
<div id="drawerBack" class="drawerBack"></div>
<div id="drawer" class="drawer" role="dialog" aria-modal="true" tabindex="-1">
  <div class="row" style="justify-content:space-between;align-items:center;margin-bottom:8px"><div style="font-weight:700">Channel Details</div><button class="btn btn-ghost" id="drawerClose" type="button"><i class="fa-solid fa-xmark"></i> Close</button></div>
  <h2 id="dTitle"></h2>
  <div class="sub" id="dSub"></div>
  <div class="kv">
    <div class="k">Genre</div><div class="v" id="dGenre"></div>
    <div class="k">CMD</div><div class="v mono" id="dCmd"></div>
    <div class="k">Status</div><div class="v" id="dState"></div>
  </div>
  <div class="btnrow">
    <button class="btn btn-primary" id="dToggle" type="button"></button>
    <button class="btn btn-ghost" id="dCopy" type="button"><i class="fa-regular fa-copy"></i> Copy CMD</button>
  </div>
</div>
<div class="wrap">
  <div class="top-bar">
    <div class="logo">
      <svg width="36" height="36" viewBox="0 0 96 96" style="flex-shrink:0"><circle cx="48" cy="48" r="46" fill="#080C09"/><circle cx="48" cy="48" r="38" fill="#2D8A4E"/><circle cx="48" cy="48" r="32" fill="#080C09"/><polygon points="40,36 40,60 60,48" fill="#2D8A4E"/></svg>
      <h1>Stalkerhek</h1>
    </div>
    <div class="nav-links">
      <a class="nav-link" href="/dashboard"><i class="fa-solid fa-arrow-left"></i><span>Dashboard</span></a>
    </div>
  </div>

  <div class="tabs" role="tablist">
    <button class="tab active" role="tab" data-tab="channels"><i class="fa-solid fa-tv"></i> Channels</button>
    <button class="tab" role="tab" data-tab="vod"><i class="fa-solid fa-film"></i> VOD</button>
    <button class="tab" role="tab" data-tab="series"><i class="fa-solid fa-list"></i> Series</button>
    <button class="tab" role="tab" data-tab="rename"><i class="fa-solid fa-pen"></i> Rename</button>
  </div>

  <div id="errBanner" class="err-banner"><div class="title">Something went wrong</div><div class="msg" id="errMsg"></div></div>

  <!-- Channels tab -->
  <div class="section active" id="section-channels">
    <div class="card">
      <div class="card-title"><i class="fa-solid fa-filter"></i> Channel Filters</div>
      <div class="card-sub">Per-profile channel filtering. Changes apply immediately.</div>
      <div class="row" style="gap:10px;flex-wrap:wrap">
        <label style="margin:0;text-transform:none;letter-spacing:0;font-size:13px">Profile</label>
        <select id="profileSel" style="width:auto;padding:8px 12px;font-size:13px">${profiles.joinToString("") { p -> """<option value="${p.id}" ${if (p.id == profileId) "selected" else ""}>${p.name.ifEmpty { "Profile ${p.id}" }}</option>""" }}</select>
        <div class="f2"></div>
        <button class="btn btn-ghost" id="reloadBtn" type="button"><i class="fa-solid fa-rotate"></i> Reload</button>
        <button class="btn btn-danger" id="resetBtn" type="button"><i class="fa-solid fa-eraser"></i> Reset</button>
      </div>
      <div id="chips" class="chips" style="display:none"></div>
    </div>

    <div class="grid">
      <div class="card" style="padding:14px">
        <div class="card-sub" style="margin-bottom:10px;font-size:13px;font-weight:600;color:var(--text)"><i class="fa-solid fa-tags"></i> Genres</div>
        <input id="genreSearch" placeholder="Search genres..." style="margin-bottom:10px;padding:10px 12px;font-size:13px" />
        <div class="row" style="gap:8px;margin-bottom:10px;flex-wrap:wrap">
          <button class="btn btn-ghost" id="genreSelAll" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square-check"></i> All</button>
          <button class="btn btn-ghost" id="genreSelNone" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square"></i> None</button>
          <button class="btn btn-ghost" id="genreEnable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye"></i> Enable</button>
          <button class="btn btn-ghost" id="genreDisable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye-slash"></i> Disable</button>
          <span class="pill" id="genreSelCount" style="font-size:11px">0</span>
        </div>
        <div class="list" id="genreList"><div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div></div>
      </div>

      <div>
        <div class="card" style="padding:14px">
          <div class="row" style="justify-content:space-between;margin-bottom:10px">
            <div class="card-sub" style="margin-bottom:0;font-size:13px;font-weight:600;color:var(--text)"><i class="fa-solid fa-list"></i> Channels</div>
            <span class="pill" id="countPill" style="font-size:11px">0</span>
          </div>
          <div class="row" style="gap:8px;margin-bottom:10px;flex-wrap:wrap">
            <input id="q" placeholder="Search..." style="flex:1;min-width:140px;padding:10px 12px;font-size:13px" />
            <select id="state" style="width:auto;padding:8px 12px;font-size:13px"><option value="all">All</option><option value="enabled">Enabled</option><option value="disabled">Disabled</option></select>
          </div>
          <div class="row" style="gap:8px;margin-bottom:10px;flex-wrap:wrap">
            <button class="btn btn-ghost" id="selAll" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square-check"></i> All</button>
            <button class="btn btn-ghost" id="selNone" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square"></i> None</button>
            <button class="btn btn-ghost" id="bulkEnable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye"></i> Enable</button>
            <button class="btn btn-ghost" id="bulkDisable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye-slash"></i> Disable</button>
            <span class="pill" id="selCount" style="font-size:11px">0</span>
          </div>
          <div class="tableWrap" tabindex="0" role="grid" aria-label="Channels">
            <table><thead><tr><th style="width:36px">Sel</th><th>Channel</th><th>Genre</th><th style="width:90px;text-align:right">Status</th></tr></thead><tbody id="rows"></tbody></table>
          </div>
          <div class="small" style="margin-top:8px">Up/Down navigate, Enter details, Space toggle, Esc clear selection</div>
        </div>
      </div>
    </div>
  </div>

  <!-- VOD tab -->
  <div class="section" id="section-vod">
    <div class="card">
      <div class="card-title"><i class="fa-solid fa-tags"></i> VOD Categories</div>
      <div class="card-sub">Enable/disable VOD categories.</div>
      <input id="vodGenreSearch" placeholder="Search categories..." style="margin-bottom:10px;padding:10px 12px;font-size:13px" />
      <div class="row" style="gap:8px;margin-bottom:10px;flex-wrap:wrap">
        <button class="btn btn-ghost" id="vodGenreSelAll" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square-check"></i> All</button>
        <button class="btn btn-ghost" id="vodGenreSelNone" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square"></i> None</button>
        <button class="btn btn-ghost" id="vodGenreEnable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye"></i> Enable</button>
        <button class="btn btn-ghost" id="vodGenreDisable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye-slash"></i> Disable</button>
        <span class="pill" id="vodGenreSelCount" style="font-size:11px">0</span>
      </div>
      <div class="list" id="vodGenreList"><div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div></div>
    </div>
  </div>

  <!-- Series tab -->
  <div class="section" id="section-series">
    <div class="card">
      <div class="card-title"><i class="fa-solid fa-tags"></i> Series Categories</div>
      <div class="card-sub">Enable/disable Series categories.</div>
      <input id="seriesGenreSearch" placeholder="Search categories..." style="margin-bottom:10px;padding:10px 12px;font-size:13px" />
      <div class="row" style="gap:8px;margin-bottom:10px;flex-wrap:wrap">
        <button class="btn btn-ghost" id="seriesGenreSelAll" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square-check"></i> All</button>
        <button class="btn btn-ghost" id="seriesGenreSelNone" type="button" style="padding:6px 10px;font-size:11px"><i class="fa-regular fa-square"></i> None</button>
        <button class="btn btn-ghost" id="seriesGenreEnable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye"></i> Enable</button>
        <button class="btn btn-ghost" id="seriesGenreDisable" type="button" disabled style="padding:6px 10px;font-size:11px"><i class="fa-solid fa-eye-slash"></i> Disable</button>
        <span class="pill" id="seriesGenreSelCount" style="font-size:11px">0</span>
      </div>
      <div class="list" id="seriesGenreList"><div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div></div>
    </div>
  </div>

  <!-- Rename tab -->
  <div class="section" id="section-rename">
    <div class="card">
      <div class="card-title"><i class="fa-solid fa-pen"></i> Channel Renaming</div>
      <div class="card-sub">Remove prefixes/suffixes from channel names.</div>
      <div class="rename-grid">
        <div>
          <label for="renamePrefix">Remove Prefix</label>
          <input id="renamePrefix" placeholder="e.g. HD_ " style="font-family:var(--font)" />
          <div class="small">Removed from start of channel names.</div>
        </div>
        <div>
          <label for="renameSuffix">Remove Suffix</label>
          <input id="renameSuffix" placeholder="e.g. _FHD" style="font-family:var(--font)" />
          <div class="small">Removed from end of channel names.</div>
        </div>
      </div>
      <div class="row" style="margin-top:14px;gap:10px">
        <button class="btn btn-primary" id="saveRename"><i class="fa-regular fa-floppy-disk"></i> Save Rename Rules</button>
        <span class="small" id="renameStatus" style="margin:0"></span>
      </div>
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-title"><i class="fa-solid fa-tag"></i> Genre / Group Renaming</div>
      <div class="card-sub">Rename genre/group names.</div>
      <div class="rename-grid">
        <div>
          <label for="genreRenameSelect">Select Genre</label>
          <select id="genreRenameSelect" style="font-family:var(--font)"><option value="">-- Select --</option></select>
        </div>
        <div>
          <label for="genreRenameName">New Name</label>
          <div class="row" style="gap:8px;flex-wrap:nowrap">
            <input id="genreRenameName" placeholder="Enter new genre name" style="flex:1;font-family:var(--font)" />
            <button class="btn btn-primary" id="saveGenreRename" style="flex-shrink:0"><i class="fa-regular fa-floppy-disk"></i></button>
          </div>
          <div class="small">Leave empty and save to remove rename.</div>
        </div>
      </div>
      <div id="genreRenamesList" class="chips" style="margin-top:10px"></div>
    </div>
  </div>
</div>

<script>
const _=id=>document.getElementById(id);
const toast=(t,m)=>{_('toastTitle').textContent=t;_('toastMsg').textContent=m;_('toast').style.display='block';clearTimeout(window.__tt);window.__tt=setTimeout(()=>{try{_('toast').style.display='none'}catch(e){}},2400)};
const postForm=async(url,obj)=>{const fd=new URLSearchParams();Object.keys(obj||{}).forEach(k=>fd.append(k,obj[k]));const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:fd});if(!r.ok)throw new Error((await r.text())||r.statusText);return r.headers.get('content-type')?.includes('json')?r.json():r.text()};
const showErr=m=>{_('errMsg').textContent=m||'Unknown error';_('errBanner').style.display='block'};
const clearErr=()=>{_('errBanner').style.display='none';_('errMsg').textContent=''};
let st={pid:$profileId,genreId:'',genreName:'',q:'',view:'all',selected:new Set(),genreSelected:new Set(),genres:[],items:[],renamePrefix:'',renameSuffix:'',dState:true};
let debTimer=null;

// Tab switching
document.querySelectorAll('.tab').forEach(t=>{t.addEventListener('click',()=>{
  document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));
  document.querySelectorAll('.section').forEach(x=>x.classList.remove('active'));
  t.classList.add('active');
  const tab=t.dataset.tab;
  _('section-'+tab).classList.add('active');
  if(tab==='channels') loadGenres();
  if(tab==='vod') loadVodGenres();
  if(tab==='series') loadSeriesGenres();
  if(tab==='rename') loadRenameRules();
})});

const loadGenres=async()=>{
  _('genreList').innerHTML='<div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div>';
  st.pid=Number(_('profileSel').value||0);
  try{
    const arr=await(await fetch('/api/filters/genres?id='+st.pid,{cache:'no-store'})).json();
    st.genres=Array.isArray(arr)?arr:[];
    renderGenres(st.genres);
  }catch(e){showErr(e.message);_('genreList').innerHTML=''}
};

const renderGenres=arr=>{
  const q=(_('genreSearch').value||'').toLowerCase().trim();
  _('genreList').innerHTML='';
  _('genreSelCount').textContent=st.genreSelected.size+' sel';
  _('genreEnable').disabled=!st.genreSelected.size;
  _('genreDisable').disabled=!st.genreSelected.size;
  (arr||[]).forEach(g=>{
    const n=g.name||'Other';
    const gid=g.genreId||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=st.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)st.genreSelected.add(gid);else st.genreSelected.delete(gid);renderGenres(st.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n+'</div><div class="small">'+(g.enabled||0)+' en / '+(g.total||0)+' tot</div>';
    left.appendChild(cw);left.appendChild(info);
    const pill=document.createElement('div');
    pill.className='pill '+(g.disabled?'bad':'ok');
    pill.textContent=g.disabled?'Disabled':'Enabled';
    row.onclick=()=>{
      st.genreId=gid;st.genreName=n;st.selected=new Set();
      loadChannels();
    };
    row.appendChild(left);row.appendChild(pill);_('genreList').appendChild(row);
  });
  if(!_('genreList').children.length)_('genreList').innerHTML='<div class="small" style="padding:12px;text-align:center">No matching genres</div>';
};

const loadChannels=async()=>{
  _('rows').innerHTML='<tr><td colspan="4" style="text-align:center;padding:20px;color:var(--muted)">Loading...</td></tr>';
  const u=new URLSearchParams({id:String(st.pid)});
  if(st.genreId)u.set('genre_id',st.genreId);
  if(st.q)u.set('query',st.q);
  if(st.view)u.set('state',st.view);
  u.set('offset','0');u.set('limit','5000');
  try{
    const j=await(await fetch('/api/filters/channels?'+u.toString(),{cache:'no-store'})).json();
    const items=j&&Array.isArray(j.items)?j.items:[];
    st.items=items;
    _('countPill').textContent=(j.total||0)+' total · '+items.length+' shown';
    renderChannels(items);
  }catch(e){showErr(e.message);_('rows').innerHTML='<tr><td colspan="4" style="text-align:center;padding:20px;color:var(--muted)">Error loading channels</td></tr>'}
};

const renderChannels=arr=>{
  _('rows').innerHTML='';
  _('selCount').textContent=st.selected.size+' sel';
  _('bulkEnable').disabled=!st.selected.size;
  _('bulkDisable').disabled=!st.selected.size;
  (arr||[]).forEach(x=>{
    const tr=document.createElement('tr');
    const sel=document.createElement('td');sel.style.width='36px';sel.style.verticalAlign='middle';
    const cw=document.createElement('span');cw.className='ck';
    const cb=document.createElement('input');cb.type='checkbox';cb.checked=st.selected.has(x.cmd);
    cb.onchange=()=>{if(cb.checked)st.selected.add(x.cmd);else st.selected.delete(x.cmd);renderChannels(st.items)};
    cb.onclick=e=>e.stopPropagation();
    cw.appendChild(cb);sel.appendChild(cw);
    const name=document.createElement('td');
    name.innerHTML='<div style="font-weight:600;font-size:13px">'+x.title+'</div><div style="font-size:11px;color:var(--muted);margin-top:2px">'+x.cmd+'</div>';
    const gen=document.createElement('td');
    gen.innerHTML='<span class="small">'+(x.genre||'')+'</span>';
    const stat=document.createElement('td');stat.style.textAlign='right';
    const p=document.createElement('span');p.className='pill '+(x.enabled?'ok':'bad');p.textContent=x.enabled?'Enabled':'Disabled';
    stat.appendChild(p);
    tr.onclick=()=>{
      _('dTitle').textContent=x.title;_('dSub').textContent='Genre: '+(x.genre||'');
      _('dGenre').textContent=x.genre||'';_('dCmd').textContent=x.cmd;
      st.dState=x.enabled;_('dState').textContent=x.enabled?'Enabled':'Disabled';
      _('dToggle').textContent=x.enabled?'Disable':'Enable';
      _('dToggle').className='btn '+(x.enabled?'btn-danger':'btn-primary');
      _('drawer').classList.add('open');_('drawerBack').classList.add('open');
    };
    tr.appendChild(sel);tr.appendChild(name);tr.appendChild(gen);tr.appendChild(stat);_('rows').appendChild(tr);
  });
  if(!_('rows').children.length)_('rows').innerHTML='<tr><td colspan="4" style="text-align:center;padding:20px;color:var(--muted)">No channels match your filters</td></tr>';
};

// Keyboard nav
_('rows').parentElement?.addEventListener('keydown',e=>{
  const rows=Array.from(_('rows').children);const cur=rows.findIndex(r=>r.classList.contains('active'));let idx=cur;
  if(e.key==='ArrowDown')idx=Math.min(cur+1,rows.length-1);
  else if(e.key==='ArrowUp')idx=Math.max(cur-1,0);
  else if(e.key==='Enter'&&cur>=0)rows[cur]?.click();
  else if(e.key===' '){e.preventDefault();const cb=rows[cur]?.querySelector('input[type="checkbox"]');if(cb)cb.checked=!cb.checked;return}
  else if(e.key==='Escape'){st.selected.clear();renderChannels(st.items);return}
  if(idx!==cur&&idx>=0)rows.forEach((r,i)=>{r.classList.toggle('active',i===idx);if(i===idx)r.scrollIntoView({block:'nearest'})});
});

// Drawer
_('drawerClose').onclick=()=>{_('drawer').classList.remove('open');_('drawerBack').classList.remove('open')};
_('drawerBack').onclick=()=>{_('drawer').classList.remove('open');_('drawerBack').classList.remove('open')};
_('dToggle').onclick=async()=>{
  const cmd=_('dCmd')?.textContent||'';
  if(!cmd)return;
  try{await postForm('/api/filters/toggle_channel',{id:String(st.pid),cmd:cmd,disabled:''+(!st.dState)});toast('Toggled',cmd)}catch(e){toast('Error',e.message||'')}
  _('drawer').classList.remove('open');_('drawerBack').classList.remove('open');loadChannels();
};
_('dCopy').onclick=()=>{const t=_('dCmd')?.textContent||'';if(navigator.clipboard?.writeText)navigator.clipboard.writeText(t);toast('Copied',t)};

// Chips
const renderChips=()=>{
  _('chips').innerHTML='';const chips=[];
  if(st.q)chips.push({k:'Search',v:st.q,clear:()=>{_('q').value='';st.q=''}});
  if(st.view&&st.view!=='all')chips.push({k:'State',v:st.view,clear:()=>{_('state').value='all';st.view='all'}});
  if(st.genreName)chips.push({k:'Genre',v:st.genreName,clear:()=>{st.genreId='';st.genreName='';loadChannels()}});
  if(!chips.length){_('chips').style.display='none';return}
  _('chips').style.display='flex';
  chips.forEach(c=>{const el=document.createElement('div');el.className='chip';const t=document.createElement('div');t.textContent=c.k+': '+c.v;const b=document.createElement('button');b.type='button';b.innerHTML='<i class="fa-solid fa-xmark"></i>';b.onclick=()=>{c.clear();renderChips();loadChannels()};el.appendChild(t);el.appendChild(b);_('chips').appendChild(el)})
};

// Rename rules
const loadRenameRules=async()=>{
  try{
    const j=await(await fetch('/api/filters/rename_rules?id='+st.pid,{cache:'no-store'})).json();
    _('renamePrefix').value=j.renamePrefix||'';
    _('renameSuffix').value=j.renameSuffix||'';
  }catch(e){}
  loadGenreRenames();
};
_('saveRename').onclick=async()=>{
  try{
    await postForm('/api/filters/rename_rules',{id:String(st.pid),renamePrefix:_('renamePrefix').value,renameSuffix:_('renameSuffix').value});
    _('renameStatus').textContent='Saved!';
    toast('Saved','Rename rules updated');
  }catch(e){toast('Error',e.message||'')}
};

// Genre rename
const allGenres=[];
const loadGenreRenames=async()=>{
  try{
    const pid=st.pid;
    const [renames,itvGenres,vodGenres,seriesGenres]=await Promise.all([
      fetch('/api/filters/genre_renames?id='+pid,{cache:'no-store'}).then(r=>r.json()),
      fetch('/api/filters/genres?id='+pid,{cache:'no-store'}).then(r=>r.json()),
      fetch('/api/filters/vod_genres?id='+pid,{cache:'no-store'}).then(r=>r.json()),
      fetch('/api/filters/series_genres?id='+pid,{cache:'no-store'}).then(r=>r.json()),
    ]);
    allGenres.length=0;
    const tag=(arr,tag)=>(Array.isArray(arr)?arr:[]).map(g=>({...g,_tag:tag}));
    allGenres.push(...tag(itvGenres,'TV'));
    allGenres.push(...tag(vodGenres,'VOD'));
    allGenres.push(...tag(seriesGenres,'SERIES'));
    renderGenreRenames(renames.genreRenames||{});
    populateGenreSelect(allGenres);
  }catch(e){console.error(e)}
};

const renderGenreRenames=map=>{
  const keys=Object.keys(map);
  _('genreRenamesList').innerHTML='';
  if(!keys.length){_('genreRenamesList').innerHTML='<div class="small" style="padding:4px 0">No genre renames configured.</div>';return}
  keys.forEach(gid=>{
    const el=document.createElement('div');el.className='chip';
    const t=document.createElement('div');t.innerHTML=(map[gid]||gid)+' <span style="opacity:.5">('+gid+')</span>';
    const b=document.createElement('button');b.type='button';b.innerHTML='<i class="fa-solid fa-xmark"></i>';
    b.onclick=async()=>{
      try{await postForm('/api/filters/rename_genre',{id:String(st.pid),genre_id:gid,name:''});toast('Removed',map[gid]);loadGenreRenames()}catch(e){toast('Error',e.message||'')}
    };
    el.appendChild(t);el.appendChild(b);_('genreRenamesList').appendChild(el);
  });
};

const populateGenreSelect=genres=>{
  const sel=_('genreRenameSelect');const cur=sel.value;
  sel.innerHTML='<option value="">-- Select --</option>';
  const groups={};
  (genres||[]).forEach(g=>{
    const tag=g._tag||'TV';
    if(!groups[tag]){groups[tag]=[]}
    groups[tag].push(g);
  });
  ['TV','VOD','SERIES'].forEach(tag=>{
    if(!groups[tag]||!groups[tag].length)return;
    const grp=document.createElement('optgroup');grp.label=tag;
    groups[tag].forEach(g=>{
      const opt=document.createElement('option');opt.value=g.genreId||'';opt.textContent=g.name||'Other';
      grp.appendChild(opt);
    });
    sel.appendChild(grp);
  });
  sel.value=cur;
};

_('genreRenameSelect').onchange=()=>{
  const gid=_('genreRenameSelect').value;
  if(!gid){_('genreRenameName').value='';return}
  fetch('/api/filters/genre_renames?id='+st.pid,{cache:'no-store'}).then(r=>r.json()).then(j=>{
    _('genreRenameName').value=(j.genreRenames||{})[gid]||'';
  }).catch(()=>{_('genreRenameName').value=''});
};

_('saveGenreRename').onclick=async()=>{
  const gid=_('genreRenameSelect').value;const name=_('genreRenameName').value.trim();
  if(!gid){toast('Error','Select a genre first');return}
  try{
    await postForm('/api/filters/rename_genre',{id:String(st.pid),genre_id:gid,name:name});
    toast('Saved','Genre rename '+(name||'removed'));
    loadGenreRenames();
    _('genreRenameName').value='';
  }catch(e){toast('Error',e.message||'')}
};

// Events
_('profileSel').onchange=()=>{st={...st,pid:Number(_('profileSel').value||0),genreId:'',genreName:'',selected:new Set(),genreSelected:new Set()};loadGenres()};
_('reloadBtn').onclick=()=>{st.genreId='';st.genreName='';loadGenres()};
_('resetBtn').onclick=async()=>{try{await postForm('/api/filters/reset',{id:st.pid});toast('Reset','Filters cleared');loadGenres()}catch(e){toast('Error',e.message||'')}};
_('genreSearch').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>renderGenres(st.genres),200)};
_('q').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>{st.q=_('q').value;renderChips();loadChannels()},280)};
_('state').onchange=()=>{st.view=_('state').value;renderChips();loadChannels()};
_('genreSelAll').onclick=()=>{(st.genres||[]).forEach(g=>st.genreSelected.add(g.genreId||''));renderGenres(st.genres)};
_('genreSelNone').onclick=()=>{st.genreSelected.clear();renderGenres(st.genres)};
_('genreEnable').onclick=async()=>{const ids=Array.from(st.genreSelected);for(const gid of ids){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',ids.length+' genres');st.genreSelected.clear();loadGenres()};
_('genreDisable').onclick=async()=>{const ids=Array.from(st.genreSelected);for(const gid of ids){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',ids.length+' genres');st.genreSelected.clear();loadGenres()};
_('selAll').onclick=()=>{(st.items||[]).forEach(x=>st.selected.add(x.cmd));renderChannels(st.items)};
_('selNone').onclick=()=>{st.selected.clear();renderChannels(st.items)};
_('bulkEnable').onclick=async()=>{const ids=Array.from(st.selected);for(const cmd of ids){try{await postForm('/api/filters/toggle_channel',{id:String(st.pid),cmd:cmd,disabled:'0'})}catch(e){}}toast('Enabled',ids.length+' channels');st.selected.clear();loadChannels()};
_('bulkDisable').onclick=async()=>{const ids=Array.from(st.selected);for(const cmd of ids){try{await postForm('/api/filters/toggle_channel',{id:String(st.pid),cmd:cmd,disabled:'1'})}catch(e){}}toast('Disabled',ids.length+' channels');st.selected.clear();loadChannels()};

// Init
loadGenres();

// VOD tab
const vodSt={genreSelected:new Set(),genres:[]};
const loadVodGenres=async()=>{
  _('vodGenreList').innerHTML='<div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div>';
  try{
    const arr=await(await fetch('/api/filters/vod_genres?id='+st.pid,{cache:'no-store'})).json();
    vodSt.genres=Array.isArray(arr)?arr:[];
    renderVodGenres(vodSt.genres);
  }catch(e){_('vodGenreList').innerHTML=''}
};
const renderVodGenres=arr=>{
  const q=(_('vodGenreSearch').value||'').toLowerCase().trim();
  _('vodGenreList').innerHTML='';
  _('vodGenreSelCount').textContent=vodSt.genreSelected.size+' sel';
  _('vodGenreEnable').disabled=!vodSt.genreSelected.size;
  _('vodGenreDisable').disabled=!vodSt.genreSelected.size;
  (arr||[]).forEach(g=>{
    const n=g.name||'Other';const gid=g.genreId||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=vodSt.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)vodSt.genreSelected.add(gid);else vodSt.genreSelected.delete(gid);renderVodGenres(vodSt.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n+'</div>';
    left.appendChild(cw);left.appendChild(info);
    const pill=document.createElement('div');pill.className='pill '+(g.disabled?'bad':'ok');pill.textContent=g.disabled?'Disabled':'Enabled';
    row.appendChild(left);row.appendChild(pill);_('vodGenreList').appendChild(row);
  });
  if(!_('vodGenreList').children.length)_('vodGenreList').innerHTML='<div class="small" style="padding:12px;text-align:center">No matching categories</div>';
};
_('vodGenreSearch').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>renderVodGenres(vodSt.genres),200)};
_('vodGenreSelAll').onclick=()=>{(vodSt.genres||[]).forEach(g=>vodSt.genreSelected.add(g.genreId||''));renderVodGenres(vodSt.genres)};
_('vodGenreSelNone').onclick=()=>{vodSt.genreSelected.clear();renderVodGenres(vodSt.genres)};
_('vodGenreEnable').onclick=async()=>{for(const gid of Array.from(vodSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',vodSt.genreSelected.size+' categories');vodSt.genreSelected.clear();loadVodGenres()};
_('vodGenreDisable').onclick=async()=>{for(const gid of Array.from(vodSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',vodSt.genreSelected.size+' categories');vodSt.genreSelected.clear();loadVodGenres()};

// Series tab
const seriesSt={genreSelected:new Set(),genres:[]};
const loadSeriesGenres=async()=>{
  _('seriesGenreList').innerHTML='<div class="skeleton"><div class="skel-item"></div><div class="skel-item"></div><div class="skel-item"></div></div>';
  try{
    const arr=await(await fetch('/api/filters/series_genres?id='+st.pid,{cache:'no-store'})).json();
    seriesSt.genres=Array.isArray(arr)?arr:[];
    renderSeriesGenres(seriesSt.genres);
  }catch(e){_('seriesGenreList').innerHTML=''}
};
const renderSeriesGenres=arr=>{
  const q=(_('seriesGenreSearch').value||'').toLowerCase().trim();
  _('seriesGenreList').innerHTML='';
  _('seriesGenreSelCount').textContent=seriesSt.genreSelected.size+' sel';
  _('seriesGenreEnable').disabled=!seriesSt.genreSelected.size;
  _('seriesGenreDisable').disabled=!seriesSt.genreSelected.size;
  (arr||[]).forEach(g=>{
    const n=g.name||'Other';const gid=g.genreId||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=seriesSt.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)seriesSt.genreSelected.add(gid);else seriesSt.genreSelected.delete(gid);renderSeriesGenres(seriesSt.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n+'</div>';
    left.appendChild(cw);left.appendChild(info);
    const pill=document.createElement('div');pill.className='pill '+(g.disabled?'bad':'ok');pill.textContent=g.disabled?'Disabled':'Enabled';
    row.appendChild(left);row.appendChild(pill);_('seriesGenreList').appendChild(row);
  });
  if(!_('seriesGenreList').children.length)_('seriesGenreList').innerHTML='<div class="small" style="padding:12px;text-align:center">No matching categories</div>';
};
_('seriesGenreSearch').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>renderSeriesGenres(seriesSt.genres),200)};
_('seriesGenreSelAll').onclick=()=>{(seriesSt.genres||[]).forEach(g=>seriesSt.genreSelected.add(g.genreId||''));renderSeriesGenres(seriesSt.genres)};
_('seriesGenreSelNone').onclick=()=>{seriesSt.genreSelected.clear();renderSeriesGenres(seriesSt.genres)};
_('seriesGenreEnable').onclick=async()=>{for(const gid of Array.from(seriesSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',seriesSt.genreSelected.size+' categories');seriesSt.genreSelected.clear();loadSeriesGenres()};
_('seriesGenreDisable').onclick=async()=>{for(const gid of Array.from(seriesSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',seriesSt.genreSelected.size+' categories');seriesSt.genreSelected.clear();loadSeriesGenres()};
</script>
</body>
</html>"""
