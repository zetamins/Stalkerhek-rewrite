package com.stalkerhek.tv.engine

import android.content.Context
import com.stalkerhek.tv.management.ManagementServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed class EngineState {
    data object Uninitialized : EngineState()
    data object Initializing : EngineState()
    data class Ready(val profilesLoaded: Int) : EngineState()
    data class Error(val message: String) : EngineState()
}

@Serializable
data class ProfileConfig(
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
    val phase: String = "idle",
    val message: String = "Not started",
    @SerialName("channels_count") val channelsCount: Int = 0,
    @SerialName("hls_addr") val hlsAddr: String = "",
    @SerialName("proxy_addr") val proxyAddr: String = "",
    val running: Boolean = false,
)

@Serializable
data class Channel(
    val cmd: String = "",
    val title: String = "",
    val genre: String = "",
    val genreId: String = "",
    val logo: String = "",
    val enabled: Boolean = true,
)

object EngineController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileConfig>>(emptyList())
    val profiles: StateFlow<List<ProfileConfig>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<ProfileStatus?>(null)
    val activeProfile: StateFlow<ProfileStatus?> = _activeProfile.asStateFlow()

    private val _activeProfileId = MutableStateFlow<Int>(0)
    val activeProfileId: StateFlow<Int> = _activeProfileId.asStateFlow()

    private var initCalled = false

    fun init(dataDir: String, appContext: Context) {
        if (initCalled) return
        initCalled = true
        _engineState.value = EngineState.Initializing
        scope.launch {
            try {
                val result = RustEngineBridge.nativeInit(dataDir)
                val initResp = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(result)
                val ok = initResp["ok"]?.toString() == "true"
                val loaded = (initResp["profiles_loaded"]?.toString()?.toIntOrNull() ?: 0)
                if (ok) {
                    _engineState.value = EngineState.Ready(loaded)
                    refreshProfiles()
                    // Detect device timezone and sync to all profiles on startup
                    val deviceTz = java.util.TimeZone.getDefault().getID()
                    _profiles.value.forEach { profile ->
                        if (profile.timezone != deviceTz) {
                            createProfile(profile.copy(timezone = deviceTz))
                        }
                    }
                    refreshProfiles()
                    // Start embedded management HTTP server for phone web UI
                    ManagementServer.start(appContext, this@EngineController)
                } else {
                    _engineState.value = EngineState.Error(initResp["error"]?.toString() ?: "Init failed")
                }
            } catch (e: Exception) {
                _engineState.value = EngineState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun shutdown() {
        try { RustEngineBridge.nativeShutdown() } catch (_: Exception) {}
    }

    suspend fun startProfile(profile: ProfileConfig): Result<ProfileStatus> {
        return try {
            val result = RustEngineBridge.nativeStartProfile(json.encodeToString(profile))
            val status = json.decodeFromString<ProfileStatus>(result)
            if (status.running) {
                _activeProfileId.value = profile.id
                _activeProfile.value = status
            }
            refreshProfiles()
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopProfile(id: Int): Result<Unit> {
        return try {
            RustEngineBridge.nativeStopProfile(id)
            if (_activeProfileId.value == id) {
                _activeProfile.value = null
                _activeProfileId.value = 0
            }
            refreshProfiles()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannels(profileId: Int, type: String = "itv"): List<Channel> {
        return try {
            val result = RustEngineBridge.nativeGetChannels(profileId, type)
            json.decodeFromString<List<Channel>>(result)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getCategories(profileId: Int, type: String): List<Category> {
        return try {
            val result = RustEngineBridge.nativeGetCategories(profileId, type)
            json.decodeFromString<List<Category>>(result)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getProfileStatus(profileId: Int): ProfileStatus? {
        return try {
            val result = RustEngineBridge.nativeGetProfileStatus(profileId)
            json.decodeFromString<ProfileStatus>(result)
        } catch (_: Exception) { null }
    }

    suspend fun createProfile(profile: ProfileConfig): Result<ProfileConfig> {
        return try {
            val result = RustEngineBridge.nativeCreateProfile(json.encodeToString(profile))
            val created = json.decodeFromString<ProfileConfig>(result)
            refreshProfiles()
            Result.success(created)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteProfile(id: Int): Result<Unit> {
        return try {
            RustEngineBridge.nativeDeleteProfile(id)
            refreshProfiles()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun filterUpdate(action: String, profileId: Int, genreId: String? = null, cmd: String? = null, disabled: Boolean? = null, genreRenameId: String? = null, genreRenameName: String? = null) {
        try {
            val actionJson = buildJsonObject {
                put("profile_id", profileId.toString())
                put("action", action)
                genreId?.let { put("genre_id", it) }
                cmd?.let { put("cmd", it) }
                disabled?.let { put("disabled", if (it) "1" else "0") }
                genreRenameId?.let { put("genre_rename_id", it) }
                genreRenameName?.let { put("genre_rename_name", it) }
            }
            RustEngineBridge.nativeFilterUpdate(actionJson.toString())
        } catch (_: Exception) {}
    }

    suspend fun getFilterState(profileId: Int): String {
        return try {
            RustEngineBridge.nativeGetFilterState(profileId)
        } catch (_: Exception) { """{"error":"failed"}""" }
    }

    suspend fun getDisabledGenres(profileId: Int): Set<String> {
        return try {
            val state = json.decodeFromString<JsonObject>(RustEngineBridge.nativeGetFilterState(profileId))
            state["disabled_genres"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    suspend fun syncFilters(snapshot: Map<Int, FilterState>) {
        try {
            RustEngineBridge.nativeSyncFilters(json.encodeToString(snapshot))
        } catch (_: Exception) {}
    }

    private suspend fun refreshProfiles() {
        try {
            val result = RustEngineBridge.nativeGetProfiles()
            _profiles.value = json.decodeFromString<List<ProfileConfig>>(result)
            // Auto-detect which profile is running, if any
            for (p in _profiles.value) {
                val status = getProfileStatus(p.id)
                if (status?.running == true) {
                    _activeProfileId.value = p.id
                    _activeProfile.value = status
                    break
                }
            }
        } catch (_: Exception) {}
    }
}

@Serializable
data class Category(
    val id: String = "",
    val title: String = "",
    val name: String = "",
)

@Serializable
data class FilterState(
    val disabledGenres: Map<String, Boolean> = emptyMap(),
    val disabledChannels: Map<String, Boolean> = emptyMap(),
    val enabledChannels: Map<String, Boolean> = emptyMap(),
    val renamePrefix: String = "",
    val renameSuffix: String = "",
    val genreRenames: Map<String, String> = emptyMap(),
    val version: Long = 0,
)

fun ProfileConfig.toRustJson(): String = buildString {
    append("""{"id":$id,"name":"${name}","portal_url":"$portalUrl","mac":"$mac",""")
    append("""username":"$username","password":"$password","hls_port":$hlsPort,"proxy_port":$proxyPort,""")
    append("""timezone":"$timezone","serial_number":"$serialNumber","device_id":"$deviceId",""")
    append("""device_id2":"$deviceId2","signature":"$signature","model":"$model",""")
    append("""watchdog_interval":$watchdogInterval,"device_id_auth":$deviceIdAuth,""")
    append("""hls_enabled":$hlsEnabled,"proxy_enabled":$proxyEnabled,"proxy_rewrite":$proxyRewrite}""")
}
