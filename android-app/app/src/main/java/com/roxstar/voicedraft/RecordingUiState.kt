package com.roxstar.voicedraft

enum class PermissionState { UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED }

enum class RecordingPhase { IDLE, RECORDING, SAVING, SAVED, ERROR }

data class RecordingUiState(
    val permission: PermissionState = PermissionState.UNKNOWN,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val peakLevel: Float = 0f,
    val savedFileName: String? = null,
    val errorMessage: String? = null,
)

/**
 * Pure state transitions for [RecordingUiState] — no Android/JNI dependency,
 * so this is the part of [RecordingViewModel] that JVM tests exercise
 * directly. The ViewModel itself just calls these and forwards the result.
 */
object RecordingReducer {

    fun permissionResult(
        current: RecordingUiState,
        granted: Boolean,
        canShowRationale: Boolean,
        hasAskedBefore: Boolean,
    ): RecordingUiState {
        val permission = when {
            granted -> PermissionState.GRANTED
            hasAskedBefore && !canShowRationale -> PermissionState.PERMANENTLY_DENIED
            else -> PermissionState.DENIED
        }
        return current.copy(permission = permission)
    }

    /** Null means the request is ignored (a recording is already in progress or saving). */
    fun startRequested(current: RecordingUiState): RecordingUiState? {
        if (current.phase == RecordingPhase.RECORDING || current.phase == RecordingPhase.SAVING) {
            return null
        }
        return current.copy(
            phase = RecordingPhase.RECORDING,
            elapsedSeconds = 0,
            peakLevel = 0f,
            errorMessage = null,
            savedFileName = null,
        )
    }

    fun startFailed(current: RecordingUiState, message: String): RecordingUiState =
        current.copy(phase = RecordingPhase.ERROR, errorMessage = message)

    /** Null means the request is ignored (nothing is currently recording). */
    fun stopRequested(current: RecordingUiState): RecordingUiState? {
        if (current.phase != RecordingPhase.RECORDING) return null
        return current.copy(phase = RecordingPhase.SAVING)
    }

    fun stopSucceeded(current: RecordingUiState, fileName: String): RecordingUiState =
        current.copy(phase = RecordingPhase.SAVED, savedFileName = fileName)

    fun stopFailed(current: RecordingUiState, message: String): RecordingUiState =
        current.copy(phase = RecordingPhase.ERROR, errorMessage = message)

    /** Ignored once a stop has been requested, so a late poll tick can't undo it. */
    fun progress(current: RecordingUiState, elapsedSeconds: Int, peakLevel: Float): RecordingUiState {
        if (current.phase != RecordingPhase.RECORDING) return current
        return current.copy(elapsedSeconds = elapsedSeconds, peakLevel = peakLevel)
    }
}

/** Maps a native result to copy a user can act on — never shown as a raw enum/status code. */
internal fun AudioStatus.toUserMessage(): String = when (this) {
    AudioStatus.OPEN_FAILED, AudioStatus.START_FAILED ->
        "Couldn't access the microphone. It may be in use by another app."
    AudioStatus.DISCONNECTED -> "The microphone was disconnected."
    AudioStatus.FILE_ERROR -> "Couldn't save the recording."
    else -> "Something went wrong. Please try again."
}
