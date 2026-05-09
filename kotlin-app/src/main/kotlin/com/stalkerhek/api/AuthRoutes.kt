package com.stalkerhek.api

import com.stalkerhek.auth.AuthService
import com.stalkerhek.models.*
import com.stalkerhek.persistence.AuthStore
import com.stalkerhek.persistence.json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.*

fun Routing.authRoutes(authStore: AuthStore) {
    val authService = AuthService(authStore)
    val authEnabled = System.getenv("STALKERHEK_DISABLE_AUTH") != "1"
    val allowRegistration = System.getenv("STALKERHEK_ALLOW_REGISTER") == "1"
    val trustedSubnets = listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128")

    fun isTrustedIP(request: ApplicationRequest): Boolean {
        val ip = request.local.remoteHost ?: "unknown"
        return ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1" || ip.startsWith("10.") ||
                ip.startsWith("192.168.") || ip.startsWith("172.16.") || ip.startsWith("172.17.")
    }

    fun getSessionUsername(call: ApplicationCall): String? {
        return call.sessions.get<SessionData>()?.username?.takeIf { it.isNotEmpty() }
    }

    fun checkAuth(call: ApplicationCall): Boolean {
        if (!authEnabled) return true
        if (isTrustedIP(call.request)) return true
        return getSessionUsername(call) != null
    }

    // Public pages
    get("/login") {
        if (!authEnabled || !authStore.hasUsers()) {
            call.respondRedirect("/dashboard")
            return@get
        }
        call.respondText(renderLoginPage(""), ContentType.Text.Html)
    }

    get("/register") {
        if (!allowRegistration && authStore.hasUsers()) {
            call.respondRedirect("/login")
            return@get
        }
        call.respondText(renderRegisterPage(""), ContentType.Text.Html)
    }

    get("/forgot-password") {
        if (!authEnabled || !authStore.hasUsers()) {
            call.respondRedirect("/dashboard")
            return@get
        }
        call.respondText(renderForgotPasswordPage("", null), ContentType.Text.Html)
    }

    get("/reset-password") {
        val token = call.request.queryParameters["token"] ?: ""
        call.respondText(renderResetPasswordPage("", token), ContentType.Text.Html)
    }

    // Account page
    get("/account") {
        val username = getSessionUsername(call) ?: ""
        val user = if (username.isNotEmpty()) authStore.getUser(username) else null
        call.respondText(
            renderAccountPage(username, user?.securityQuestion ?: "", authEnabled, true),
            ContentType.Text.Html
        )
    }

    // API routes
    post("/api/login") {
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""
        val user = authStore.getUser(username)

        if (user == null || !authService.verifyPassword(password, user.passwordHash)) {
            if (call.request.header("X-Requested-With") == "XMLHttpRequest") {
                call.respondText("""{"error":"invalid credentials"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            } else {
                call.respondText(renderLoginPage("Invalid username or password"), ContentType.Text.Html)
            }
            return@post
        }

        call.sessions.set(SessionData(username = username))
        authStore.updateUser(username) { it.copy(lastLogin = System.currentTimeMillis() / 1000) }

        if (call.request.header("X-Requested-With") == "XMLHttpRequest") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        } else {
            call.respondRedirect("/dashboard")
        }
    }

    post("/api/register") {
        if (!allowRegistration && authStore.hasUsers() && getSessionUsername(call) == null) {
            call.respondText("registration disabled", status = HttpStatusCode.Forbidden)
            return@post
        }
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""
        val passwordConfirm = params["password_confirm"] ?: ""
        val securityQ = params["security_question"] ?: ""
        val securityA = params["security_answer"]?.trim() ?: ""
        val customQ = params["custom_question"] ?: ""

        if (username.length < 2 || password.length < 4) {
            call.respondText(renderRegisterPage("Username required, password min 4 chars"), ContentType.Text.Html)
            return@post
        }
        if (password != passwordConfirm) {
            call.respondText(renderRegisterPage("Passwords do not match"), ContentType.Text.Html)
            return@post
        }
        if (authStore.getUser(username) != null) {
            call.respondText(renderRegisterPage("Username already exists"), ContentType.Text.Html)
            return@post
        }

        val hash = authService.hashPassword(password)
        val finalQuestion = if (securityQ == "What is your custom security question" && customQ.isNotEmpty()) customQ else securityQ
        val answerHash = if (securityA.isNotEmpty()) authService.hashAnswer(securityA) else ""

        val user = User(
            username = username,
            passwordHash = hash,
            securityQuestion = finalQuestion,
            securityAnswerHash = answerHash,
            allowCustomQuestion = securityQ == "What is your custom security question" && customQ.isNotEmpty(),
        )
        authStore.addUser(user)

        if (getSessionUsername(call) == null) {
            call.sessions.set(SessionData(username = username))
        }

        if (call.request.header("X-Requested-With") == "XMLHttpRequest") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        } else {
            call.respondRedirect(if (getSessionUsername(call) != null) "/account?created=success" else "/dashboard")
        }
    }

    post("/api/logout") {
        call.sessions.clear("stalkerhek_session")
        if (call.request.header("X-Requested-With") == "XMLHttpRequest") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        } else {
            call.respondRedirect("/login")
        }
    }

    post("/api/forgot-password") {
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val answer = params["answer"]?.trim() ?: ""
        val user = authStore.getUser(username)

        if (user == null || user.securityQuestion.isEmpty()) {
            call.respondText(
                renderForgotPasswordPage("If this account exists and has a security question, you can reset your password.", null),
                ContentType.Text.Html
            )
            return@post
        }

        if (answer.isNotEmpty()) {
            if (!authService.verifyAnswer(answer, user.securityAnswerHash)) {
                call.respondText(
                    renderForgotPasswordPage("If this account exists and has a security question, you can reset your password.", null),
                    ContentType.Text.Html
                )
                return@post
            }
            val token = authService.generateToken()
            call.sessions.set("reset_token", token)
            call.respondRedirect("/reset-password?token=$token")
        } else {
            call.respondText(renderForgotPasswordPage("", user), ContentType.Text.Html)
        }
    }

    post("/api/reset-password") {
        val params = call.receiveParameters()
        val token = params["token"] ?: ""
        val newPassword = params["new_password"] ?: ""
        val confirmPassword = params["confirm_password"] ?: ""

        if (newPassword.length < 4 || newPassword != confirmPassword) {
            call.respondText(renderResetPasswordPage("Invalid password", token), ContentType.Text.Html)
            return@post
        }
        call.respondText(renderLoginPage("Password reset successful. Please log in."), ContentType.Text.Html)
    }

    get("/api/auth/status") {
        val username = getSessionUsername(call)
        call.respondText(json.encodeToString(mapOf(
            "enabled" to authEnabled,
            "authenticated" to (username != null),
            "username" to (username ?: ""),
            "has_users" to authStore.hasUsers(),
            "allow_registration" to (allowRegistration || !authStore.hasUsers()),
        )))
    }

    get("/api/users") {
        if (authEnabled && getSessionUsername(call) == null) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        val users = authStore.listUsers()
        call.respondText(json.encodeToString(mapOf("users" to users, "count" to users.size)))
    }
}
