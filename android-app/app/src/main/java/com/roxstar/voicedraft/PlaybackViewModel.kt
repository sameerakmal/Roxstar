package com.roxstar.voicedraft

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages playback of a local [Draft] via the native [AudioEngine]'s playback pipeline.
 *
 * ## Design decision: shared AudioEngine handle
 * [PlaybackViewModel] receives the same [AudioEngine] instance that
 * [RecordingViewModel] uses. The C++ AudioEngine owns both a RecordingSession
 * (input) and a PlaybackSession (output) — they use independent Oboe streams
 * and do not interfere. Sharing one handle avoids double-allocation and keeps
 * JNI lifecycle management centralised.
 *
 * The [engine] reference is passed in via [AudioEngineHolder] (an
 * Application-scoped singleton) so both ViewModels reference the same object.
 *
 * ## Threading
 * - [play] dispatches `prepare` off the main thread (blocking I/O + stream open).
 * - [stop] dispatches `stopPlayback` off the main thread (may block briefly).
 * - A polling coroutine running on the main thread reads `playbackConfig()` every
 *   [POLL_INTERVAL_MS] to update position.
 */
class PlaybackViewModel(
    application: Application,
    /** Shared engine; must not be released while this ViewModel is alive. */
    private val engine: AudioEngine,
) : AndroidViewModel(application) {

    // Secondary constructor used by the default ViewModelProvider.Factory.
    // Falls back to the application-scoped engine holder so no DI framework is required.
    constructor(application: Application) : this(application, AudioEngineHolder.get(application))

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * Loads [draft] and starts playback. Idempotent if the same draft is
     * already playing. If a different draft is playing, it is stopped first.
     */
    fun play(draft: Draft) {
        // Don't restart the same draft if it is already preparing/playing.
        if (_uiState.value.activeDraftId == draft.id &&
            _uiState.value.phase == PlaybackPhase.PLAYING) return

        val next = PlaybackReducer.prepareRequested(_uiState.value, draft.id) ?: return
        _uiState.value = next
        pollingJob?.cancel()

        viewModelScope.launch {
            // Prepare is blocking: file I/O + Oboe stream open.
            val status = withContext(Dispatchers.IO) { engine.preparePlayback(draft.filePath) }
            if (status != AudioStatus.OK) {
                _uiState.update { PlaybackReducer.prepareFailed(it, status.toUserMessage()) }
                return@launch
            }

            val startStatus = withContext(Dispatchers.IO) { engine.startPlayback() }
            if (startStatus != AudioStatus.OK) {
                _uiState.update { PlaybackReducer.prepareFailed(it, startStatus.toUserMessage()) }
                return@launch
            }

            val config = engine.playbackConfig()
            val frameCount = config.frameCount
            val sampleRate = config.sampleRate.toLong()
            val durationMs = if (sampleRate > 0) (frameCount * 1000L) / sampleRate else draft.durationMs
            _uiState.update { PlaybackReducer.playStarted(it, durationMs) }

            startPolling(sampleRate)
        }
    }

    /** Stops playback and rewinds to the start. */
    fun stop() {
        pollingJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stopPlayback() }
            _uiState.update { PlaybackReducer.stopped(it) }
        }
    }

    /**
     * Resets to [PlaybackPhase.IDLE]. Call this when the user navigates away
     * from the Draft list or deselects the current draft.
     */
    fun reset() {
        pollingJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stopPlayback() }
            _uiState.update { PlaybackReducer.reset(it) }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        // Stop any active playback; do NOT release the engine — RecordingViewModel owns its lifecycle.
        viewModelScope.launch { withContext(Dispatchers.IO) { engine.stopPlayback() } }
        super.onCleared()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun startPolling(sampleRate: Long) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val config = engine.playbackConfig()
                val positionMs = if (sampleRate > 0) (config.framePosition * 1000L) / sampleRate else 0L
                _uiState.update { PlaybackReducer.progressTick(it, positionMs) }

                // Detect natural end of playback (native state transitions to Stopped).
                if (config.state == PlaybackState.STOPPED || config.state == PlaybackState.IDLE) {
                    _uiState.update { PlaybackReducer.stopped(it) }
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
