package com.roxstar.voicedraft

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one contract the compiler cannot check: the numeric codes in
 * native-audio/AudioEngine.h and their Kotlin mirrors in AudioEngine.kt.
 *
 * If either side is edited without the other, these assertions fail at build
 * time instead of producing a silently wrong state or status on a device.
 */
class NativeContractTest {

    private val header: String by lazy {
        val file = findHeader()
        assertTrue("AudioEngine.h not found (looked from ${File("").absolutePath})", file.isFile)
        file.readText()
    }

    @Test
    fun engineStateCodesMatchNativeHeader() {
        val native = parseEnum("EngineState")
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
        val native = parseEnum("Status")
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
            "kIdxCount",
        )
        assertEquals(expected, enumBody("ConfigIndex").map { it.first })
    }

    // --- helpers ---

    private fun findHeader(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "native-audio/AudioEngine.h")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File("native-audio/AudioEngine.h")
    }

    /** Returns the entries of a C++ enum in declaration order, as name to literal. */
    private fun enumBody(name: String): List<Pair<String, Int?>> {
        val start = header.indexOf("enum class $name")
            .takeIf { it >= 0 }
            ?: header.indexOf("enum $name")
        assertTrue("enum $name not found in AudioEngine.h", start >= 0)
        val open = header.indexOf('{', start)
        val close = header.indexOf('}', open)
        return header.substring(open + 1, close)
            .lineSequence()
            .map { it.substringBefore("//").trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val parts = entry.split("=")
                parts[0].trim() to parts.getOrNull(1)?.trim()?.toIntOrNull()
            }
            .toList()
    }

    private fun parseEnum(name: String): Map<String, Int> =
        enumBody(name).associate { (entryName, value) ->
            assertTrue("native $name.$entryName has no explicit value", value != null)
            entryName to value!!
        }

    /** Uninitialized -> UNINITIALIZED, InvalidState -> INVALID_STATE, Ok -> OK. */
    private fun screamingSnake(pascal: String): String =
        pascal.mapIndexed { index, ch ->
            if (index > 0 && ch.isUpperCase()) "_$ch" else ch.toString()
        }.joinToString("").uppercase()
}
