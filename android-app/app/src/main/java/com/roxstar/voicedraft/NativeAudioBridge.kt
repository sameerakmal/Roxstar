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
}
