package com.stalkerhek.api

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.channels.Channel
import java.time.Instant

fun Routing.logRoutes(logService: LogService, authStore: AuthStore) {
    val authEnabled = System.getenv("STALKERHEK_DISABLE_AUTH") != "1"

    fun checkAuth(call: ApplicationCall) = checkAuth(call, authEnabled, authStore)

    // Instance logs page
    get("/logs") {
        if (!checkAuth(call)) {
            call.respondRedirect("/login")
            return@get
        }
        call.respondText(instanceLogsPageHtml(), ContentType.Text.Html)
    }

    // Instance logs SSE stream
    get("/api/logs/stream") {
        if (!checkAuth(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        call.response.header("Content-Type", "text/event-stream")
        call.response.header("Cache-Control", "no-cache")
        call.response.header("Connection", "keep-alive")
        call.response.header("X-Accel-Buffering", "no")

        val channel = Channel<String>(Channel.BUFFERED)
        val job = GlobalScope.launch {
            try {
                var lastCount = 0
                while (true) {
                    val logs = logService.getInstanceLogs()
                    if (logs.size > lastCount) {
                        for (i in lastCount until logs.size) {
                            val payload = json.encodeToString(mapOf("ts" to Instant.now().toString(), "line" to logs[i]))
                            channel.send("event: log\ndata: $payload\n\n")
                        }
                        lastCount = logs.size
                    }
                    delay(1000)
                    channel.send(": keepalive\n\n")
                }
            } catch (e: Exception) {
                // client disconnected
            }
        }

        try {
            call.respondTextWriter(contentType = ContentType("text", "event-stream")) {
                try {
                    for (msg in channel) {
                        write(msg)
                        flush()
                    }
                } catch (e: Exception) {
                    // client disconnected
                }
            }
        } finally {
            job.cancel()
            channel.close()
        }
    }

    // Profile logs page
    get("/profiles/{id}/logs") {
        if (!checkAuth(call)) {
            call.respondRedirect("/login")
            return@get
        }
        val pid = call.parameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        call.respondText(profileLogsPageHtml(pid), ContentType.Text.Html)
    }

    // Profile logs SSE stream
    get("/api/profiles/{id}/logs/stream") {
        if (!checkAuth(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        val pid = call.parameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)

        call.response.header("Content-Type", "text/event-stream")
        call.response.header("Cache-Control", "no-cache")
        call.response.header("Connection", "keep-alive")
        call.response.header("X-Accel-Buffering", "no")

        val channel = Channel<String>(Channel.BUFFERED)
        val job = GlobalScope.launch {
            try {
                var lastId = 0L
                while (true) {
                    val logs = logService.getLogs(pid)
                    for (entry in logs) {
                        if (entry.id > lastId) {
                            val payload = json.encodeToString(mapOf("id" to entry.id, "ts" to entry.ts, "line" to entry.msg))
                            channel.send("event: log\ndata: $payload\n\n")
                            lastId = entry.id
                        }
                    }
                    delay(1000)
                    channel.send(": keepalive\n\n")
                }
            } catch (e: Exception) {
                // client disconnected
            }
        }

        try {
            call.respondTextWriter(contentType = ContentType("text", "event-stream")) {
                try {
                    for (msg in channel) {
                        write(msg)
                        flush()
                    }
                } catch (e: Exception) {
                    // client disconnected
                }
            }
        } finally {
            job.cancel()
            channel.close()
        }
    }

    // API: get all profile logs
    get("/api/logs") {
        if (!checkAuth(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        val entries = logService.getInstanceLogs()
        call.respondText(json.encodeToString(entries), ContentType.Application.Json)
    }

    // API: get profile logs
    get("/api/profiles/{id}/logs") {
        if (!checkAuth(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        val pid = call.parameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val entries = logService.getLogs(pid)
        call.respondText(json.encodeToString(entries), ContentType.Application.Json)
    }
}

fun instanceLogsPageHtml(): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Stalkerhek Logs</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;display:flex;flex-direction:column;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:1200px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));flex:1;display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s;flex:1;display:flex;flex-direction:column}
.card:hover{border-color:var(--border-light)}
.card-title{font-size:17px;font-weight:700;margin-bottom:4px}
.card-sub{color:var(--muted);font-size:13px;margin-bottom:16px}
.box{flex:1 1 auto;min-height:55vh;max-height:72vh;overflow:auto;scrollbar-gutter:stable;border:1px solid var(--border);border-radius:12px;background:var(--bg);padding:8px}
.ln{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono","Courier New",monospace;font-size:12.5px;line-height:1.5;padding:7px 10px;border-bottom:1px dashed rgba(31,46,35,.65);white-space:pre-wrap;word-break:break-all}
.ln:last-child{border-bottom:none}
.ln:hover{background:rgba(45,138,78,.04)}
.ts{color:var(--muted);margin-right:10px}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--border-light)}
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
      <a class="nav-link" href="/account"><i class="fa-solid fa-user-shield"></i><span>Account</span></a>
      <a class="nav-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
    </div>
  </div>
  <div class="card">
    <div class="card-title"><i class="fa-regular fa-file-lines"></i> Instance Logs</div>
    <div class="card-sub">Live logs from the running Stalkerhek process.</div>
    <div class="box" id="box"></div>
  </div>
</div>
<script>
const box=document.getElementById('box');
function addLine(ts,line){
const div=document.createElement('div');div.className='ln';
const t=document.createElement('span');t.className='ts';t.textContent='['+ts+'] ';div.appendChild(t);
div.appendChild(document.createTextNode(line||''));box.appendChild(div);box.scrollTop=box.scrollHeight;
}
const es=new EventSource('/api/logs/stream');
es.addEventListener('log',(ev)=>{try{const d=JSON.parse(ev.data);addLine(d.ts||new Date().toISOString(),d.line||'');}catch(e){addLine(new Date().toISOString(),ev.data||'');}});
es.onerror=()=>{addLine(new Date().toISOString(),'connection lost; retrying...');};
</script>
</body>
</html>"""

fun profileLogsPageHtml(profileId: Int): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Profile $profileId Logs</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#080c09;--surface:#0c120e;--surface2:#111a14;--border:#1a2c1f;--border-light:#23382a;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e;--brand-glow:rgba(45,138,78,0.15);--font:system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Helvetica,Arial,sans-serif}
html{font-size:15px}
body{font-family:var(--font);background:var(--bg);color:var(--text);min-height:100dvh;display:flex;flex-direction:column;line-height:1.5;-webkit-font-smoothing:antialiased}
a{color:var(--brand);text-decoration:none;transition:color .15s}a:hover{color:#4dca74}
.wrap{max-width:1200px;width:100%;margin:0 auto;padding:calc(20px + env(safe-area-inset-top)) calc(16px + env(safe-area-inset-left)) calc(100px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-right));flex:1;display:flex;flex-direction:column;gap:16px}
.top-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}
.logo{display:flex;align-items:center;gap:12px}
.logo img{height:clamp(32px,5vw,48px);width:auto;border-radius:10px}
.logo h1{font-size:clamp(16px,2.5vw,22px);font-weight:700;letter-spacing:-.3px}
.nav-links{display:flex;gap:8px;flex-wrap:wrap}
.nav-link{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;border:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:13px;font-weight:500;transition:all .15s}
.nav-link:hover{background:var(--surface2);border-color:var(--brand);color:var(--text)}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:clamp(16px,3vw,24px);transition:border-color .2s;flex:1;display:flex;flex-direction:column}
.card:hover{border-color:var(--border-light)}
.card-title{font-size:17px;font-weight:700;margin-bottom:4px}
.card-sub{color:var(--muted);font-size:13px;margin-bottom:16px}
.box{flex:1 1 auto;min-height:55vh;max-height:72vh;overflow:auto;scrollbar-gutter:stable;border:1px solid var(--border);border-radius:12px;background:var(--bg);padding:8px}
.ln{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono","Courier New",monospace;font-size:12.5px;line-height:1.5;padding:7px 10px;border-bottom:1px dashed rgba(31,46,35,.65);white-space:pre-wrap;word-break:break-all}
.ln:last-child{border-bottom:none}
.ln:hover{background:rgba(45,138,78,.04)}
.ts{color:var(--muted);margin-right:10px}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:var(--border-light)}
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
      <a class="nav-link" href="/account"><i class="fa-solid fa-user-shield"></i><span>Account</span></a>
      <a class="nav-link" href="https://github.com/kidpoleon/stalkerhek" target="_blank"><i class="fa-brands fa-github"></i><span>GitHub</span></a>
    </div>
  </div>
  <div class="card">
    <div class="card-title"><i class="fa-regular fa-file-lines"></i> Profile $profileId Logs</div>
    <div class="card-sub">Logs for profile $profileId.</div>
    <div class="box" id="box"></div>
  </div>
</div>
<script>
const box=document.getElementById('box');
function addLine(ts,line){
const div=document.createElement('div');div.className='ln';
const t=document.createElement('span');t.className='ts';t.textContent='['+ts+'] ';div.appendChild(t);
div.appendChild(document.createTextNode(line||''));box.appendChild(div);box.scrollTop=box.scrollHeight;
}
const es=new EventSource('/api/profiles/$profileId/logs/stream');
es.addEventListener('log',(ev)=>{try{const d=JSON.parse(ev.data);addLine(d.ts||new Date().toISOString(),d.line||'');}catch(e){addLine(new Date().toISOString(),ev.data||'');}});
es.onerror=()=>{addLine(new Date().toISOString(),'connection lost; retrying...');};
</script>
</body>
</html>"""
