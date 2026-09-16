package com.roxstar.voicedraft

/**
 * Thin JNI facade over the native-audio/ C++ library.
 *
 * Calls are deliberately coarse-grained — [nativeGetConfig] returns the whole
 * stream configuration in one array rather than one JNI call per field. The
 * engine itself is referenced by an opaque native handle.
 *
 * Nothing here is ever invoked from the Oboe audio callback.
 */
object NativeAudioBridge {

    init {
        System.loadLibrary("native-audio")
    }

    /** Round-trips through the native library to confirm the JNI bridge works. */
    external fun nativeHello(): String

    /** Oboe version the native library was compiled against. */
    external fun nativeGetOboeVersion(): String

    /** Allocates a native AudioEngine and returns its opaque handle. */
    external fun nativeCreate(): Long

    /** Closes any open stream and frees the native AudioEngine. */
    external fun nativeDestroy(handle: Long)

    external fun nativeOpen(handle: Long): Int

    external fun nativeStart(handle: Long): Int

    external fun nativeStop(handle: Long): Int

    external fun nativeClose(handle: Long): Int

    /** One coarse snapshot of the live stream; see the IDX_* layout in AudioEngine.kt. */
    external fun nativeGetConfig(handle: Long): LongArray

    /** Oboe's own text for the last oboe::Result observed natively. */
    external fun nativeGetLastResultText(handle: Long): String

    /**
     * Auto-opens/starts the stream if needed, then starts recording to
     * [path] (an absolute, already-unique path chosen by the caller).
     */
    external fun nativeStartRecording(handle: Long, path: String): Int

    /**
     * Blocking: stops the Oboe stream, then signals/drains/joins the writer
     * thread and patches the WAV header. Call this off the main thread.
     */
    external fun nativeStopRecording(handle: Long): Int

    /** The path most recently used for recording, or "" if none yet. */
    external fun nativeGetLastRecordingPath(handle: Long): String

    /**
     * Selects the effect applied to the *next* recording (see [Effect]).
     * Rejected with INVALID_STATE while a recording is in progress. An
     * out-of-range [effectCode] fails safely with INVALID_EFFECT rather than
     * being cast into undefined native enum territory.
     */
    external fun nativeSetEffect(handle: Long, effectCode: Int): Int
}
