package com.roxstar.voicedraft

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
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

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    // Shared with PlaybackViewModel via AudioEngineHolder — do not call release() here.
    // See AudioEngineHolder for the design rationale.
    private val engine = AudioEngineHolder.get(application)
    private val draftsDir = File(application.filesDir, "drafts").apply { mkdirs() }
    private val draftRepo = DraftRepository(draftsDir)

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            RecordingCleanup.removeOrphaned(draftsDir)
            draftRepo.reconcileOrphans()
        }
    }

    fun onPermissionResult(granted: Boolean, canShowRationale: Boolean, hasAskedBefore: Boolean) {
        _uiState.update { RecordingReducer.permissionResult(it, granted, canShowRationale, hasAskedBefore) }
    }

    /** Ignored while recording or saving — the effect is fixed for an in-progress session. */
    fun selectEffect(effect: Effect) {
        val next = RecordingReducer.effectSelected(_uiState.value, effect) ?: return
        _uiState.value = next
        engine.setEffect(effect)  // sticky on the native side until changed again
    }

    fun startRecording() {
        val next = RecordingReducer.startRequested(_uiState.value) ?: return
        _uiState.value = next

        val path = File(draftsDir, "${UUID.randomUUID()}.wav").absolutePath
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { engine.startRecording(path) }
            if (status == AudioStatus.OK) {
                startPolling()
            } else {
                _uiState.update { RecordingReducer.startFailed(it, status.toUserMessage()) }
            }
        }
    }

    fun stopRecording() {
        val next = RecordingReducer.stopRequested(_uiState.value) ?: return
        _uiState.value = next
        pollingJob?.cancel()

        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { engine.stopRecording() }
            _uiState.update {
                if (status == AudioStatus.OK) {
                    val wavPath = engine.lastRecordingPath()
                    val wavFile = java.io.File(wavPath)
                    // Persist Draft metadata immediately after a successful stop.
                    withContext(Dispatchers.IO) {
                        val draft = Draft(
                            id = wavFile.nameWithoutExtension,
                            name = DraftRepository.defaultName(System.currentTimeMillis()),
                            createdAt = System.currentTimeMillis(),
                            durationMs = DraftRepository.wavDurationMs(wavFile),
                            effect = it.selectedEffect,
                            filePath = wavPath,
                        )
                        draftRepo.saveDraft(draft)
                    }
                    RecordingReducer.stopSucceeded(it, wavFile.name)
                } else {
                    RecordingReducer.stopFailed(it, status.toUserMessage())
                }
            }
        }
    }

    /**
     * Called when the screen leaves the foreground while recording. There is
     * no foreground service (by design — out of scope for this app), so a
     * backgrounded recording is stopped and finalized rather than left
     * running with a mic stream Android may reclaim at any moment.
     */
    fun stopIfRecording() {
        if (_uiState.value.phase == RecordingPhase.RECORDING) stopRecording()
    }

    fun cancelRecording() {
        val next = RecordingReducer.cancelRequested(_uiState.value) ?: return
        _uiState.value = next
        pollingJob?.cancel()

        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.cancelRecording() }
            _uiState.update { RecordingReducer.cancelSucceeded(it) }
        }
    }

    private fun startPolling() {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val config = engine.config()
                val elapsed = if (config.sampleRate > 0) {
                    (config.recordingFramesCaptured / config.sampleRate).toInt()
                } else {
                    0
                }
                _uiState.update { RecordingReducer.progress(it, elapsed, config.peakLevel) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        // Safety net: if the activity is torn down while recording, stop and discard
        // rather than leak the writer thread. AudioEngineHolder keeps the engine alive
        // for PlaybackViewModel; we do NOT call release() here.
        pollingJob?.cancel()
        if (_uiState.value.phase == RecordingPhase.RECORDING ||
            _uiState.value.phase == RecordingPhase.SAVING) {
            engine.cancelRecording()
        }
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
    }
}
