package com.stalkerhek.tv.engine

object RustEngineBridge {
    init {
        System.loadLibrary("stalkerhek_engine")
    }

    /** Initialize the engine with Android internal storage path. Returns JSON status. */
    external fun nativeInit(dataDir: String): String

    /** Start a profile. Accepts ProfileConfig JSON, returns start result JSON. */
    external fun nativeStartProfile(profileJson: String): String

    /** Stop a profile by id. Returns {"ok":true/false}. */
    external fun nativeStopProfile(profileId: Int): String

    /** Get channels for a profile. type = "itv", "vod", or "series". Returns JSON array. */
    external fun nativeGetChannels(profileId: Int, mediaType: String): String

    /** Get categories. type = "vod" or "series". Returns JSON array. */
    external fun nativeGetCategories(profileId: Int, mediaType: String): String

    /** Get all profiles. Returns JSON array of ProfileConfig. */
    external fun nativeGetProfiles(): String

    /** Get profile status by id. Returns ProfileStatus JSON. */
    external fun nativeGetProfileStatus(profileId: Int): String

    /** Create or update a profile. Returns ProfileConfig JSON. */
    external fun nativeCreateProfile(profileJson: String): String

    /** Delete a profile by id. */
    external fun nativeDeleteProfile(profileId: Int): String

    /** Apply a filter action. Accepts action JSON. */
    external fun nativeFilterUpdate(actionJson: String): String

    /** Bulk-sync filters from persistence. Accepts snapshot JSON. */
    external fun nativeSyncFilters(snapshotJson: String): String

    /** Gracefully shut down the engine (stop servers, release resources). */
    external fun nativeShutdown(): String

    /** Get filter state for a profile (debug). Returns JSON. */
    external fun nativeGetFilterState(profileId: Int): String
}
