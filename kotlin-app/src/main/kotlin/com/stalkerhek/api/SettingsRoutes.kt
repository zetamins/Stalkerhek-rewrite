package com.stalkerhek.api

import com.stalkerhek.models.RuntimeSettings
import com.stalkerhek.persistence.json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString

var currentSettings = RuntimeSettings()

fun Routing.settingsRoutes() {
    get("/api/settings") {
        call.respondText(json.encodeToString(currentSettings), ContentType.Application.Json)
    }

    post("/api/settings") {
        val params = call.receiveParameters()
        var s = currentSettings
        params["playlist_delay_segments"]?.toIntOrNull()?.let { v ->
            s = s.copy(playlistDelaySegments = v.coerceIn(0, 40))
        }
        params["response_header_timeout_seconds"]?.toIntOrNull()?.let { v ->
            s = s.copy(responseHeaderTimeoutSeconds = v.coerceIn(1, 120))
        }
        params["max_idle_conns_per_host"]?.toIntOrNull()?.let { v ->
            s = s.copy(maxIdleConnsPerHost = v.coerceIn(2, 256))
        }
        currentSettings = s
        call.respondText("""{"ok":true,"settings":${json.encodeToString(s)}}""", ContentType.Application.Json)
    }
}
