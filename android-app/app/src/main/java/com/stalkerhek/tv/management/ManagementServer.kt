package com.stalkerhek.tv.management

import android.content.Context
import com.stalkerhek.tv.engine.EngineController
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*

object ManagementServer {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(context: Context, engineController: EngineController, port: Int = 4400) {
        if (server != null) return
        val srv = embeddedServer(CIO, port = port, host = "0.0.0.0",
            module = {
                install(ContentNegotiation) { json() }
                routing {
                    managementRoutes(engineController)
                }
            }
        )
        srv.start(wait = false)
        server = srv
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }
}
