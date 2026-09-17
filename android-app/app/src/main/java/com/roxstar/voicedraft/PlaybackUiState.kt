package com.roxstar.voicedraft

/** Lifecycle phases for the playback of a single [Draft]. */
enum class PlaybackPhase {
    /** No draft is loaded; the player is idle. */
    IDLE,
    /** Loading the WAV file and opening the Oboe output stream. Blocking on IO. */
    PREPARING,
    /** Audio is actively playing. */
    PLAYING,
    /** Playback stopped (either by user or natural end of track). */
    STOPPED,
    /** An error occurred (see [PlaybackUiState.errorMessage]). */
    ERROR,
}

/**
 * Immutable snapshot of the playback UI.
 *
 * Designed to be consumed by Compose: all state is plain data, no callbacks.
 * [PlaybackViewModel] is the only writer.
 */
data class PlaybackUiState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    /** The draft currently loaded (or being loaded). Null while [IDLE]. */
    val activeDraftId: String? = null,
    /** Playback position in milliseconds. Updated by the polling loop. */
    val positionMs: Long = 0L,
    /** Total duration of the loaded draft in milliseconds. */
    val durationMs: Long = 0L,
    /** Non-null only when [phase] == [PlaybackPhase.ERROR]. */
    val errorMessage: String? = null,
)

/**
 * Pure reducer for [PlaybackUiState] transitions.
 *
 * All functions are side-effect-free. Returning `null` means "ignore this event".
 * Designed to be tested on the JVM host without Android dependencies.
 */
object PlaybackReducer {

    /** Null if already preparing or playing. */
    fun prepareRequested(current: PlaybackUiState, draftId: String): PlaybackUiState? {
        if (current.phase == PlaybackPhase.PREPARING) return null
        return current.copy(
            phase = PlaybackPhase.PREPARING,
            activeDraftId = draftId,
            positionMs = 0L,
            errorMessage = null,
        )
    }

    fun playStarted(current: PlaybackUiState, durationMs: Long): PlaybackUiState =
        current.copy(phase = PlaybackPhase.PLAYING, durationMs = durationMs)

    fun prepareFailed(current: PlaybackUiState, message: String): PlaybackUiState =
        current.copy(phase = PlaybackPhase.ERROR, errorMessage = message)

    fun progressTick(current: PlaybackUiState, positionMs: Long): PlaybackUiState {
        if (current.phase != PlaybackPhase.PLAYING) return current
        return current.copy(positionMs = positionMs)
    }

    fun stopped(current: PlaybackUiState): PlaybackUiState =
        current.copy(phase = PlaybackPhase.STOPPED, positionMs = 0L)

    fun reset(current: PlaybackUiState): PlaybackUiState =
        current.copy(
            phase = PlaybackPhase.IDLE,
            activeDraftId = null,
            positionMs = 0L,
            durationMs = 0L,
            errorMessage = null,
        )
}
