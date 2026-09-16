package com.roxstar.voicedraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioStatusMessageTest {

    @Test
    fun everyStatusProducesNonBlankHumanReadableText() {
        AudioStatus.entries.forEach { status ->
            val message = status.toUserMessage()
            assertNotEquals("blank message for $status", "", message.trim())
            // The whole point: never leak the raw enum/status code into user-facing text.
            assertEquals(false, message.contains(status.name))
            assertEquals(false, message.contains(status.code.toString()))
        }
    }

    @Test
    fun openAndStartFailuresShareTheSameMicrophoneMessage() {
        assertEquals(AudioStatus.OPEN_FAILED.toUserMessage(), AudioStatus.START_FAILED.toUserMessage())
    }

    @Test
    fun invalidEffectHasItsOwnDistinctMessage() {
        val message = AudioStatus.INVALID_EFFECT.toUserMessage()
        assertNotEquals(message, AudioStatus.OPEN_FAILED.toUserMessage())
        assertNotEquals(message, AudioStatus.FILE_ERROR.toUserMessage())
    }
}
