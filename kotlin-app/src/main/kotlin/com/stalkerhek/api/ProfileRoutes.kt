package com.stalkerhek.api

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

fun Routing.profileRoutes(profileService: ProfileService, authStore: AuthStore, logService: LogService) {
    val authEnabled = System.getenv("STALKERHEK_DISABLE_AUTH") != "1"

    fun auth(call: ApplicationCall) = checkAuth(call, authEnabled, authStore)

    get("/dashboard") {
        val profiles = profileService.listProfiles()
        val statuses = profileService.listStatuses()
        call.respondText(renderDashboardPage(profiles, statuses), ContentType.Text.Html)
    }

    get("/") {
        call.respondRedirect("/dashboard")
    }

    get("/profiles") {
        call.respondRedirect("/dashboard")
    }

    // Web form: create or update profile
    post("/profiles") {
        val params = call.receiveParameters()
        val editId = params["edit_id"]?.toIntOrNull()
        val existingProfile = if (editId != null) profileService.getProfile(editId) else null
        val profile = Profile(
            name = params["name"]?.trim() ?: "",
            portalUrl = params["portal"]?.trim() ?: "",
            mac = params["mac"]?.trim()?.uppercase() ?: "",
            username = params["username"]?.trim() ?: "",
            password = params["password"]?.takeIf { it.isNotEmpty() } ?: existingProfile?.password ?: "",
            hlsPort = params["hls_port"]?.toIntOrNull() ?: 0,
            proxyPort = params["proxy_port"]?.toIntOrNull() ?: 0,
            timezone = params["timezone"]?.takeIf { it.isNotBlank() } ?: "UTC",
            serialNumber = params["serial_number"]?.takeIf { it.isNotBlank() } ?: "0000000000000",
            deviceId = params["device_id"]?.takeIf { it.isNotBlank() } ?: "f".repeat(64),
            deviceId2 = params["device_id2"]?.takeIf { it.isNotBlank() } ?: "f".repeat(64),
            model = params["model"]?.takeIf { it.isNotBlank() } ?: "MAG254",
            deviceIdAuth = params["device_id_auth"] != "0",
            watchdogInterval = params["watchdog_time"]?.toIntOrNull() ?: 5,
        )
        if (profile.portalUrl.isBlank() || profile.mac.isBlank()) {
            call.respondText("portal_url and mac required", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (editId != null) {
            profileService.updateProfile(editId, profile)
        } else {
            profileService.createProfile(profile)
        }
        call.respondRedirect("/dashboard")
    }

    get("/setup") {
        call.respondText("""<!doctype html><html><body>Setup page</body></html>""", ContentType.Text.Html)
    }

    // API: list profiles
    get("/api/profiles") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val profiles = profileService.listProfiles()
        call.respondText(json.encodeToString(profiles), ContentType.Application.Json)
    }

    // API: get single profile
    get("/api/profiles/{id}") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val profile = profileService.getProfile(id) ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
        call.respondText(json.encodeToString(profile), ContentType.Application.Json)
    }

    // API: create profile
    post("/api/profiles") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val params = call.receiveParameters()
        val profile = Profile(
            name = params["name"]?.trim() ?: "",
            portalUrl = params["portal_url"]?.trim() ?: "",
            mac = params["mac"]?.trim()?.uppercase() ?: "",
            username = params["username"]?.trim() ?: "",
            password = params["password"] ?: "",
            hlsPort = params["hls_port"]?.toIntOrNull() ?: 0,
            proxyPort = params["proxy_port"]?.toIntOrNull() ?: 0,
            timezone = params["timezone"]?.takeIf { it.isNotBlank() } ?: "UTC",
            serialNumber = params["serial_number"]?.takeIf { it.isNotBlank() } ?: "0000000000000",
            deviceId = params["device_id"]?.takeIf { it.isNotBlank() } ?: "f".repeat(64),
            deviceId2 = params["device_id2"]?.takeIf { it.isNotBlank() } ?: "f".repeat(64),
            model = params["model"]?.takeIf { it.isNotBlank() } ?: "MAG254",
            deviceIdAuth = params["device_id_auth"] != "0",
            watchdogInterval = params["watchdog_interval"]?.toIntOrNull() ?: 5,
        )
        if (profile.portalUrl.isBlank() || profile.mac.isBlank()) {
            call.respondText("portal_url and mac required", status = HttpStatusCode.BadRequest)
            return@post
        }
        val saved = profileService.createProfile(profile)
        call.respondText(json.encodeToString(saved), ContentType.Application.Json)
    }

    // API: update profile
    put("/api/profiles/{id}") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@put }
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respondText("bad request", status = HttpStatusCode.BadRequest)
        val existing = profileService.getProfile(id) ?: return@put call.respondText("not found", status = HttpStatusCode.NotFound)
        val params = call.receiveParameters()
        val updated = existing.copy(
            name = params["name"]?.trim() ?: existing.name,
            portalUrl = params["portal_url"]?.trim() ?: existing.portalUrl,
            mac = (params["mac"]?.trim()?.uppercase()) ?: existing.mac,
            username = params["username"]?.trim() ?: existing.username,
            password = params["password"] ?: existing.password,
            hlsPort = params["hls_port"]?.toIntOrNull() ?: existing.hlsPort,
            proxyPort = params["proxy_port"]?.toIntOrNull() ?: existing.proxyPort,
            timezone = params["timezone"]?.takeIf { it.isNotBlank() } ?: existing.timezone,
        )
        profileService.updateProfile(id, updated)
        call.respondText(json.encodeToString(updated), ContentType.Application.Json)
    }

    // API: delete profile
    delete("/api/profiles/{id}") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@delete }
        val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respondText("bad request", status = HttpStatusCode.BadRequest)
        profileService.stopProfile(id)
        profileService.deleteProfile(id)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // API: start profile
    post("/api/profiles/start") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val id = call.receiveParameters()["id"]?.toIntOrNull() ?: return@post call.respondText("id required", status = HttpStatusCode.BadRequest)
        GlobalScope.launch { profileService.startProfile(id) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Web form: start profile
    post("/profiles/start") {
        if (!auth(call)) { call.respondRedirect("/login"); return@post }
        val id = call.receiveParameters()["id"]?.toIntOrNull()
        if (id != null) GlobalScope.launch { profileService.startProfile(id) }
        call.respondRedirect("/dashboard")
    }

    // API: stop profile
    post("/api/profiles/stop") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
        val id = call.receiveParameters()["id"]?.toIntOrNull() ?: return@post call.respondText("id required", status = HttpStatusCode.BadRequest)
        profileService.stopProfile(id)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Web form: stop profile
    post("/profiles/stop") {
        if (!auth(call)) { call.respondRedirect("/login"); return@post }
        val id = call.receiveParameters()["id"]?.toIntOrNull()
        if (id != null) profileService.stopProfile(id)
        call.respondRedirect("/dashboard")
    }

    // Web form: delete profile
    post("/profiles/delete") {
        if (!auth(call)) { call.respondRedirect("/login"); return@post }
        val id = call.receiveParameters()["id"]?.toIntOrNull()
        if (id != null) {
            profileService.stopProfile(id)
            profileService.deleteProfile(id)
        }
        call.respondRedirect("/dashboard")
    }

    // API: profile status (all)
    get("/api/profile_status") {
        if (!auth(call)) { call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
        val statuses = profileService.listStatuses()
        call.respondText(json.encodeToString(statuses), ContentType.Application.Json)
    }
}
