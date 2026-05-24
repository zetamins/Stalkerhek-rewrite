package com.stalkerhek.api

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.rustclient.RustEngineClient
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

fun Routing.filterRoutes(profileService: ProfileService, authStore: AuthStore, filterStore: FilterStore, rustEngine: RustEngineClient) {
    val authEnabled = System.getenv("STALKERHEK_DISABLE_AUTH") != "1"

    fun auth(call: ApplicationCall) = checkAuth(call, authEnabled, authStore)

    suspend fun getChannelList(profileId: Int): List<ChannelInfo> {
        val cached = profileService.getChannelCache(profileId)
        if (cached.isNotEmpty()) return cached
        val channels = profileService.fetchChannels(profileId)
        return channels.onSuccess { profileService.setChannelCache(profileId, it) }.getOrDefault(emptyList())
    }

    fun applyRename(title: String, filter: ProfileFilterState): String {
        var t = title
        if (filter.renamePrefix.isNotEmpty()) {
            for (prefix in filter.renamePrefix.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (t.startsWith(prefix)) { t = t.removePrefix(prefix); break }
            }
        }
        if (filter.renameSuffix.isNotEmpty()) {
            for (suffix in filter.renameSuffix.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (t.endsWith(suffix)) { t = t.removeSuffix(suffix); break }
            }
        }
        return t.trim()
    }

    // List all genres (flat list, no category derivation)
    get("/api/filters/genres") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val channels = getChannelList(pid)
        val genreMap = linkedMapOf<String, GenreInfo>()
        val filter = filterStore.get(pid)

        for (ch in channels) {
            val gid = ch.genreId.ifEmpty { "Other" }
            val gname = filter.genreRenames[gid] ?: ch.genre.ifEmpty { "Other" }
            val gi = genreMap.getOrPut(gid) {
                GenreInfo(genreId = gid, category = "", name = gname)
            }
            val allowed = isChannelAllowed(ch, filter, pid)
            genreMap[gid] = gi.copy(
                total = gi.total + 1,
                enabled = gi.enabled + if (allowed) 1 else 0,
                blocked = gi.blocked + if (!allowed) 1 else 0,
                disabled = filter.disabledGenres[gid] == true,
            )
        }
        call.respondText(json.encodeToString(genreMap.values.sortedBy { it.name.lowercase() }))
    }

    get("/api/filters/channels") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("""{"error":"missing id"}""", ContentType.Application.Json)
        val query = call.request.queryParameters["query"]?.lowercase() ?: ""
        val genreId = call.request.queryParameters["genre_id"] ?: ""
        val state = call.request.queryParameters["state"]?.takeIf { it.isNotEmpty() } ?: "all"
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200

        val channels = getChannelList(pid)
        val filter = filterStore.get(pid)
        val filtered = channels.filter { ch ->
            (genreId.isEmpty() || ch.genreId == genreId || (genreId == "Other" && ch.genreId.isEmpty())) &&
            (query.isEmpty() || applyRename(ch.title, filter).lowercase().contains(query))
        }
        val withState = filtered.filter { ch ->
            val allowed = isChannelAllowed(ch, filter, pid)
            when (state) {
                "enabled" -> allowed
                "disabled" -> !allowed
                else -> true
            }
        }
        val page = withState.drop(offset).take(limit.coerceIn(1, 5000))
        val items = page.map { ch ->
            ch.copy(
                title = applyRename(ch.title, filter),
                genre = filter.genreRenames[ch.genreId] ?: ch.genre,
                enabled = isChannelAllowed(ch, filter, pid)
            )
        }
        call.respondText(json.encodeToString(ChannelsResponse(total = filtered.size, items = items)), ContentType.Application.Json)
    }

    post("/api/filters/toggle_genre") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull() ?: return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val gid = params["genre_id"]?.trim() ?: return@post call.respondText("missing genre_id", status = HttpStatusCode.BadRequest)
        val disabled = params["disabled"] in listOf("1", "true")
        val filter = filterStore.get(pid)
        val newDisabled = if (disabled) filter.disabledGenres + (gid to true) else filter.disabledGenres - gid
        filterStore.set(pid, filter.copy(disabledGenres = newDisabled))
        CoroutineScope(Dispatchers.IO).launch { rustEngine.filterUpdate(pid, "toggle_genre", genreId = gid, disabled = disabled) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/api/filters/toggle_channel") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull() ?: return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val cmd = params["cmd"]?.trim() ?: return@post call.respondText("missing cmd", status = HttpStatusCode.BadRequest)
        val disabled = params["disabled"] in listOf("1", "true")
        val filter = filterStore.get(pid)
        val newDisabled = if (disabled) filter.disabledChannels + (cmd to true) else filter.disabledChannels - cmd
        val newEnabled = if (disabled) filter.enabledChannels - cmd else filter.enabledChannels + (cmd to true)
        filterStore.set(pid, filter.copy(disabledChannels = newDisabled, enabledChannels = newEnabled))
        CoroutineScope(Dispatchers.IO).launch { rustEngine.filterUpdate(pid, "toggle_channel", cmd = cmd, disabled = disabled) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/api/filters/reset") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val pid = call.receiveParameters()["id"]?.toIntOrNull()
        if (pid != null) {
            filterStore.reset(pid)
            CoroutineScope(Dispatchers.IO).launch { rustEngine.filterUpdate(pid, "reset") }
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // VOD genres (from portal categories, no item loading)
    get("/api/filters/vod_genres") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val filter = filterStore.get(pid)
        val cats = rustEngine.getCategories(pid, "vod")
        val genres = cats.filter { it["id"] != "*" }.map { c ->
            val gid = c["id"] ?: ""
            val gname = filter.genreRenames[gid] ?: c["title"] ?: "Other"
            GenreInfo(genreId = gid, category = "", name = gname, disabled = filter.disabledGenres[gid] == true, total = 0, enabled = 0, blocked = 0)
        }
        call.respondText(json.encodeToString(genres.sortedBy { it.name.lowercase() }))
    }

    // Series genres (from portal categories, no item loading)
    get("/api/filters/series_genres") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val filter = filterStore.get(pid)
        val cats = rustEngine.getCategories(pid, "series")
        val genres = cats.filter { it["id"] != "*" }.map { c ->
            val gid = c["id"] ?: ""
            val gname = filter.genreRenames[gid] ?: c["title"] ?: "Other"
            GenreInfo(genreId = gid, category = "", name = gname, disabled = filter.disabledGenres[gid] == true, total = 0, enabled = 0, blocked = 0)
        }
        call.respondText(json.encodeToString(genres.sortedBy { it.name.lowercase() }))
    }

    // Rename rules: get
    get("/api/filters/rename_rules") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val filter = filterStore.get(pid)
        call.respondText(json.encodeToString(mapOf(
            "renamePrefix" to filter.renamePrefix,
            "renameSuffix" to filter.renameSuffix,
        )), ContentType.Application.Json)
    }

    // Rename rules: save
    post("/api/filters/rename_rules") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull() ?: return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val filter = filterStore.get(pid)
        val updated = filter.copy(
            renamePrefix = params["renamePrefix"]?.takeIf { it.isNotBlank() } ?: "",
            renameSuffix = params["renameSuffix"]?.takeIf { it.isNotBlank() } ?: "",
        )
        filterStore.set(pid, updated)
        // Sync rename rules to Rust engine
        CoroutineScope(Dispatchers.IO).launch {
            rustEngine.filterUpdate(pid, "rename", renamePrefix = updated.renamePrefix, renameSuffix = updated.renameSuffix)
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Genre rename: get current renames
    get("/api/filters/genre_renames") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val pid = call.request.queryParameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val filter = filterStore.get(pid)
        call.respondText(json.encodeToString(mapOf(
            "genreRenames" to filter.genreRenames,
        )), ContentType.Application.Json)
    }

    // Genre rename: save/remove a rename
    post("/api/filters/rename_genre") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val params = call.receiveParameters()
        val pid = params["id"]?.toIntOrNull() ?: return@post call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val gid = params["genre_id"]?.trim() ?: return@post call.respondText("missing genre_id", status = HttpStatusCode.BadRequest)
        val name = params["name"]?.trim() ?: ""
        val filter = filterStore.get(pid)
        val updated = filter.copy(
            genreRenames = if (name.isEmpty()) filter.genreRenames - gid else filter.genreRenames + (gid to name)
        )
        filterStore.set(pid, updated)
        // Sync to Rust engine
        CoroutineScope(Dispatchers.IO).launch {
            rustEngine.filterUpdate(pid, "rename_genre", genreRenameId = gid, genreRenameName = name.ifEmpty { null })
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }
}

fun isChannelAllowed(ch: ChannelInfo, filter: ProfileFilterState, profileId: Int): Boolean {
    if (filter.disabledChannels[ch.cmd] == true) return false
    if (ch.genreId.isNotEmpty() && filter.disabledGenres[ch.genreId] == true) {
        return filter.enabledChannels[ch.cmd] == true
    }
    return true
}
