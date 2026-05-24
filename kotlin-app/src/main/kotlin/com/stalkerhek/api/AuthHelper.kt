package com.stalkerhek.api

import com.stalkerhek.models.SessionData
import com.stalkerhek.persistence.AuthStore
import io.ktor.server.application.*
import io.ktor.server.sessions.*

/**
 * Shared auth helpers used across all route files.
 * Duplicating these as local functions in every route file led to C2 (unprotected routes).
 */

fun getSessionUsername(call: ApplicationCall): String? {
    val session = call.sessions.get<SessionData>() ?: return null
    if (session.expiresAt < java.time.Instant.now().epochSecond) return null
    return session.username.takeIf { it.isNotEmpty() }
}

fun isTrustedIP(call: ApplicationCall): Boolean {
    val ip = call.request.local.remoteHost ?: return false
    return ip == "127.0.0.1" ||
        ip == "0:0:0:0:0:0:0:1" ||
        ip == "::1" ||
        ip.startsWith("10.") ||
        ip.startsWith("192.168.") ||
        // Full RFC 1918 172.16.0.0/12 range: 172.16.x through 172.31.x
        (ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true)
}

fun checkAuth(call: ApplicationCall, authEnabled: Boolean, authStore: AuthStore): Boolean {
    if (!authEnabled) return true
    if (!authStore.hasUsers()) return true
    if (isTrustedIP(call)) return true
    return getSessionUsername(call) != null
}
