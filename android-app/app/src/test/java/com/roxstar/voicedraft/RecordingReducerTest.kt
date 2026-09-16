package com.roxstar.voicedraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingReducerTest {

    // --- permissionResult ---

    @Test
    fun permissionResult_granted_alwaysWinsRegardlessOfHistory() {
        val state = RecordingReducer.permissionResult(
            RecordingUiState(), granted = true, canShowRationale = true, hasAskedBefore = true,
        )
        assertEquals(PermissionState.GRANTED, state.permission)
    }

    @Test
    fun permissionResult_firstDenial_isDeniedNotPermanentlyDenied() {
        val state = RecordingReducer.permissionResult(
            RecordingUiState(), granted = false, canShowRationale = true, hasAskedBefore = false,
        )
        assertEquals(PermissionState.DENIED, state.permission)
    }

    @Test
    fun permissionResult_deniedWithoutRationaleAfterAsking_isPermanentlyDenied() {
        val state = RecordingReducer.permissionResult(
            RecordingUiState(), granted = false, canShowRationale = false, hasAskedBefore = true,
        )
        assertEquals(PermissionState.PERMANENTLY_DENIED, state.permission)
    }

    @Test
    fun permissionResult_deniedWithRationaleAvailable_isNeverPermanentlyDenied() {
        val state = RecordingReducer.permissionResult(
            RecordingUiState(), granted = false, canShowRationale = true, hasAskedBefore = true,
        )
        assertEquals(PermissionState.DENIED, state.permission)
    }

    // --- startRequested ---

    @Test
    fun startRequested_fromIdle_startsRecordingAndResetsCounters() {
        val previous = RecordingUiState(
            phase = RecordingPhase.ERROR,
            elapsedSeconds = 42,
            peakLevel = 0.5f,
            errorMessage = "stale error",
            savedFileName = "stale.wav",
        )
        val next = RecordingReducer.startRequested(previous)
        assertEquals(RecordingPhase.RECORDING, next?.phase)
        assertEquals(0, next?.elapsedSeconds)
        assertEquals(0f, next?.peakLevel)
        assertNull(next?.errorMessage)
        assertNull(next?.savedFileName)
    }

    @Test
    fun startRequested_fromSaved_isAllowed() {
        val next = RecordingReducer.startRequested(RecordingUiState(phase = RecordingPhase.SAVED))
        assertEquals(RecordingPhase.RECORDING, next?.phase)
    }

    @Test
    fun startRequested_whileAlreadyRecording_isIgnored() {
        assertNull(RecordingReducer.startRequested(RecordingUiState(phase = RecordingPhase.RECORDING)))
    }

    @Test
    fun startRequested_whileSaving_isIgnored() {
        assertNull(RecordingReducer.startRequested(RecordingUiState(phase = RecordingPhase.SAVING)))
    }

    // --- stopRequested ---

    @Test
    fun stopRequested_whileRecording_movesToSaving() {
        val next = RecordingReducer.stopRequested(RecordingUiState(phase = RecordingPhase.RECORDING))
        assertEquals(RecordingPhase.SAVING, next?.phase)
    }

    @Test
    fun stopRequested_whileIdle_isIgnored() {
        assertNull(RecordingReducer.stopRequested(RecordingUiState(phase = RecordingPhase.IDLE)))
    }

    @Test
    fun stopRequested_whileAlreadySaving_isIgnored() {
        assertNull(RecordingReducer.stopRequested(RecordingUiState(phase = RecordingPhase.SAVING)))
    }

    // --- stop outcomes ---

    @Test
    fun stopSucceeded_setsSavedPhaseAndFileName() {
        val next = RecordingReducer.stopSucceeded(RecordingUiState(phase = RecordingPhase.SAVING), "clip.wav")
        assertEquals(RecordingPhase.SAVED, next.phase)
        assertEquals("clip.wav", next.savedFileName)
    }

    @Test
    fun stopFailed_setsErrorPhaseAndMessage() {
        val next = RecordingReducer.stopFailed(RecordingUiState(phase = RecordingPhase.SAVING), "disk full")
        assertEquals(RecordingPhase.ERROR, next.phase)
        assertEquals("disk full", next.errorMessage)
    }

    @Test
    fun startFailed_setsErrorPhaseAndMessage() {
        val next = RecordingReducer.startFailed(RecordingUiState(phase = RecordingPhase.RECORDING), "mic busy")
        assertEquals(RecordingPhase.ERROR, next.phase)
        assertEquals("mic busy", next.errorMessage)
    }

    // --- progress ---

    @Test
    fun progress_whileRecording_updatesElapsedAndPeak() {
        val next = RecordingReducer.progress(RecordingUiState(phase = RecordingPhase.RECORDING), 5, 0.8f)
        assertEquals(5, next.elapsedSeconds)
        assertEquals(0.8f, next.peakLevel)
    }

    @Test
    fun progress_whileNotRecording_isIgnored() {
        val previous = RecordingUiState(phase = RecordingPhase.SAVING, elapsedSeconds = 3, peakLevel = 0.1f)
        val next = RecordingReducer.progress(previous, 99, 0.9f)
        assertEquals(previous, next)
    }
}
