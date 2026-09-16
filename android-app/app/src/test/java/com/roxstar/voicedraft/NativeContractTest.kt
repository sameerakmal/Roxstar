package com.roxstar.voicedraft

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one contract the compiler cannot check: the numeric codes shared
 * across JNI (native-audio/Status.h, AudioEngine.h, RecordingSession.h,
 * src/effects/IEffect.h) and their Kotlin mirrors (AudioEngine.kt).
 *
 * If either side is edited without the other, these assertions fail at build
 * time instead of producing a silently wrong state or status on a device.
 */
class NativeContractTest {

    private val statusHeader by header("Status.h")
    private val audioEngineHeader by header("AudioEngine.h")
    private val recordingSessionHeader by header("RecordingSession.h")
    private val effectHeader by header("src/effects/IEffect.h")

    @Test
    fun engineStateCodesMatchNativeHeader() {
        val native = parseEnum(audioEngineHeader, "EngineState")
        assertEquals(EngineState.entries.size, native.size)
        EngineState.entries.forEach { kotlinEntry ->
            val nativeName = native.keys.first { screamingSnake(it) == kotlinEntry.name }
            assertEquals(
                "EngineState.${kotlinEntry.name} disagrees with native $nativeName",
                native.getValue(nativeName),
                kotlinEntry.code,
            )
        }
    }

    @Test
    fun audioStatusCodesMatchNativeHeader() {
        val native = parseEnum(statusHeader, "Status")
        assertEquals(AudioStatus.entries.size, native.size)
        AudioStatus.entries.forEach { kotlinEntry ->
            val nativeName = native.keys.first { screamingSnake(it) == kotlinEntry.name }
            assertEquals(
                "AudioStatus.${kotlinEntry.name} disagrees with native $nativeName",
                native.getValue(nativeName),
                kotlinEntry.code,
            )
        }
    }

    @Test
    fun recordingStateCodesMatchNativeHeader() {
        val native = parseEnum(recordingSessionHeader, "RecordingState")
        assertEquals(RecordingState.entries.size, native.size)
        RecordingState.entries.forEach { kotlinEntry ->
            val nativeName = native.keys.first { screamingSnake(it) == kotlinEntry.name }
            assertEquals(
                "RecordingState.${kotlinEntry.name} disagrees with native $nativeName",
                native.getValue(nativeName),
                kotlinEntry.code,
            )
        }
    }

    @Test
    fun effectCodesMatchNativeHeader() {
        val native = parseEnum(effectHeader, "EffectType")
        assertEquals(Effect.entries.size, native.size)
        Effect.entries.forEach { kotlinEntry ->
            val nativeName = native.keys.first { screamingSnake(it) == kotlinEntry.name }
            assertEquals(
                "Effect.${kotlinEntry.name} disagrees with native $nativeName",
                native.getValue(nativeName),
                kotlinEntry.code,
            )
        }
    }

    @Test
    fun configIndexLayoutMatchesNativeHeader() {
        // The order AudioEngine.kt assumes when decoding nativeGetConfig().
        val expected = listOf(
            "kIdxState",
            "kIdxSampleRate",
            "kIdxChannelCount",
            "kIdxFormat",
            "kIdxSharingMode",
            "kIdxPerformanceMode",
            "kIdxFramesPerBurst",
            "kIdxBufferSizeInFrames",
            "kIdxBufferCapacityInFrames",
            "kIdxDeviceId",
            "kIdxInputPreset",
            "kIdxAudioApi",
            "kIdxFramesRead",
            "kIdxPeakLevelMicros",
            "kIdxLastResult",
            "kIdxRecordingState",
            "kIdxRecordingFramesCaptured",
            "kIdxRecordingOverrunFrames",
            "kIdxCount",
        )
        assertEquals(expected, enumBody(audioEngineHeader, "ConfigIndex").map { it.first })
    }

    // --- helpers ---

    /** Lazily locates and reads native-audio/<fileName>, searching upward from the module dir. */
    private fun header(fileName: String) = lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "native-audio/$fileName")
            if (candidate.isFile) return@lazy candidate.readText()
            dir = dir.parentFile
        }
        assertTrue("native-audio/$fileName not found (looked from ${File("").absolutePath})", false)
        ""
    }

    /** Returns the entries of a C++ enum in declaration order, as name to literal. */
    private fun enumBody(headerText: String, name: String): List<Pair<String, Int?>> {
        val start = headerText.indexOf("enum class $name")
            .takeIf { it >= 0 }
            ?: headerText.indexOf("enum $name")
        assertTrue("enum $name not found", start >= 0)
        val open = headerText.indexOf('{', start)
        val close = headerText.indexOf('}', open)
        return headerText.substring(open + 1, close)
            .lineSequence()
            .map { it.substringBefore("//").trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val parts = entry.split("=")
                parts[0].trim() to parts.getOrNull(1)?.trim()?.toIntOrNull()
            }
            .toList()
    }

    private fun parseEnum(headerText: String, name: String): Map<String, Int> =
        enumBody(headerText, name).associate { (entryName, value) ->
            assertTrue("native $name.$entryName has no explicit value", value != null)
            entryName to value!!
        }

    /** Uninitialized -> UNINITIALIZED, InvalidState -> INVALID_STATE, Ok -> OK. */
    private fun screamingSnake(pascal: String): String =
        pascal.mapIndexed { index, ch ->
            if (index > 0 && ch.isUpperCase()) "_$ch" else ch.toString()
        }.joinToString("").uppercase()
}
