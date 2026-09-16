package com.roxstar.voicedraft

/**
 * Thin JNI facade over the native-audio/ C++ library.
 *
 * Phase 1 only proves the toolchain end-to-end (Gradle -> CMake -> NDK -> JNI -> Kotlin).
 * No audio, Oboe, effects, recording, playback or networking calls are exposed here yet —
 * those are added in later phases per the approved plan.
 */
object NativeAudioBridge {

    init {
        System.loadLibrary("native-audio")
    }

    /** Round-trips through the native library to confirm the JNI bridge works. */
    external fun nativeHello(): String
}
