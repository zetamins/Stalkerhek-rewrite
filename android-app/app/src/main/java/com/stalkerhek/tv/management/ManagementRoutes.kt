package com.stalkerhek.tv.management

import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.engine.ProfileConfig
import com.stalkerhek.tv.engine.RustEngineBridge
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// Generate a random hex string of the given length (used for device IDs, serial, etc.)
private val secureRandom = SecureRandom()
private fun randomHex(length: Int): String {
    val chars = "0123456789abcdef"
    return (1..length).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
}

// In-memory filter state cache — needed because the Rust engine persists filter state
// internally but doesn't expose a read-back API for rename rules and genre renames.
private val renamePrefixMap = ConcurrentHashMap<Int, String>()
private val renameSuffixMap = ConcurrentHashMap<Int, String>()
private val genreRenamesMap = ConcurrentHashMap<Int, MutableMap<String, String>>()

fun Routing.managementRoutes(engine: EngineController) {
    val json = Json { prettyPrint = false }

    // Health check
    get("/health") {
        call.respond(mapOf("status" to "ok", "service" to "stalkerhek-android"))
    }

    // Profile list
    get("/api/v1/profiles") {
        call.respond(engine.profiles.value)
    }

    // Profile statuses (used by dashboard polling)
    get("/api/profile_status") {
        val statuses = engine.profiles.value.map { profile ->
            try { runBlocking { engine.getProfileStatus(profile.id) } } catch (_: Exception) { null }
        }.filterNotNull()
        call.respond(statuses)
    }

    // --- Web-form profile management (redirect to /dashboard) ---

    post("/profiles") {
        val params = call.receiveParameters()
        val editId = params["edit_id"]?.toIntOrNull() ?: 0
        val name = params["name"]?.trim() ?: ""
        val portal = params["portal"]?.trim() ?: ""
        val mac = params["mac"]?.trim()?.uppercase() ?: ""
        val hlsPort = params["hls_port"]?.toIntOrNull() ?: 4600
        val proxyPort = params["proxy_port"]?.toIntOrNull() ?: 4800

        // For edit, preserve existing password if form field is empty
        val existingPassword = if (editId > 0) {
            engine.profiles.value.find { it.id == editId }?.password ?: ""
        } else ""

        val passwordParam = params["password"]?.trim() ?: ""
        val password = if (passwordParam.isNotEmpty()) passwordParam else existingPassword

        val profile = ProfileConfig(
            id = editId,
            name = name,
            portalUrl = portal,
            mac = mac,
            hlsPort = hlsPort,
            proxyPort = proxyPort,
            username = params["username"]?.trim() ?: "",
            password = password,
            model = (params["model"]?.trim() ?: "").ifEmpty { "MAG254" },
            serialNumber = (params["serial_number"]?.trim() ?: "").let {
                val v = it.trim()
                if (v.isNotEmpty() && v != "0000000000000") v else randomHex(13)
            },
            deviceId = (params["device_id"]?.trim() ?: "").let {
                val v = it.trim()
                if (v.isNotEmpty() && v != "f".repeat(64)) v else randomHex(64)
            },
            deviceId2 = (params["device_id2"]?.trim() ?: "").let {
                val v = it.trim()
                if (v.isNotEmpty() && v != "f".repeat(64)) v else randomHex(64)
            },
            signature = (params["signature"]?.trim() ?: "").let {
                val v = it.trim()
                if (v.isNotEmpty() && v != "f".repeat(64)) v else randomHex(64)
            },
            timezone = (params["timezone"]?.trim() ?: "").ifEmpty { "UTC" },
            watchdogInterval = params["watchdog_time"]?.toIntOrNull() ?: 5,
            deviceIdAuth = params["username"]?.trim().isNullOrEmpty(),
            hlsEnabled = true,
            proxyEnabled = true,
            proxyRewrite = true,
        )

        try {
            runBlocking { engine.createProfile(profile) }
            call.respondRedirect("/dashboard")
        } catch (e: Exception) {
            call.respondText("Failed to save profile: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    post("/profiles/delete") {
        val params = call.receiveParameters()
        val id = params["id"]?.toIntOrNull()
        if (id == null) {
            call.respondRedirect("/dashboard")
            return@post
        }
        try {
            runBlocking { engine.deleteProfile(id) }
        } catch (_: Exception) {}
        call.respondRedirect("/dashboard")
    }

    // --- JS API profile lifecycle (called from dashboard JS, returns JSON) ---

    post("/api/profiles/start") {
        val params = call.receiveParameters()
        val id = params["id"]?.toIntOrNull()
        if (id == null) {
            call.respondText("""{"ok":false,"error":"invalid id"}""", ContentType.Application.Json)
            return@post
        }
        val profile = engine.profiles.value.find { it.id == id }
        if (profile == null) {
            call.respondText("""{"ok":false,"error":"profile not found"}""", ContentType.Application.Json)
            return@post
        }
        val result = runBlocking { engine.startProfile(profile) }
        if (result.isSuccess) {
            val channels = result.getOrNull()?.channelsCount ?: 0
            call.respondText("""{"ok":true,"id":$id,"channels":$channels}""", ContentType.Application.Json)
        } else {
            val err = result.exceptionOrNull()?.message ?: "start failed"
            call.respondText("""{"ok":false,"error":"$err"}""", ContentType.Application.Json)
        }
    }

    post("/api/profiles/stop") {
        val params = call.receiveParameters()
        val id = params["id"]?.toIntOrNull()
        if (id == null) {
            call.respondText("""{"ok":false,"error":"invalid id"}""", ContentType.Application.Json)
            return@post
        }
        try {
            runBlocking { engine.stopProfile(id) }
        } catch (_: Exception) {}
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Existing v1 API endpoints
    post("/api/v1/profile/{id}/start") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) { call.respondText("bad request", status = HttpStatusCode.BadRequest); return@post }
        val profile = engine.profiles.value.find { it.id == id }
        if (profile == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@post }
        val result = runBlocking { engine.startProfile(profile) }
        if (result.isSuccess) {
            val channels = result.getOrNull()?.channelsCount ?: 0
            call.respondText("""{"ok":true,"id":$id,"channels":$channels}""", ContentType.Application.Json)
        } else {
            call.respondText(result.exceptionOrNull()?.message ?: "error", status = HttpStatusCode.InternalServerError)
        }
    }

    post("/api/v1/profile/{id}/stop") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) { call.respondText("bad request", status = HttpStatusCode.BadRequest); return@post }
        runBlocking { engine.stopProfile(id) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    get("/api/v1/profile/{id}/status") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) { call.respondText("bad request", status = HttpStatusCode.BadRequest); return@get }
        val status = runBlocking { engine.getProfileStatus(id) }
        if (status != null) {
            call.respond(status)
        } else {
            call.respondText("""{"phase":"idle","message":"Not running","running":false}""", ContentType.Application.Json)
        }
    }

    // --- Dashboard and Filters HTML pages ---

    get("/dashboard") {
        call.respondText(renderDashboardHtml(engine), ContentType.Text.Html)
    }

    get("/filters") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val profiles = engine.profiles.value
        call.respondText(renderFiltersHtml(pid, profiles), ContentType.Text.Html)
    }

    get("/") {
        call.respondRedirect("/dashboard")
    }

    // --- Filter API: Genres ---

    get("/api/filters/genres") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        if (engine.profiles.value.none { it.id == pid }) { call.respondText("[]", ContentType.Application.Json); return@get }
        val channels = runBlocking { engine.getChannels(pid, "itv") }
        val json = buildJsonArray {
            channels.groupBy { it.genreId }.forEach { (gid, chs) ->
                addJsonObject {
                    put("genreId", gid)
                    put("name", chs.firstOrNull()?.genre ?: "Other")
                    put("total", chs.size)
                    put("enabled", chs.count { it.enabled })
                    put("disabled", chs.count { !it.enabled })
                }
            }
        }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    get("/api/filters/channels") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val query = call.request.queryParameters["query"]?.trim() ?: ""
        val genreId = call.request.queryParameters["genre_id"]?.trim() ?: ""
        val stateFilter = call.request.queryParameters["state"]?.trim() ?: "all"
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000

        if (engine.profiles.value.none { it.id == pid }) { call.respondText("""{"items":[],"total":0}""", ContentType.Application.Json); return@get }
        val channels = runBlocking { engine.getChannels(pid, "itv") }

        // Apply rename rules from cache
        val prefix = renamePrefixMap[pid] ?: ""
        val suffix = renameSuffixMap[pid] ?: ""
        val renames = genreRenamesMap[pid] ?: emptyMap()

        var filtered = channels
        if (genreId.isNotEmpty()) filtered = filtered.filter { it.genreId == genreId }
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(q) || it.cmd.lowercase().contains(q)
            }
        }
        when (stateFilter) {
            "enabled" -> filtered = filtered.filter { it.enabled }
            "disabled" -> filtered = filtered.filter { !it.enabled }
        }

        val total = filtered.size
        val response = buildJsonObject {
            put("items", buildJsonArray {
                filtered.drop(offset).take(limit).forEach { ch ->
                    addJsonObject {
                        val renamed = ch.title.let { t ->
                            var r = t
                            if (prefix.isNotEmpty() && r.startsWith(prefix)) r = r.removePrefix(prefix)
                            if (suffix.isNotEmpty() && r.endsWith(suffix)) r = r.removeSuffix(suffix)
                            r
                        }
                        put("cmd", ch.cmd)
                        put("title", renamed)
                        put("genre", renames[ch.genreId] ?: ch.genre)
                        put("enabled", ch.enabled)
                    }
                }
            })
            put("total", total)
        }
        call.respondText(response.toString(), ContentType.Application.Json)
    }

    post("/api/filters/toggle_genre") {
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull()
        val genreId = params["genre_id"]
        val disabled = params["disabled"]
        if (pid == null || genreId.isNullOrEmpty()) {
            call.respondText("""{"ok":false,"error":"missing params"}""", ContentType.Application.Json)
            return@post
        }
        runBlocking {
            engine.filterUpdate(
                action = "toggle_genre",
                profileId = pid,
                genreId = genreId,
                disabled = disabled == "1"
            )
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/api/filters/toggle_channel") {
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull()
        val cmd = params["cmd"]
        val disabled = params["disabled"]
        if (pid == null || cmd.isNullOrEmpty()) {
            call.respondText("""{"ok":false,"error":"missing params"}""", ContentType.Application.Json)
            return@post
        }
        runBlocking {
            engine.filterUpdate(
                action = "toggle_channel",
                profileId = pid,
                cmd = cmd,
                disabled = disabled == "1"
            )
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/api/filters/reset") {
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull()
        if (pid == null) {
            call.respondText("""{"ok":false,"error":"missing id"}""", ContentType.Application.Json)
            return@post
        }
        runBlocking { engine.filterUpdate(action = "reset", profileId = pid) }
        // Clear cached filter state for this profile
        renamePrefixMap.remove(pid)
        renameSuffixMap.remove(pid)
        genreRenamesMap.remove(pid)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // --- VOD / Series categories ---

    get("/api/filters/vod_genres") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        if (engine.profiles.value.none { it.id == pid }) { call.respondText("[]", ContentType.Application.Json); return@get }
        val categories = runBlocking { engine.getCategories(pid, "vod") }
        val disabledGenres = runBlocking { engine.getDisabledGenres(pid) }
        val response = buildJsonArray {
            categories.forEach { cat ->
                val gid = cat.id.ifEmpty { cat.name }
                addJsonObject {
                    put("genreId", gid)
                    put("name", cat.title.ifEmpty { cat.name })
                    put("disabled", gid in disabledGenres)
                }
            }
        }
        call.respondText(response.toString(), ContentType.Application.Json)
    }

    get("/api/filters/series_genres") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        if (engine.profiles.value.none { it.id == pid }) { call.respondText("[]", ContentType.Application.Json); return@get }
        val categories = runBlocking { engine.getCategories(pid, "series") }
        val disabledGenres = runBlocking { engine.getDisabledGenres(pid) }
        val response = buildJsonArray {
            categories.forEach { cat ->
                val gid = cat.id.ifEmpty { cat.name }
                addJsonObject {
                    put("genreId", gid)
                    put("name", cat.title.ifEmpty { cat.name })
                    put("disabled", gid in disabledGenres)
                }
            }
        }
        call.respondText(response.toString(), ContentType.Application.Json)
    }

    // --- Rename rules ---

    get("/api/filters/rename_rules") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val prefix = renamePrefixMap[pid] ?: ""
        val suffix = renameSuffixMap[pid] ?: ""
        val resp = buildJsonObject {
            put("renamePrefix", prefix)
            put("renameSuffix", suffix)
        }
        call.respondText(resp.toString(), ContentType.Application.Json)
    }

    post("/api/filters/rename_rules") {
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull()
        val prefix = params["renamePrefix"]?.trim() ?: ""
        val suffix = params["renameSuffix"]?.trim() ?: ""
        if (pid == null) {
            call.respondText("""{"ok":false,"error":"missing id"}""", ContentType.Application.Json)
            return@post
        }
        // Persist to Rust engine
        try {
            val actionJson = buildJsonObject {
                put("profile_id", pid.toString())
                put("action", "rename")
                put("rename_prefix", prefix)
                put("rename_suffix", suffix)
            }
            RustEngineBridge.nativeFilterUpdate(actionJson.toString())
        } catch (_: Exception) {}
        // Update cache
        renamePrefixMap[pid] = prefix
        renameSuffixMap[pid] = suffix
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // --- Genre renames ---

    get("/api/filters/genre_renames") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val renames = genreRenamesMap[pid] ?: emptyMap()
        val json = buildJsonObject {
            putJsonObject("genreRenames") {
                renames.forEach { (k, v) -> put(k, v) }
            }
        }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    post("/api/filters/rename_genre") {
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull()
        val genreId = params["genre_id"]
        val name = params["name"]?.trim() ?: ""
        if (pid == null || genreId.isNullOrEmpty()) {
            call.respondText("""{"ok":false,"error":"missing params"}""", ContentType.Application.Json)
            return@post
        }
        // Persist to Rust engine
        runBlocking {
            engine.filterUpdate(
                action = "rename_genre",
                profileId = pid,
                genreRenameId = genreId,
                genreRenameName = name
            )
        }
        // Update cache
        val renames = genreRenamesMap.getOrPut(pid) { mutableMapOf() }
        if (name.isEmpty()) renames.remove(genreId) else renames[genreId] = name
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Debug: inspect filter state from Rust engine
    get("/api/debug/filters") {
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: 0
        val result = runBlocking { engine.getFilterState(pid) }
        call.respondText(result, ContentType.Application.Json)
    }

    // --- Backup / Restore (Import/Export) ---

    get("/api/backup/export") {
        val profiles = engine.profiles.value
        val backup = buildJsonObject {
            put("version", 1)
            put("exported_at", System.currentTimeMillis() / 1000)
            putJsonArray("profiles") {
                profiles.forEach { p ->
                    addJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        put("portalUrl", p.portalUrl)
                        put("mac", p.mac)
                        put("username", p.username)
                        put("password", p.password)
                        put("hlsPort", p.hlsPort)
                        put("proxyPort", p.proxyPort)
                        put("timezone", p.timezone)
                        put("serialNumber", p.serialNumber)
                        put("deviceId", p.deviceId)
                        put("deviceId2", p.deviceId2)
                        put("signature", p.signature)
                        put("model", p.model)
                        put("watchdogInterval", p.watchdogInterval)
                        put("deviceIdAuth", p.deviceIdAuth)
                        put("hlsEnabled", p.hlsEnabled)
                        put("proxyEnabled", p.proxyEnabled)
                        put("proxyRewrite", p.proxyRewrite)
                    }
                }
            }
            putJsonObject("filters") {
                profiles.forEach { p ->
                    val state = runBlocking { engine.getFilterState(p.id) }
                    put(p.id.toString(), Json.parseToJsonElement(state))
                }
            }
        }
        call.response.header("Content-Disposition", "attachment; filename=\"stalkerhek-backup.json\"")
        call.response.header("Content-Type", "application/json")
        call.respondText(backup.toString())
    }

    post("/api/backup/import") {
        val multipart = call.receiveMultipart()
        var jsonStr: String? = null
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                jsonStr = String(part.provider().toByteArray(), Charsets.UTF_8)
            }
        }

        if (jsonStr == null) {
            call.respondText("No file uploaded", status = HttpStatusCode.BadRequest)
            return@post
        }

        val backup = Json.parseToJsonElement(jsonStr).jsonObject
        val backupProfiles = backup["profiles"]?.jsonArray ?: emptyList()
        val backupFilters = backup["filters"]?.jsonObject ?: buildJsonObject {}

        // Track old profile IDs to new IDs assigned by the engine
        val idMap = mutableMapOf<Int, Int>()
        val existingIds = engine.profiles.value.map { it.id }.toMutableSet()

        // Create profiles
        for (pEl in backupProfiles) {
            val p = pEl.jsonObject
            // Assign a unique ID that won't conflict with existing profiles
            var newId = 1
            while (newId in existingIds) newId++
            existingIds.add(newId)
            val profile = ProfileConfig(
                id = newId,
                name = p["name"]?.jsonPrimitive?.contentOrNull ?: "",
                portalUrl = p["portalUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                mac = p["mac"]?.jsonPrimitive?.contentOrNull ?: "",
                username = p["username"]?.jsonPrimitive?.contentOrNull ?: "",
                password = p["password"]?.jsonPrimitive?.contentOrNull ?: "",
                hlsPort = p["hlsPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4600,
                proxyPort = p["proxyPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4800,
                timezone = p["timezone"]?.jsonPrimitive?.contentOrNull ?: "UTC",
                serialNumber = p["serialNumber"]?.jsonPrimitive?.contentOrNull ?: "0000000000000",
                deviceId = p["deviceId"]?.jsonPrimitive?.contentOrNull ?: "f".repeat(64),
                deviceId2 = p["deviceId2"]?.jsonPrimitive?.contentOrNull ?: "f".repeat(64),
                signature = p["signature"]?.jsonPrimitive?.contentOrNull ?: "f".repeat(64),
                model = p["model"]?.jsonPrimitive?.contentOrNull ?: "MAG254",
                watchdogInterval = p["watchdogInterval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
                deviceIdAuth = p["deviceIdAuth"]?.jsonPrimitive?.boolean ?: true,
                hlsEnabled = p["hlsEnabled"]?.jsonPrimitive?.boolean ?: true,
                proxyEnabled = p["proxyEnabled"]?.jsonPrimitive?.boolean ?: true,
                proxyRewrite = p["proxyRewrite"]?.jsonPrimitive?.boolean ?: true,
            )
            val oldId = p["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val created = runBlocking { engine.createProfile(profile) }
            created.getOrNull()?.let { newProfile ->
                idMap[oldId] = newProfile.id
            }
        }

        // Restore filter state for each profile
        backupFilters.forEach { (key, value) ->
            val oldId = key.toIntOrNull() ?: return@forEach
            val newId = idMap[oldId] ?: return@forEach
            val state = value.jsonObject

            // Disabled genres (stored as an array in the raw engine state)
            val disabledGenres = (state["disabled_genres"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
            for (gid in disabledGenres) {
                runBlocking {
                    engine.filterUpdate(
                        action = "toggle_genre",
                        profileId = newId,
                        genreId = gid,
                        disabled = true
                    )
                }
            }

            // Disabled channels (supports both array and object formats)
            val disabledChannels = when (val dc = state["disabled_channels"]) {
                is JsonObject -> dc.map { it.key }
                is JsonArray -> dc.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            for (cmd in disabledChannels) {
                runBlocking {
                    engine.filterUpdate(
                        action = "toggle_channel",
                        profileId = newId,
                        cmd = cmd,
                        disabled = true
                    )
                }
            }

            // Enabled channels (supports both array and object formats)
            val enabledChannels = when (val ec = state["enabled_channels"]) {
                is JsonObject -> ec.map { it.key }
                is JsonArray -> ec.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            for (cmd in enabledChannels) {
                runBlocking {
                    engine.filterUpdate(
                        action = "toggle_channel",
                        profileId = newId,
                        cmd = cmd,
                        disabled = false
                    )
                }
            }

            // Rename rules (prefix/suffix)
            val renamePrefix = state["rename_prefix"]?.jsonPrimitive?.contentOrNull ?: ""
            val renameSuffix = state["rename_suffix"]?.jsonPrimitive?.contentOrNull ?: ""
            if (renamePrefix.isNotEmpty() || renameSuffix.isNotEmpty()) {
                try {
                    val actionJson = buildJsonObject {
                        put("profile_id", newId.toString())
                        put("action", "rename")
                        put("rename_prefix", renamePrefix)
                        put("rename_suffix", renameSuffix)
                    }
                    RustEngineBridge.nativeFilterUpdate(actionJson.toString())
                    renamePrefixMap[newId] = renamePrefix
                    renameSuffixMap[newId] = renameSuffix
                } catch (_: Exception) {}
            }

            // Genre renames
            val genreRenames = (state["genre_renames"] as? JsonObject)
                ?: buildJsonObject {}
            genreRenames.forEach { (gid, name) ->
                runBlocking {
                    engine.filterUpdate(
                        action = "rename_genre",
                        profileId = newId,
                        genreRenameId = gid,
                        genreRenameName = name.jsonPrimitive.content
                    )
                }
                val renames = genreRenamesMap.getOrPut(newId) { mutableMapOf() }
                renames[gid] = name.jsonPrimitive.content
            }
        }

        call.respondRedirect("/dashboard")
    }
}
