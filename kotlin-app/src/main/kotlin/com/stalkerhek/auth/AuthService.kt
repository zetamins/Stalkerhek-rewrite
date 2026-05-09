package com.stalkerhek.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.stalkerhek.models.User
import com.stalkerhek.persistence.AuthStore
import java.security.SecureRandom
import java.util.*

class AuthService(private val store: AuthStore) {
    private val secureRandom = SecureRandom()

    fun hashPassword(password: String): String =
        BCrypt.withDefaults().hashToString(12, password.toCharArray())

    fun verifyPassword(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashAnswer(answer: String): String {
        val normalized = answer.trim().lowercase()
        return BCrypt.withDefaults().hashToString(6, normalized.toCharArray())
    }

    fun verifyAnswer(answer: String, hash: String): Boolean {
        val normalized = answer.trim().lowercase()
        return BCrypt.verifyer().verify(normalized.toCharArray(), hash).verified
    }
}
