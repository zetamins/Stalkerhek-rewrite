package com.stalkerhek.api

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.staticRoutes() {
    // Channel filter page
    get("/filters") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val ua = call.request.headers["User-Agent"]?.lowercase() ?: ""
        val isMobile = listOf("mobile", "android", "iphone", "ipad").any { ua.contains(it) }

        if (isMobile) {
            call.respondText("""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Filters - Desktop Required</title>
<style>:root{--bg:#080c09;--surface:#0c120e;--border:#1a2c1f;--text:#e2ece3;--muted:#8ba38d;--brand:#2d8a4e}*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:18px}a{color:var(--brand)}.card{max-width:520px;width:100%;border:1px solid var(--border);border-radius:18px;background:rgba(13,20,16,.75);padding:18px}h1{margin:0 0 8px 0;font-size:18px}.sub{color:var(--muted);line-height:1.5}</style></head><body><div class="card"><h1>Filters require a desktop browser</h1><div class="sub">Channel filtering is a power feature and is intentionally desktop-only for clarity and safety.<br><br>Please open this page on a desktop/laptop browser.</div><div class="sub" style="margin-top:12px"><a href="/dashboard">Back to Dashboard</a></div></div></body></html>""", ContentType.Text.Html)
            return@get
        }

        call.respondText(renderFiltersPage(pid), ContentType.Text.Html)
    }
}

fun String.esc(): String = this.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun renderFiltersPage(profileId: Int): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="icon" href="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css" referrerpolicy="no-referrer" />
<title>Stalkerhek Filters</title>
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
      <img src="https://i.ibb.co/MyxmyVzz/STALKERHEK-LOGO-1500x1500.png" alt="" onerror="this.style.display='none'" />
      <h1>Stalkerhek</h1>
    </div>
    <div class="nav-links">
      <a class="nav-link" href="/dashboard"><i class="fa-solid fa-arrow-left"></i><span>Dashboard</span></a>
      <a class="nav-link" href="/account"><i class="fa-solid fa-user-shield"></i><span>Account</span></a>
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
        <select id="profileSel" style="width:auto;padding:8px 12px;font-size:13px"><option value="$profileId" selected>Profile $profileId</option></select>
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
      <div class="card-sub">Enable/disable VOD categories. Apply immediately to STB and proxy.</div>
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
      <div class="card-sub">Enable/disable Series categories. Apply immediately to STB and proxy.</div>
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
      <div class="card-sub">Remove prefixes/suffixes from channel names. Applied in UI, playlists, and streams.</div>
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
      <div class="card-sub">Rename genre/group names. Applied in UI, playlists, and EPG.</div>
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
let st={pid:$profileId,genreId:'',genreName:'',q:'',view:'all',selected:new Set(),genreSelected:new Set(),genres:[],items:[],renamePrefix:'',renameSuffix:''};
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
    const gid=g.genreId||g.genreId||g.genre_id||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=st.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)st.genreSelected.add(gid);else st.genreSelected.delete(gid);renderGenres(st.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n.esc()+'</div><div class="small">'+(g.enabled||0)+' en / '+(g.total||0)+' tot</div>';
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
    name.innerHTML='<div style="font-weight:600;font-size:13px">'+x.title.esc()+'</div><div class="mono" style="font-size:11px;color:var(--muted);margin-top:2px">'+x.cmd.esc()+'</div>';
    const gen=document.createElement('td');
    gen.innerHTML='<span class="small">'+(x.genre||'').esc()+'</span>';
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
_('rows').parentElement?.parentElement?.addEventListener('keydown',e=>{
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
    _('renameStatus').textContent='Saved! Reload genres to see changes.';
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
    const t=document.createElement('div');t.textContent=(map[gid]||gid).esc()+' <span style="opacity:.5">('+gid.esc()+')</span>';
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
      const opt=document.createElement('option');opt.value=g.genreId||'';opt.textContent=(g.name||'Other').esc();
      grp.appendChild(opt);
    });
    sel.appendChild(grp);
  });
  sel.value=cur;
};

