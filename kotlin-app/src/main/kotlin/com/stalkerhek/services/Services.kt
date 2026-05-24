package com.stalkerhek.services

import com.stalkerhek.models.*
import com.stalkerhek.persistence.*
import com.stalkerhek.rustclient.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProfileService(
    private val profileStore: ProfileStore,
    private val rustEngine: RustEngineClient,
    private val filterStore: FilterStore,
    private val logService: LogService,
) {
    private val profileStatuses = ConcurrentHashMap<Int, ProfileStatus>()
    private val busyStatus = ConcurrentHashMap<Int, Boolean>()
    private val channelCache = ConcurrentHashMap<Int, List<ChannelInfo>>()
    // Supervised scope for fire-and-forget calls to the Rust engine.
    // Uses SupervisorJob so a failed child doesn't cancel siblings.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun listProfiles(): List<Profile> = profileStore.list()

    fun getProfile(id: Int): Profile? = profileStore.get(id)

    fun createProfile(profile: Profile): Profile {
        val saved = profileStore.add(profile)
        logService.append(saved.id, "Profile created")
        logService.appendInstance("Profile #${saved.id} created: ${saved.name}")
        return saved
    }

    fun updateProfile(id: Int, profile: Profile): Profile? {
        val updated = profileStore.update(id, profile) ?: return null
        logService.append(id, "Profile updated")
        logService.appendInstance("Profile #$id updated: ${profile.name}")
        return updated
    }

    fun deleteProfile(id: Int): Boolean {
        profileStore.delete(id)
        filterStore.delete(id)
        profileStatuses.remove(id)
        channelCache.remove(id)
        logService.append(id, "Profile deleted")
        logService.appendInstance("Profile #$id deleted")
        serviceScope.launch { rustEngine.deleteProfile(id) }
        return true
    }

    fun getProfileStatus(id: Int): ProfileStatus =
        profileStatuses[id] ?: ProfileStatus(id = id)

    fun getChannelCache(id: Int): List<ChannelInfo> =
        channelCache[id] ?: emptyList()

    fun setChannelCache(id: Int, channels: List<ChannelInfo>) {
        channelCache[id] = channels
    }

    suspend fun fetchChannels(id: Int): Result<List<ChannelInfo>> = runCatching {
        rustEngine.getChannels(id, "itv")
    }

    fun isBusy(id: Int): Boolean = busyStatus[id] ?: false

    suspend fun startProfile(id: Int): Result<Unit> {
        val profile = profileStore.get(id) ?: return Result.failure(Exception("Profile not found"))
        return runCatching {
            busyStatus[id] = true
            try {
                logService.append(id, "Starting...")
                logService.appendInstance("Profile #$id starting...")

                val rustCfg = RustProfileConfig(
                    id = profile.id,
                    name = profile.name,
                    portal_url = profile.portalUrl,
                    mac = profile.mac,
                    username = profile.username,
                    password = profile.password,
                    hls_port = profile.hlsPort,
                    proxy_port = profile.proxyPort,
                    timezone = profile.timezone,
                    model = profile.model,
                    device_id_auth = profile.deviceIdAuth,
                    hls_enabled = profile.hlsEnabled,
                    proxy_enabled = profile.proxyEnabled,
                    proxy_rewrite = profile.proxyRewrite,
                    serial_number = profile.serialNumber,
                    device_id = profile.deviceId,
                    device_id2 = profile.deviceId2,
                    signature = profile.signature,
                    watchdog_interval = profile.watchdogInterval,
                )

                // Upsert profile config into Rust engine and start it.
                // The Rust start endpoint reads the profile from its own store,
                // so we must create/update it there first.
                rustEngine.createProfile(rustCfg)
                val result = rustEngine.startProfile(rustCfg)
                result.fold(
                    onSuccess = { startResp ->
                        // Use the ports returned directly in the start response.
                        // Fall back to a status query only if they're missing (shouldn't happen).
                        val hlsAddr = startResp.hlsAddr.takeIf { it.isNotEmpty() }
                            ?: rustEngine.getProfileStatus(id)?.hlsAddr
                            ?: ":${profile.hlsPort}"
                        val proxyAddr = startResp.proxyAddr.takeIf { it.isNotEmpty() }
                            ?: rustEngine.getProfileStatus(id)?.proxyAddr
                            ?: ":${profile.proxyPort}"
                        profileStatuses[id] = ProfileStatus(
                            id = id,
                            name = profile.name,
                            phase = "success",
                            message = "Running",
                            channels = startResp.channels,
                            hls = hlsAddr,
                            proxy = proxyAddr,
                            running = true,
                        )
                        serviceScope.launch {
                            val channels = rustEngine.getChannels(id)
                            channelCache[id] = channels
                        }
                        logService.append(id, "Started successfully with ${startResp.channels} channels")
                        logService.appendInstance("Profile #$id started — ${startResp.channels} channels")
                    },
                    onFailure = { e ->
                        profileStatuses[id] = ProfileStatus(
                            id = id,
                            name = profile.name,
                            phase = "error",
                            message = e.message ?: "Unknown error",
                        )
                        logService.append(id, "Failed to start: ${e.message}")
                        logService.appendInstance("Profile #$id failed: ${e.message}")
                    }
                )
            } finally {
                // Always clear busy flag, even if an unexpected exception escapes
                busyStatus[id] = false
            }
        }
    }

    fun stopProfile(id: Int) {
        profileStatuses[id] = profileStatuses[id]?.copy(phase = "idle", message = "Stopped", running = false)
            ?: ProfileStatus(id = id, phase = "idle", message = "Stopped")
        logService.append(id, "Stopped")
        logService.appendInstance("Profile #$id stopped")
        serviceScope.launch { rustEngine.stopProfile(id) }
    }

    fun listStatuses(): List<ProfileStatus> {
        val profiles = profileStore.list()
        return profiles.map { p ->
            val status = profileStatuses[p.id] ?: ProfileStatus(id = p.id, name = p.name)
            status.copy(name = p.name, busy = busyStatus[p.id] ?: false)
        }
    }

    suspend fun refreshStatusesFromEngine() {
        val profiles = profileStore.list()
        for (p in profiles) {
            try {
                val engineStatus = rustEngine.getProfileStatus(p.id)
                if (engineStatus != null && engineStatus.running) {
                    profileStatuses[p.id] = ProfileStatus(
                        id = p.id,
                        name = p.name,
                        phase = "success",
                        message = "Running",
                        channels = engineStatus.channelsCount,
                        hls = engineStatus.hlsAddr,
                        proxy = engineStatus.proxyAddr,
                        running = true,
                    )
                }
            } catch (_: Exception) { }
        }
    }
}

