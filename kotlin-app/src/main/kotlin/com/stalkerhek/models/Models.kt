package com.stalkerhek.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Profile(
    val id: Int = 0,
    val name: String = "",
    val portalUrl: String = "",
    val mac: String = "",
    val username: String = "",
    val password: String = "",
    val hlsPort: Int = 4600,
    val proxyPort: Int = 4800,
    val timezone: String = "UTC",
    val serialNumber: String = "0000000000000",
    val deviceId: String = "f".repeat(64),
    val deviceId2: String = "f".repeat(64),
    val signature: String = "f".repeat(64),
    val model: String = "MAG254",
    val watchdogInterval: Int = 5,
    val deviceIdAuth: Boolean = true,
    val hlsEnabled: Boolean = true,
    val proxyEnabled: Boolean = true,
    val proxyRewrite: Boolean = true,
)

@Serializable
data class ProfileStatus(
    val id: Int = 0,
    val name: String = "",
    val phase: String = "idle",
    val message: String = "Not started",
    val channels: Int = 0,
    val hls: String = "",
    val proxy: String = "",
    val running: Boolean = false,
    val busy: Boolean = false,
)

@Serializable
data class User(
    val username: String,
    val passwordHash: String = "",
    val securityQuestion: String = "",
    val securityAnswerHash: String = "",
    val allowCustomQuestion: Boolean = false,
    val createdAt: Long = Instant.now().epochSecond,
    val lastLogin: Long = 0,
)

@Serializable
data class SessionData(
    val username: String = "",
    val createdAt: Long = Instant.now().epochSecond,
    val expiresAt: Long = Instant.now().epochSecond + 604800,
)

@Serializable
data class RuntimeSettings(
    val playlistDelaySegments: Int = 3,
    val responseHeaderTimeoutSeconds: Int = 25,
    val maxIdleConnsPerHost: Int = 128,
)

@Serializable
data class ChannelInfo(
    val title: String,
    val cmd: String,
    @SerialName("genre_id") val genreId: String,
    val genre: String,
    val enabled: Boolean = true,
)

@Serializable
data class CategoryInfo(
    val category: String,
    val total: Int = 0,
    val enabled: Int = 0,
    val blocked: Int = 0,
    val genres: Int = 0,
    val disabledGenres: Int = 0,
)

@Serializable
data class GenreInfo(
    val genreId: String,
    val category: String,
    val name: String,
    val total: Int = 0,
    val disabled: Boolean = false,
    val enabled: Int = 0,
    val blocked: Int = 0,
)

@Serializable
data class ChannelsResponse(
    val total: Int = 0,
    val items: List<ChannelInfo> = emptyList(),
)

@Serializable
data class HealthResponse(
    val status: String,
    val uptime: String,
    val version: String = "dev",
    val profiles: Int = 0,
    val running: Int = 0,
    val errors: Int = 0,
    val webui: Boolean = true,
    val checks: Map<String, String> = emptyMap(),
    val timestamp: String = Instant.now().toString(),
)

@Serializable
data class LogEntry(
    val id: Long = 0,
    val ts: String = Instant.now().toString(),
    val msg: String = "",
)

@Serializable
data class ProfileFilterState(
    val disabledGenres: Map<String, Boolean> = emptyMap(),
    val disabledChannels: Map<String, Boolean> = emptyMap(),
    val enabledChannels: Map<String, Boolean> = emptyMap(),
    val renamePrefix: String = "",
    val renameSuffix: String = "",
    val genreRenames: Map<String, String> = emptyMap(),
    val version: Long = 0,
)
