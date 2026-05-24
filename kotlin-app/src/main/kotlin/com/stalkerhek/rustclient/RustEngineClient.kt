package com.stalkerhek.rustclient

import com.stalkerhek.models.ChannelInfo
import com.stalkerhek.models.ProfileFilterState
import com.stalkerhek.persistence.json
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class RustEngineClient(private val baseUrl: String) {
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
        expectSuccess = false
    }

    suspend fun health(): Boolean = try {
        val resp = client.get("$baseUrl/health")
        resp.status == HttpStatusCode.OK
    } catch (e: Exception) { false }

    suspend fun startProfile(config: RustProfileConfig): Result<RustStartResponse> = runCatching {
        val resp = client.post("$baseUrl/api/v1/profile/${config.id}/start") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString<RustProfileConfig>(config))
        }
        if (resp.status.value >= 300) {
            throw Exception("Rust engine returned ${resp.status.value}: ${resp.bodyAsText().take(200)}")
        }
        json.decodeFromString<RustStartResponse>(resp.bodyAsText())
    }

    suspend fun stopProfile(id: Int): Boolean = try {
        val resp = client.post("$baseUrl/api/v1/profile/$id/stop")
        resp.status == HttpStatusCode.OK
    } catch (e: Exception) { false }

    suspend fun getProfileStatus(id: Int): RustProfileStatus? {
        return try {
            val resp = client.get("$baseUrl/api/v1/profile/$id/status")
            if (resp.status.value < 300) json.decodeFromString<RustProfileStatus>(resp.bodyAsText()) else null
        } catch (e: Exception) { null }
    }

    suspend fun getChannels(id: Int, type: String = "itv"): List<ChannelInfo> {
        return try {
            val resp = client.get("$baseUrl/api/v1/profile/$id/channels?type=$type")
            val text = resp.bodyAsText()
            if (text == "null") emptyList()
            else json.decodeFromString<List<ChannelInfo>>(text)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun getCategories(id: Int, type: String = "vod"): List<Map<String, String>> {
        return try {
            val resp = client.get("$baseUrl/api/v1/profile/$id/categories?type=$type")
            json.decodeFromString<List<Map<String, String>>>(resp.bodyAsText())
        } catch (e: Exception) { emptyList() }
    }

    suspend fun createProfile(config: RustProfileConfig): Result<RustProfileConfig> = runCatching {
        val resp = client.post("$baseUrl/api/v1/profile") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString<RustProfileConfig>(config))
        }
        if (resp.status.value >= 300) {
            throw Exception("Rust engine returned ${resp.status.value}: ${resp.bodyAsText().take(200)}")
        }
        json.decodeFromString<RustProfileConfig>(resp.bodyAsText())
    }

    suspend fun deleteProfile(id: Int): Boolean = try {
        val resp = client.delete("$baseUrl/api/v1/profile/$id")
        resp.status == HttpStatusCode.OK
    } catch (e: Exception) { false }

    suspend fun filterUpdate(profileId: Int, action: String, genreId: String? = null, cmd: String? = null, disabled: Boolean? = null, renamePrefix: String? = null, renameSuffix: String? = null, genreRenameId: String? = null, genreRenameName: String? = null): Boolean = try {
        val body = buildMap<String, String> {
            put("profile_id", profileId.toString())
            put("action", action)
            genreId?.let { put("genre_id", it) }
            cmd?.let { put("cmd", it) }
            disabled?.let { put("disabled", if (it) "1" else "0") }
            renamePrefix?.let { put("rename_prefix", it) }
            renameSuffix?.let { put("rename_suffix", it) }
            genreRenameId?.let { put("genre_rename_id", it) }
            genreRenameName?.let { put("genre_rename_name", it) }
        }
        val resp = client.post("$baseUrl/api/v1/filters") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }
        resp.status == HttpStatusCode.OK
    } catch (e: Exception) { false }

    suspend fun syncFilters(snapshot: Map<Int, ProfileFilterState>): Boolean = try {
        val resp = client.post("$baseUrl/api/v1/filters/sync") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(snapshot))
        }
        resp.status == HttpStatusCode.OK
    } catch (e: Exception) { false }

    fun close() { client.close() }
}

@Serializable
data class RustProfileConfig(
    val id: Int = 0,
    val name: String,
    val portal_url: String,
    val mac: String,
    val username: String = "",
    val password: String = "",
    val hls_port: Int = 0,
    val proxy_port: Int = 0,
    val timezone: String = "UTC",
    val model: String = "MAG254",
    val device_id_auth: Boolean = true,
    val hls_enabled: Boolean = true,
    val proxy_enabled: Boolean = true,
    val proxy_rewrite: Boolean = true,
    val serial_number: String = "0000000000000",
    val device_id: String = "f".repeat(64),
    val device_id2: String = "f".repeat(64),
    val signature: String = "f".repeat(64),
    val watchdog_interval: Int = 5,
)

@Serializable
data class RustStartResponse(
    val ok: Boolean = false,
    val id: Int = 0,
    val channels: Int = 0,
)

@Serializable
data class RustProfileStatus(
    val id: Int = 0,
    val phase: String = "idle",
    val message: String = "",
    val channelsCount: Int = 0,
    val hlsAddr: String = "",
    val proxyAddr: String = "",
    val running: Boolean = false,
)