class LogService {
    private data class LogEntryInternal(val id: Long, val ts: Instant, val msg: String)
    private val profileLogs = ConcurrentHashMap<Int, MutableList<LogEntryInternal>>()
    private val instanceLogs = mutableListOf<String>()
    private val maxEntries = 600
    private val instanceMax = 2000
    private val nextId = java.util.concurrent.atomic.AtomicLong(1)

    fun append(profileId: Int, msg: String) {
        if (profileId <= 0 || msg.isBlank()) return
        val entry = LogEntryInternal(nextId.getAndIncrement(), Instant.now(), msg)
        val logs = profileLogs.getOrPut(profileId) { mutableListOf() }
        synchronized(logs) {
            logs.add(entry)
            if (logs.size > maxEntries) logs.removeAt(0)
        }
    }

    fun getLogs(profileId: Int): List<LogEntry> {
        val logs = profileLogs[profileId] ?: return emptyList()
        synchronized(logs) {
            return logs.map { LogEntry(id = it.id, ts = it.ts.toString(), msg = it.msg) }
        }
    }

    fun appendInstance(msg: String) {
        if (msg.isBlank()) return
        synchronized(instanceLogs) {
            instanceLogs.add(msg)
            if (instanceLogs.size > instanceMax) instanceLogs.removeAt(0)
        }
    }

    fun getInstanceLogs(): List<String> = synchronized(instanceLogs) { instanceLogs.toList() }
}
