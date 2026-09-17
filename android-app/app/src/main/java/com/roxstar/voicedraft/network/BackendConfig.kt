package com.roxstar.voicedraft.network

import com.roxstar.voicedraft.BuildConfig

/**
 * Global configuration for backend networking.
 *
 * Defaults to the compile-time [BuildConfig.BACKEND_BASE_URL], but allows
 * runtime configuration (e.g. for staging, local testing, or MockWebServer).
 * Guarantees no trailing slash for consistent URL construction.
 */
object BackendConfig {
    @Volatile
    private var customBaseUrl: String? = null

    /**
     * The active base URL for backend API calls.
     */
    var baseUrl: String
        get() = (customBaseUrl ?: BuildConfig.BACKEND_BASE_URL).trimEnd('/')
        set(value) {
            customBaseUrl = value.trim().trimEnd('/')
        }

    /**
     * Resets the base URL back to the default [BuildConfig.BACKEND_BASE_URL].
     */
    fun resetToDefault() {
        customBaseUrl = null
    }
}