_('genreRenameSelect').onchange=()=>{
  const gid=_('genreRenameSelect').value;
  if(!gid){_('genreRenameName').value='';return}
  // Find existing rename for this genre
  fetch('/api/filters/genre_renames?id='+st.pid,{cache:'no-store'}).then(r=>r.json()).then(j=>{
    const renames=j.genreRenames||{};
    _('genreRenameName').value=renames[gid]||'';
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
_('genreSelAll').onclick=()=>{(st.genres||[]).forEach(g=>st.genreSelected.add(g.genreId||g.genre_id||''));renderGenres(st.genres)};
_('genreSelNone').onclick=()=>{st.genreSelected.clear();renderGenres(st.genres)};
_('genreEnable').onclick=async()=>{const ids=Array.from(st.genreSelected);for(const gid of ids){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',ids.length+' genres');st.genreSelected.clear();loadGenres()};
_('genreDisable').onclick=async()=>{const ids=Array.from(st.genreSelected);for(const gid of ids){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',ids.length+' genres');st.genreSelected.clear();loadGenres()};
_('selAll').onclick=()=>{(st.items||[]).forEach(x=>st.selected.add(x.cmd));renderChannels(st.items)};
_('selNone').onclick=()=>{st.selected.clear();renderChannels(st.items)};
_('bulkEnable').onclick=async()=>{const ids=Array.from(st.selected);for(const cmd of ids){try{await postForm('/api/filters/toggle_channel',{id:String(st.pid),cmd:cmd,disabled:'0'})}catch(e){}}toast('Enabled',ids.length+' channels');st.selected.clear();loadChannels()};
_('bulkDisable').onclick=async()=>{const ids=Array.from(st.selected);for(const cmd of ids){try{await postForm('/api/filters/toggle_channel',{id:String(st.pid),cmd:cmd,disabled:'1'})}catch(e){}}toast('Disabled',ids.length+' channels');st.selected.clear();loadChannels()};

// Init
String.prototype.esc=function(){return this.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')};
loadGenres();

// VOD tab — just genre list, no item loading
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
    const n=g.name||'Other';const gid=g.genreId||g.genre_id||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=vodSt.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)vodSt.genreSelected.add(gid);else vodSt.genreSelected.delete(gid);renderVodGenres(vodSt.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n.esc()+'</div>';
    left.appendChild(cw);left.appendChild(info);
    const pill=document.createElement('div');pill.className='pill '+(g.disabled?'bad':'ok');pill.textContent=g.disabled?'Disabled':'Enabled';
    row.appendChild(left);row.appendChild(pill);_('vodGenreList').appendChild(row);
  });
  if(!_('vodGenreList').children.length)_('vodGenreList').innerHTML='<div class="small" style="padding:12px;text-align:center">No matching categories</div>';
};
_('vodGenreSearch').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>renderVodGenres(vodSt.genres),200)};
_('vodGenreSelAll').onclick=()=>{(vodSt.genres||[]).forEach(g=>vodSt.genreSelected.add(g.genreId||g.genre_id||''));renderVodGenres(vodSt.genres)};
_('vodGenreSelNone').onclick=()=>{vodSt.genreSelected.clear();renderVodGenres(vodSt.genres)};
_('vodGenreEnable').onclick=async()=>{for(const gid of Array.from(vodSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',vodSt.genreSelected.size+' categories');vodSt.genreSelected.clear();loadVodGenres()};
_('vodGenreDisable').onclick=async()=>{for(const gid of Array.from(vodSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',vodSt.genreSelected.size+' categories');vodSt.genreSelected.clear();loadVodGenres()};

// Series tab — just genre list, no item loading
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
    const n=g.name||'Other';const gid=g.genreId||g.genre_id||'';
    if(q&&!n.toLowerCase().includes(q))return;
    const row=document.createElement('div');row.className='item';
    const left=document.createElement('div');left.style.display='flex';left.style.gap='10px';left.style.alignItems='center';
    const cw=document.createElement('span');cw.className='ck';
    const chk=document.createElement('input');chk.type='checkbox';chk.checked=seriesSt.genreSelected.has(gid);
    chk.onclick=e=>e.stopPropagation();
    chk.onchange=()=>{if(chk.checked)seriesSt.genreSelected.add(gid);else seriesSt.genreSelected.delete(gid);renderSeriesGenres(seriesSt.genres)};
    cw.appendChild(chk);
    const info=document.createElement('div');
    info.innerHTML='<div class="name">'+n.esc()+'</div>';
    left.appendChild(cw);left.appendChild(info);
    const pill=document.createElement('div');pill.className='pill '+(g.disabled?'bad':'ok');pill.textContent=g.disabled?'Disabled':'Enabled';
    row.appendChild(left);row.appendChild(pill);_('seriesGenreList').appendChild(row);
  });
  if(!_('seriesGenreList').children.length)_('seriesGenreList').innerHTML='<div class="small" style="padding:12px;text-align:center">No matching categories</div>';
};
_('seriesGenreSearch').oninput=()=>{clearTimeout(debTimer);debTimer=setTimeout(()=>renderSeriesGenres(seriesSt.genres),200)};
_('seriesGenreSelAll').onclick=()=>{(seriesSt.genres||[]).forEach(g=>seriesSt.genreSelected.add(g.genreId||g.genre_id||''));renderSeriesGenres(seriesSt.genres)};
_('seriesGenreSelNone').onclick=()=>{seriesSt.genreSelected.clear();renderSeriesGenres(seriesSt.genres)};
_('seriesGenreEnable').onclick=async()=>{for(const gid of Array.from(seriesSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'0'})}catch(e){}}toast('Enabled',seriesSt.genreSelected.size+' categories');seriesSt.genreSelected.clear();loadSeriesGenres()};
_('seriesGenreDisable').onclick=async()=>{for(const gid of Array.from(seriesSt.genreSelected)){try{await postForm('/api/filters/toggle_genre',{id:String(st.pid),genre_id:gid,disabled:'1'})}catch(e){}}toast('Disabled',seriesSt.genreSelected.size+' categories');seriesSt.genreSelected.clear();loadSeriesGenres()};

</script>
</body>
</html>"""
