package com.stalkerhek

import com.stalkerhek.api.*
import com.stalkerhek.auth.*
import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.rustclient.RustEngineClient
import com.stalkerhek.services.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    val persistDir = System.getenv("STALKERHEK_DATA_DIR") ?: "data"
    File(persistDir).mkdirs()

    val profilesFile = System.getenv("STALKERHEK_PROFILES_FILE") ?: "$persistDir/profiles.json"
    val authFile = System.getenv("STALKERHEK_AUTH_FILE") ?: "$persistDir/auth.json"
    val filtersFile = "$persistDir/filters.json"
    val rustApiUrl = System.getenv("STALKERHEK_ENGINE_URL") ?: "http://127.0.0.1:9900"

    val profileStore = ProfileStore(profilesFile)
    val authStore = AuthStore(authFile)
    val filterStore = FilterStore(filtersFile)
    val logService = LogService()
    val rustEngine = RustEngineClient(rustApiUrl)
    val profileService = ProfileService(profileStore, rustEngine, filterStore, logService)

    // Sync persisted filters to Rust engine at startup
    val snapshot = filterStore.snapshot()
    if (snapshot.isNotEmpty()) {
        runBlocking { rustEngine.syncFilters(snapshot) }
        println("Synced ${snapshot.size} profiles' filters to Rust engine")
    }

    // Refresh profile statuses from engine (handles engine restart with auto-start)
    runBlocking { profileService.refreshStatusesFromEngine() }

    embeddedServer(Netty, port = 4400, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }
        install(Sessions) {
            cookie<SessionData>("stalkerhek_session") {
                cookie.path = "/"
                cookie.httpOnly = true
                cookie.sameSite = SameSite.Strict
                transform(SessionTransportTransformerMessageAuthentication("stalkerhek-secret-key-change-in-prod".toByteArray()))
            }
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.application.log.error("Unhandled exception", cause)
                call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            authRoutes(authStore)
            profileRoutes(profileService, authStore, logService)
            filterRoutes(profileService, authStore, filterStore, rustEngine)
            logRoutes(logService, authStore)
            healthRoutes(profileService)
            settingsRoutes()
            staticRoutes()
        }
    }.start(wait = true)
}
