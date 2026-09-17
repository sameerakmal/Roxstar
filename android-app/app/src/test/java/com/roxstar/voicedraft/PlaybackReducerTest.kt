package com.roxstar.voicedraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackReducerTest {

    // --- prepareRequested ---

    @Test
    fun prepareRequested_fromIdle_transitionsToPreparing() {
        val next = PlaybackReducer.prepareRequested(PlaybackUiState(), "draft-1")
        assertEquals(PlaybackPhase.PREPARING, next?.phase)
    }

    @Test
    fun prepareRequested_setsActiveDraftId() {
        val next = PlaybackReducer.prepareRequested(PlaybackUiState(), "draft-abc")
        assertEquals("draft-abc", next?.activeDraftId)
    }

    @Test
    fun prepareRequested_resetsPositionAndError() {
        val previous = PlaybackUiState(
            phase = PlaybackPhase.STOPPED,
            positionMs = 5000L,
            errorMessage = "stale error",
        )
        val next = PlaybackReducer.prepareRequested(previous, "draft-2")
        assertEquals(0L, next?.positionMs)
        assertNull(next?.errorMessage)
    }

    @Test
    fun prepareRequested_whilePreparing_isIgnored() {
        val preparing = PlaybackUiState(phase = PlaybackPhase.PREPARING)
        assertNull(PlaybackReducer.prepareRequested(preparing, "draft-3"))
    }

    @Test
    fun prepareRequested_whilePlaying_isAllowed() {
        val playing = PlaybackUiState(phase = PlaybackPhase.PLAYING)
        assertNotNull(PlaybackReducer.prepareRequested(playing, "draft-4"))
    }

    // --- playStarted ---

    @Test
    fun playStarted_transitionsToPlaying() {
        val next = PlaybackReducer.playStarted(PlaybackUiState(phase = PlaybackPhase.PREPARING), 3000L)
        assertEquals(PlaybackPhase.PLAYING, next.phase)
    }

    @Test
    fun playStarted_setsDuration() {
        val next = PlaybackReducer.playStarted(PlaybackUiState(phase = PlaybackPhase.PREPARING), 7500L)
        assertEquals(7500L, next.durationMs)
    }

    // --- prepareFailed ---

    @Test
    fun prepareFailed_transitionsToError() {
        val next = PlaybackReducer.prepareFailed(PlaybackUiState(phase = PlaybackPhase.PREPARING), "file missing")
        assertEquals(PlaybackPhase.ERROR, next.phase)
        assertEquals("file missing", next.errorMessage)
    }

    // --- progressTick ---

    @Test
    fun progressTick_whilePlaying_updatesPosition() {
        val next = PlaybackReducer.progressTick(PlaybackUiState(phase = PlaybackPhase.PLAYING), 1500L)
        assertEquals(1500L, next.positionMs)
    }

    @Test
    fun progressTick_whileNotPlaying_isIgnored() {
        val previous = PlaybackUiState(phase = PlaybackPhase.STOPPED, positionMs = 100L)
        val next = PlaybackReducer.progressTick(previous, 9999L)
        assertEquals(previous, next)
    }

    // --- stopped ---

    @Test
    fun stopped_transitionsToStoppedAndResetsPosition() {
        val next = PlaybackReducer.stopped(PlaybackUiState(phase = PlaybackPhase.PLAYING, positionMs = 2000L))
        assertEquals(PlaybackPhase.STOPPED, next.phase)
        assertEquals(0L, next.positionMs)
    }

    // --- reset ---

    @Test
    fun reset_returnsToIdleWithAllFieldsCleared() {
        val state = PlaybackUiState(
            phase = PlaybackPhase.ERROR,
            activeDraftId = "d-1",
            positionMs = 500L,
            durationMs = 3000L,
            errorMessage = "oops",
        )
        val next = PlaybackReducer.reset(state)
        assertEquals(PlaybackPhase.IDLE, next.phase)
        assertNull(next.activeDraftId)
        assertEquals(0L, next.positionMs)
        assertEquals(0L, next.durationMs)
        assertNull(next.errorMessage)
    }
}
