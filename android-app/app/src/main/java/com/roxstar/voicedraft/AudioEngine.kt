package com.roxstar.voicedraft

/** Mirrors roxstar::EngineState in native-audio/AudioEngine.h. */
enum class EngineState(val code: Int) {
    UNINITIALIZED(0),
    OPEN(1),
    STARTED(2),
    STOPPED(3),
    CLOSED(4),
    DISCONNECTED(5);

    companion object {
        fun from(code: Long): EngineState =
            entries.firstOrNull { it.code == code.toInt() } ?: UNINITIALIZED
    }
}

/** Mirrors roxstar::Status in native-audio/AudioEngine.h. */
enum class AudioStatus(val code: Int) {
    OK(0),
    INVALID_STATE(-1),
    OPEN_FAILED(-2),
    START_FAILED(-3),
    STOP_FAILED(-4),
    CLOSE_FAILED(-5),
    NO_ENGINE(-6),
    DISCONNECTED(-7);

    companion object {
        fun from(code: Int): AudioStatus = entries.firstOrNull { it.code == code } ?: NO_ENGINE
    }
}

/** The configuration Oboe actually granted, read back from the open stream. */
data class AudioConfig(
    val state: EngineState,
    val sampleRate: Int,
    val channelCount: Int,
    val format: String,
    val sharingMode: String,
    val performanceMode: String,
    val framesPerBurst: Int,
    val bufferSizeInFrames: Int,
    val bufferCapacityInFrames: Int,
    val deviceId: Int,
    val inputPreset: String,
    val audioApi: String,
    val framesRead: Long,
    val peakLevel: Float,
    val lastResult: String,
)

/**
 * Kotlin owner of the native AudioEngine handle.
 *
 * Lifecycle: UNINITIALIZED -> OPEN -> STARTED -> STOPPED -> CLOSED.
 * STOPPED may be started again, and CLOSED may be opened again.
 *
 * [release] must be called when the engine is no longer needed; it closes any
 * open stream and frees the native object.
 */
class AudioEngine {

    private var handle: Long = NativeAudioBridge.nativeCreate()

    val oboeVersion: String get() = NativeAudioBridge.nativeGetOboeVersion()

    fun open(): AudioStatus = withHandle { AudioStatus.from(NativeAudioBridge.nativeOpen(it)) }

    fun start(): AudioStatus = withHandle { AudioStatus.from(NativeAudioBridge.nativeStart(it)) }

    fun stop(): AudioStatus = withHandle { AudioStatus.from(NativeAudioBridge.nativeStop(it)) }

    fun close(): AudioStatus = withHandle { AudioStatus.from(NativeAudioBridge.nativeClose(it)) }

    fun release() {
        if (handle != 0L) {
            NativeAudioBridge.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun config(): AudioConfig {
        if (handle == 0L) return EMPTY_CONFIG
        val v = NativeAudioBridge.nativeGetConfig(handle)
        if (v.size < IDX_COUNT) return EMPTY_CONFIG
        return AudioConfig(
            state = EngineState.from(v[IDX_STATE]),
            sampleRate = v[IDX_SAMPLE_RATE].toInt(),
            channelCount = v[IDX_CHANNEL_COUNT].toInt(),
            format = formatName(v[IDX_FORMAT]),
            sharingMode = sharingModeName(v[IDX_SHARING_MODE]),
            performanceMode = performanceModeName(v[IDX_PERFORMANCE_MODE]),
            framesPerBurst = v[IDX_FRAMES_PER_BURST].toInt(),
            bufferSizeInFrames = v[IDX_BUFFER_SIZE].toInt(),
            bufferCapacityInFrames = v[IDX_BUFFER_CAPACITY].toInt(),
            deviceId = v[IDX_DEVICE_ID].toInt(),
            inputPreset = inputPresetName(v[IDX_INPUT_PRESET]),
            audioApi = audioApiName(v[IDX_AUDIO_API]),
            framesRead = v[IDX_FRAMES_READ],
            peakLevel = v[IDX_PEAK_LEVEL_MICROS] / 1_000_000f,
            lastResult = NativeAudioBridge.nativeGetLastResultText(handle),
        )
    }

    private inline fun withHandle(block: (Long) -> AudioStatus): AudioStatus =
        if (handle == 0L) AudioStatus.NO_ENGINE else block(handle)

    private companion object {
        // Must match roxstar::ConfigIndex in native-audio/AudioEngine.h.
        const val IDX_STATE = 0
        const val IDX_SAMPLE_RATE = 1
        const val IDX_CHANNEL_COUNT = 2
        const val IDX_FORMAT = 3
        const val IDX_SHARING_MODE = 4
        const val IDX_PERFORMANCE_MODE = 5
        const val IDX_FRAMES_PER_BURST = 6
        const val IDX_BUFFER_SIZE = 7
        const val IDX_BUFFER_CAPACITY = 8
        const val IDX_DEVICE_ID = 9
        const val IDX_INPUT_PRESET = 10
        const val IDX_AUDIO_API = 11
        const val IDX_FRAMES_READ = 12
        const val IDX_PEAK_LEVEL_MICROS = 13
        const val IDX_LAST_RESULT = 14
        const val IDX_COUNT = 15

        val EMPTY_CONFIG = AudioConfig(
            state = EngineState.UNINITIALIZED,
            sampleRate = 0,
            channelCount = 0,
            format = "Invalid",
            sharingMode = "-",
            performanceMode = "-",
            framesPerBurst = 0,
            bufferSizeInFrames = 0,
            bufferCapacityInFrames = 0,
            deviceId = 0,
            inputPreset = "-",
            audioApi = "-",
            framesRead = 0,
            peakLevel = 0f,
            lastResult = "-",
        )

        // Numeric values come from oboe/Definitions.h.
        fun formatName(v: Long) = when (v.toInt()) {
            -1 -> "Invalid"
            0 -> "Unspecified"
            1 -> "I16"
            2 -> "Float"
            3 -> "I24"
            4 -> "I32"
            5 -> "IEC61937"
            else -> "Unknown($v)"
        }

        fun sharingModeName(v: Long) = when (v.toInt()) {
            0 -> "Exclusive"
            1 -> "Shared"
            else -> "Unknown($v)"
        }

        fun performanceModeName(v: Long) = when (v.toInt()) {
            10 -> "None"
            11 -> "PowerSaving"
            12 -> "LowLatency"
            else -> "Unknown($v)"
        }

        fun inputPresetName(v: Long) = when (v.toInt()) {
            1 -> "Generic"
            5 -> "Camcorder"
            6 -> "VoiceRecognition"
            7 -> "VoiceCommunication"
            9 -> "Unprocessed"
            10 -> "VoicePerformance"
            else -> "Unknown($v)"
        }

        fun audioApiName(v: Long) = when (v.toInt()) {
            0 -> "Unspecified"
            1 -> "OpenSLES"
            2 -> "AAudio"
            else -> "Unknown($v)"
        }
    }
}
