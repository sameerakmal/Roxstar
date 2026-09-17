package com.roxstar.voicedraft

import android.app.Application

/**
 * Application-scoped singleton that holds the single [AudioEngine] instance.
 *
 * ## Why shared?
 * The native C++ [AudioEngine] owns both a RecordingSession (Oboe input) and a
 * PlaybackSession (Oboe output) — they use completely independent streams and
 * do not interfere with each other. Sharing one handle means:
 * - Only one native object is allocated.
 * - The JNI handle lifecycle is managed in one place ([release] on app exit).
 * - [RecordingViewModel] and [PlaybackViewModel] can both call recording and
 *   playback methods without knowing about each other.
 *
 * ## Lifecycle
 * [get] creates the engine on first call. [release] must be called when the
 * Application is destroyed (or in a test tear-down). The [Application] parameter
 * is accepted for future per-app isolation in tests but is not used today.
 */
object AudioEngineHolder {

    @Volatile
    private var instance: AudioEngine? = null

    /** Returns the shared engine, creating it on first call. Thread-safe. */
    fun get(application: Application): AudioEngine {
        return instance ?: synchronized(this) {
            instance ?: AudioEngine().also { instance = it }
        }
    }

    /**
     * Closes and releases the native engine. Call from [Application.onTerminate]
     * or equivalent. After this, the next [get] call will create a fresh engine.
     */
    fun release() {
        synchronized(this) {
            instance?.let { engine ->
                engine.close()
                engine.release()
            }
            instance = null
        }
    }
}
