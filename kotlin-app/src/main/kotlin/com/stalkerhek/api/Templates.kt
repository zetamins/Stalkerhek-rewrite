package com.stalkerhek.api

import com.stalkerhek.models.*

fun renderLoginPage(error: String): String = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stalkerhek Login</title>
<style>
:root{--bg:#0a0f0a;--panel:#0d1410;--border:#1f2e23;--text:#e0e6e0;--muted:#9aaa9a;--brand:#2d7a4e}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Helvetica,Arial,sans-serif;background:linear-gradient(180deg,#0d1410 0%,#0a0f0a 100%);color:var(--text);min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:18px}
.card{max-width:400px;width:100%;border:1px solid var(--border);border-radius:18px;background:rgba(13,20,16,.75);padding:24px;box-shadow:0 18px 48px rgba(0,0,0,.42)}
h1{margin:0 0 8px 0;font-size:22px}
.sub{color:var(--muted);font-size:14px;line-height:1.5;margin-bottom:16px}
label{display:block;font-size:13px;color:#c5d1c5;margin:12px 0 6px}
input{width:100%;padding:14px;border-radius:12px;border:1px solid var(--border);background:#0f1612;color:var(--text);outline:none;font-size:16px}
input:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(45,122,78,.2)}
button{width:100%;cursor:pointer;border:none;border-radius:12px;padding:14px;font-size:15px;font-weight:700;background:var(--brand);color:white;margin-top:16px}
button:hover{background:#3a8f5e}
a{color:var(--brand);text-decoration:none}
.error{color:#e85d4d;font-size:13px;margin-top:8px;padding:10px;border:1px solid rgba(232,93,77,.3);border-radius:8px;background:rgba(232,93,77,.08)}
.links{margin-top:16px;display:flex;gap:12px;justify-content:center;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h1>Stalkerhek</h1>
<div class="sub">Sign in to your management dashboard</div>
${if (error.isNotEmpty()) "<div class=\"error\">$error</div>" else ""}
<form method="post" action="/api/login">
<label for="username">Username</label>
<input id="username" name="username" required minlength="2" autocomplete="username" />
<label for="password">Password</label>
<input id="password" name="password" type="password" required minlength="4" autocomplete="current-password" />
<button type="submit">Sign In</button>
</form>
<div class="links">
<a href="/forgot-password">Forgot password?</a>
<a href="/register">Register</a>
</div>
</div>
</body>
</html>"""

fun renderRegisterPage(error: String): String = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stalkerhek Register</title>
<style>
:root{--bg:#0a0f0a;--panel:#0d1410;--border:#1f2e23;--text:#e0e6e0;--muted:#9aaa9a;--brand:#2d7a4e}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Helvetica,Arial,sans-serif;background:linear-gradient(180deg,#0d1410 0%,#0a0f0a 100%);color:var(--text);min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:18px}
.card{max-width:480px;width:100%;border:1px solid var(--border);border-radius:18px;background:rgba(13,20,16,.75);padding:24px;box-shadow:0 18px 48px rgba(0,0,0,.42)}
h1{margin:0 0 8px 0;font-size:22px}
.sub{color:var(--muted);font-size:14px;line-height:1.5;margin-bottom:16px}
label{display:block;font-size:13px;color:#c5d1c5;margin:12px 0 6px}
input,select{width:100%;padding:14px;border-radius:12px;border:1px solid var(--border);background:#0f1612;color:var(--text);outline:none;font-size:16px}
input:focus,select:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(45,122,78,.2)}
select option{background:#0d1410}
button{width:100%;cursor:pointer;border:none;border-radius:12px;padding:14px;font-size:15px;font-weight:700;background:var(--brand);color:white;margin-top:16px}
button:hover{background:#3a8f5e}
a{color:var(--brand);text-decoration:none}
.error{color:#e85d4d;font-size:13px;margin-top:8px;padding:10px;border:1px solid rgba(232,93,77,.3);border-radius:8px;background:rgba(232,93,77,.08)}
.links{margin-top:16px;text-align:center;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h1>Create Account</h1>
<div class="sub">Register a new management user</div>
${if (error.isNotEmpty()) "<div class=\"error\">$error</div>" else ""}
<form method="post" action="/api/register">
<label for="username">Username</label>
<input id="username" name="username" required minlength="2" autocomplete="username" />
<label for="password">Password</label>
<input id="password" name="password" type="password" required minlength="4" autocomplete="new-password" />
<label for="password_confirm">Confirm Password</label>
<input id="password_confirm" name="password_confirm" type="password" required minlength="4" autocomplete="new-password" />
<label for="security_question">Security Question</label>
<select id="security_question" name="security_question">
<option value="What is your mother's maiden name?">What is your mother's maiden name?</option>
<option value="What was the name of your first pet?">What was the name of your first pet?</option>
<option value="What city were you born in?">What city were you born in?</option>
<option value="What is your favorite book?">What is your favorite book?</option>
<option value="What is your custom security question">Custom question</option>
</select>
<div id="customQ" style="display:none">
<label for="custom_question">Custom Security Question</label>
<input id="custom_question" name="custom_question" placeholder="Enter your custom question" />
</div>
<label for="security_answer">Security Answer</label>
<input id="security_answer" name="security_answer" placeholder="Answer for security question" />
<button type="submit">Register</button>
</form>
<div class="links"><a href="/login">Back to Login</a></div>
</div>
<script>
document.getElementById('security_question').addEventListener('change',function(){
document.getElementById('customQ').style.display=this.value==='What is your custom security question'?'block':'none';
});
</script>
</body>
</html>"""

fun renderForgotPasswordPage(msg: String, user: User?): String {
    val body = if (user != null && user.securityQuestion.isNotEmpty()) {
        """<form method="post" action="/api/forgot-password">
<input type="hidden" name="username" value="${user.username}" />
<label>Security Question</label>
<div style="padding:14px;border-radius:12px;border:1px solid var(--border);background:#0f1612;margin-bottom:12px">${user.securityQuestion}</div>
<label for="answer">Your Answer</label>
<input id="answer" name="answer" required placeholder="Enter your answer" />
<button type="submit">Verify Answer</button>
</form>"""
    } else {
        """<form method="post" action="/api/forgot-password">
<label for="username">Username</label>
<input id="username" name="username" required autocomplete="username" />
<button type="submit">Reset Password</button>
</form>"""
    }
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stalkerhek Forgot Password</title>
<style>
:root{--bg:#0a0f0a;--panel:#0d1410;--border:#1f2e23;--text:#e0e6e0;--muted:#9aaa9a;--brand:#2d7a4e}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Helvetica,Arial,sans-serif;background:linear-gradient(180deg,#0d1410 0%,#0a0f0a 100%);color:var(--text);min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:18px}
.card{max-width:420px;width:100%;border:1px solid var(--border);border-radius:18px;background:rgba(13,20,16,.75);padding:24px;box-shadow:0 18px 48px rgba(0,0,0,.42)}
h1{margin:0 0 8px 0;font-size:22px}
.sub{color:var(--muted);font-size:14px;line-height:1.5;margin-bottom:16px}
label{display:block;font-size:13px;color:#c5d1c5;margin:12px 0 6px}
input{width:100%;padding:14px;border-radius:12px;border:1px solid var(--border);background:#0f1612;color:var(--text);outline:none;font-size:16px}
input:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(45,122,78,.2)}
button{width:100%;cursor:pointer;border:none;border-radius:12px;padding:14px;font-size:15px;font-weight:700;background:var(--brand);color:white;margin-top:16px}
a{color:var(--brand);text-decoration:none}
.msg{color:var(--muted);font-size:13px;margin-top:8px;padding:10px;border:1px solid var(--border);border-radius:8px}
.links{margin-top:16px;text-align:center;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h1>Reset Password</h1>
<div class="sub">Verify your identity to reset your password</div>
${if (msg.isNotEmpty()) "<div class=\"msg\">$msg</div>" else ""}
$body
<div class="links"><a href="/login">Back to Login</a></div>
</div>
</body>
</html>"""
}

fun renderResetPasswordPage(error: String, token: String): String = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stalkerhek Reset Password</title>
<style>
:root{--bg:#0a0f0a;--panel:#0d1410;--border:#1f2e23;--text:#e0e6e0;--muted:#9aaa9a;--brand:#2d7a4e}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Helvetica,Arial,sans-serif;background:linear-gradient(180deg,#0d1410 0%,#0a0f0a 100%);color:var(--text);min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:18px}
.card{max-width:420px;width:100%;border:1px solid var(--border);border-radius:18px;background:rgba(13,20,16,.75);padding:24px;box-shadow:0 18px 48px rgba(0,0,0,.42)}
h1{margin:0 0 8px 0;font-size:22px}
.sub{color:var(--muted);font-size:14px;line-height:1.5;margin-bottom:16px}
label{display:block;font-size:13px;color:#c5d1c5;margin:12px 0 6px}
input{width:100%;padding:14px;border-radius:12px;border:1px solid var(--border);background:#0f1612;color:var(--text);outline:none;font-size:16px}
input:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(45,122,78,.2)}
button{width:100%;cursor:pointer;border:none;border-radius:12px;padding:14px;font-size:15px;font-weight:700;background:var(--brand);color:white;margin-top:16px}
a{color:var(--brand);text-decoration:none}
.error{color:#e85d4d;font-size:13px;margin-top:8px;padding:10px;border:1px solid rgba(232,93,77,.3);border-radius:8px;background:rgba(232,93,77,.08)}
.links{margin-top:16px;text-align:center;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h1>Set New Password</h1>
<div class="sub">Enter your new password below</div>
${if (error.isNotEmpty()) "<div class=\"error\">$error</div>" else ""}
<form method="post" action="/api/reset-password">
<input type="hidden" name="token" value="$token" />
<label for="new_password">New Password</label>
<input id="new_password" name="new_password" type="password" required minlength="4" autocomplete="new-password" />
<label for="confirm_password">Confirm Password</label>
<input id="confirm_password" name="confirm_password" type="password" required minlength="4" autocomplete="new-password" />
<button type="submit">Set Password</button>
</form>
<div class="links"><a href="/login">Back to Login</a></div>
</div>
</body>
</html>"""

fun renderAccountPage(username: String, securityQuestion: String, authEnabled: Boolean, authenticated: Boolean): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Stalkerhek Account</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;display:flex;flex-direction:column;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:600px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));flex:1;display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s;margin-bottom:0}
.card:hover{border-color:var(--border-light)}
.card-title{font-size:17px;font-weight:700;margin-bottom:4px;display:flex;align-items:center;gap:8px}
.card-sub{color:var(--muted);font-size:13px;margin-bottom:16px}
.info{display:grid;gap:6px;margin-top:12px}
.info .row{display:flex;justify-content:space-between;padding:10px 12px;border-bottom:1px solid var(--border);align-items:center}
.info .label{color:var(--muted);font-size:13px}
.info .value{font-size:13px;font-weight:500}
.btn{display:inline-flex;align-items:center;gap:8px;padding:10px 16px;border-radius:10px;border:1px solid var(--border);background:var(--surface2);color:var(--text);font-size:13px;font-weight:600;cursor:pointer;transition:all .15s;font-family:var(--font);text-decoration:none;white-space:nowrap}
.btn:hover{background:var(--surface2);color:var(--text);border-color:var(--border-light)}
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
      <a class="nav-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
    </div>
  </div>
  <div class="card">
    <div class="card-title"><i class="fa-solid fa-user-shield"></i> Account</div>
    <div class="card-sub">Manage your account settings.</div>
    <div class="info">
      <div class="row"><span class="label">Username</span><span class="value">$username</span></div>
      <div class="row"><span class="label">Security Question</span><span class="value">${securityQuestion.ifEmpty { "None set" }}</span></div>
    </div>
    <div style="display:flex;gap:10px;margin-top:18px;flex-wrap:wrap">
      <a class="btn" href="/dashboard"><i class="fa-solid fa-arrow-left"></i> Dashboard</a>
      <a class="btn" href="/api/logout" onclick="event.preventDefault();fetch('/api/logout',{method:'POST'}).then(()=>window.location='/login')"><i class="fa-solid fa-sign-out-alt"></i> Logout</a>
    </div>
  </div>
</div>
</body>
</html>"""

fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

fun renderDashboardPage(profiles: List<Profile>, statuses: List<ProfileStatus>): String {
    val profileCards = if (profiles.isEmpty()) {
        """<div class="empty-state"><i class="fa-solid fa-plus-circle"></i><h3>No Profiles Yet</h3><p class="sub">Create your first profile to get started with IPTV streaming.</p></div>"""
    } else {
        profiles.joinToString("\n") { p ->
            val st = statuses.find { it.id == p.id }
            val phase = st?.phase ?: "idle"
            val message = st?.message ?: ""
            val running = st?.running ?: false
            val busy = st?.busy ?: false
            val channels = st?.channels ?: 0
            val badgeClass = when (phase) {
                "success" -> if (running) "run" else "ok"
                "error" -> "err"
                else -> ""
            }
            val badgeText = when { busy -> "Starting..."; running -> "Running"; phase == "success" -> if (message.isNotEmpty()) message else "Ready"; phase == "error" -> "Error"; phase == "validating" -> "Working..."; else -> "Idle" }
            val badgeIcon = when { running -> "play"; phase == "error" -> "exclamation-triangle"; busy -> "spinner fa-spin"; else -> "pause" }
            """<div class="profile-card" data-id="${p.id}" data-name="${p.name}" data-portal="${p.portalUrl}" data-mac="${p.mac}" data-hls="${p.hlsPort}" data-proxy="${p.proxyPort}" data-model="${p.model}" data-serial="${p.serialNumber}" data-deviceid="${p.deviceId}" data-deviceid2="${p.deviceId2}" data-signature="${p.signature}" data-timezone="${p.timezone}" data-username="${p.username}" data-password="" data-watchdog="${p.watchdogInterval}">
  <div class="card-header">
    <div class="card-info">
      <div class="card-name">${if (p.name.isNotEmpty()) p.name else "Profile ${p.id}"}</div>
      <div class="card-meta"><i class="fa-solid fa-link"></i> ${p.portalUrl}</div>
      <div class="card-meta"><i class="fa-solid fa-network-wired"></i> ${p.mac}</div>
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
    <a class="btn btn-ghost" href="#" data-copy="http://{host}:${p.hlsPort}/" onclick="copyLink(event,this)"><i class="fa-solid fa-film"></i> <span>HLS</span></a>
    <a class="btn btn-ghost link-proxy" href="#" data-copy="http://{host}:${p.proxyPort}/" onclick="copyLink(event,this)"><i class="fa-solid fa-right-left"></i> <span>Proxy</span></a>
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
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Stalkerhek Dashboard</title>
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
      <img src="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png" alt="" onerror="this.style.display='none'" />
      <h1>Stalkerhek</h1>
    </div>
    <div class="nav-links">
      <a class="nav-link" href="/logs" target="_blank"><i class="fa-regular fa-file-lines"></i><span>Logs</span></a>
      <a class="nav-link" href="/account"><i class="fa-solid fa-user-shield"></i><span>Account</span></a>
      <a class="nav-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
    </div>
  </div>

  <div class="tabs" role="tablist">
    <button class="tab active" role="tab" data-tab="create"><i class="fa-solid fa-plus"></i> Create</button>
    <button class="tab" role="tab" data-tab="manage"><i class="fa-solid fa-layer-group"></i> Manage</button>
    <button class="tab" role="tab" data-tab="advanced"><i class="fa-solid fa-sliders"></i> Advanced</button>
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
          <div><label for="hls_port">HLS Port</label><input id="hls_port" name="hls_port" required inputmode="numeric" /></div>
          <div><label for="proxy_port">Proxy Port</label><input id="proxy_port" name="proxy_port" required inputmode="numeric" /></div>
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
        <p class="setting-hint" style="margin-top:12px">Tip: After saving, the profile will start automatically.</p>
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

  <div class="section" id="section-advanced">
    <div class="card">
      <div class="card-title">Advanced Settings</div>
      <div class="card-sub">Runtime tuning for streaming stability and proxy behavior.</div>
      <form id="settingsForm" onsubmit="return false">
        <div class="settings-grid">
          <div class="setting-card">
            <div class="setting-label">Playlist Delay</div>
            <input id="s_delay" name="playlist_delay_segments" inputmode="numeric" placeholder="3" />
            <div class="setting-hint">Segments of latency added to reduce buffering.</div>
          </div>
          <div class="setting-card">
            <div class="setting-label">Header Timeout</div>
            <input id="s_rht" name="response_header_timeout_seconds" inputmode="numeric" placeholder="25" />
            <div class="setting-hint">Seconds to wait for upstream response headers.</div>
          </div>
          <div class="setting-card">
            <div class="setting-label">Max Idle Connections</div>
            <input id="s_idle" name="max_idle_conns_per_host" inputmode="numeric" placeholder="128" />
            <div class="setting-hint">Higher values improve concurrent stream performance.</div>
          </div>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="button" id="saveSettings"><i class="fa-regular fa-floppy-disk"></i> <span>Save Settings</span></button>
        </div>
        <p class="setting-hint" style="margin-top:12px">Leave a field empty to keep its current value.</p>
      </form>
    </div>
  </div>
</div>

<div class="bottom-nav">
  <div class="bottom-nav-inner">
    <span class="bottom-link"><i class="fa-solid fa-network-wired"></i><span>{host}</span></span>
    <a class="bottom-link" href="/logs" target="_blank"><i class="fa-regular fa-file-lines"></i><span>Logs</span></a>
    <a class="bottom-link" href="/account"><i class="fa-solid fa-user-shield"></i><span>Account</span></a>
    <a class="bottom-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
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

document.getElementById('saveSettings').addEventListener('click', async function () {
  try {
    await postForm('/api/settings', {
      playlist_delay_segments: document.getElementById('s_delay').value,
      response_header_timeout_seconds: document.getElementById('s_rht').value,
      max_idle_conns_per_host: document.getElementById('s_idle').value
    });
    showToast('Saved', 'Settings applied.');
  } catch (e) { showToast('Save failed', e.message || ''); }
});

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
      if (badgeIcon) badgeIcon.className = 'fa-solid fa-' + (s.running ? 'play' : (s.phase === 'error' ? 'exclamation-triangle' : (s.busy ? 'spinner fa-spin' : 'pause')));
      var lines = [];
      if (s.message) lines.push('<div><i class="fa-solid fa-info-circle"></i> ' + s.message.replace(/</g,'&lt;') + '</div>');
      if (s.phase === 'error') lines.push('<div><i class="fa-solid fa-circle-exclamation"></i> Tip: open Instance Logs for details.</div>');
      if (s.channels) lines.push('<div><i class="fa-solid fa-satellite-dish"></i> Channels: ' + s.channels + '</div>');
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