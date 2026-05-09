package com.stalkerhek.persistence

import com.stalkerhek.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

class ProfileStore(private val filePath: String) {
    private var profiles = mutableListOf<Profile>()
    private var nextId = 1

    init { load() }

    fun list(): List<Profile> = synchronized(this) { profiles.toList() }

    fun get(id: Int): Profile? = synchronized(this) { profiles.find { it.id == id } }

    fun add(profile: Profile): Profile = synchronized(this) {
        val p = profile.copy(id = nextId++)
        profiles.add(p)
        save()
        p
    }

    fun update(id: Int, profile: Profile): Profile? = synchronized(this) {
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized null
        val p = profile.copy(id = id)
        profiles[idx] = p
        save()
        p
    }

    fun delete(id: Int): Boolean = synchronized(this) {
        val removed = profiles.removeAll { it.id == id }
        if (removed) save()
        removed
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val data = file.readText()
            val loaded: List<Profile> = json.decodeFromString(data)
            profiles = loaded.toMutableList()
            nextId = (profiles.maxOfOrNull { it.id } ?: 0) + 1
        } catch (e: Exception) {
            println("Failed to load profiles: ${e.message}")
        }
    }

    private fun save() {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("profiles", ".json", file.parentFile)
        tmp.writeText(json.encodeToString(profiles))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

class AuthStore(private val filePath: String) {
    private var users = mutableMapOf<String, User>()

    init { load() }

    fun hasUsers(): Boolean = synchronized(this) { users.isNotEmpty() }

    fun getUser(username: String): User? = synchronized(this) { users[username] }

    fun addUser(user: User): Boolean = synchronized(this) {
        if (users.containsKey(user.username)) return@synchronized false
        users[user.username] = user
        save()
        true
    }

    fun updateUser(username: String, update: (User) -> User): Boolean = synchronized(this) {
        val user = users[username] ?: return@synchronized false
        users[username] = update(user)
        save()
        true
    }

    fun deleteUser(username: String): Boolean = synchronized(this) {
        if (!users.containsKey(username)) return@synchronized false
        users.remove(username)
        save()
        true
    }

    fun listUsers(): List<Map<String, Any?>> = synchronized(this) {
        users.map { (name, user) ->
            mapOf(
                "username" to name,
                "created_at" to user.createdAt,
                "last_login" to user.lastLogin,
                "has_security_question" to (user.securityQuestion.isNotEmpty()),
            )
        }
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val data = file.readText()
            val loaded: Map<String, User> = json.decodeFromString(data)
            users = loaded.toMutableMap()
        } catch (e: Exception) {
            println("Failed to load users: ${e.message}")
        }
    }

    fun save() {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("auth", ".json", file.parentFile)
        tmp.writeText(json.encodeToString(users))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

class FilterStore(private val filePath: String) {
    private var filters = mutableMapOf<Int, ProfileFilterState>()

    init { load() }

    fun get(profileId: Int): ProfileFilterState =
        synchronized(this) { filters[profileId] ?: ProfileFilterState() }

    fun set(profileId: Int, filter: ProfileFilterState) = synchronized(this) {
        filters[profileId] = filter
        save()
    }

    fun delete(profileId: Int) = synchronized(this) {
        filters.remove(profileId)
        save()
    }

    fun reset(profileId: Int) = synchronized(this) {
        filters.remove(profileId)
        save()
    }

    fun snapshot(): Map<Int, ProfileFilterState> = synchronized(this) { filters.toMap() }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val data = file.readText()
            val loaded: Map<Int, ProfileFilterState> = json.decodeFromString(data)
            filters = loaded.toMutableMap()
        } catch (e: Exception) {
            println("Failed to load filters: ${e.message}")
        }
    }

    private fun save() {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("filters", ".json", file.parentFile)
        tmp.writeText(json.encodeToString(filters))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
