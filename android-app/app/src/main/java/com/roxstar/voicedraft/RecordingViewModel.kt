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

    private val engine = AudioEngine()
    private val draftsDir = File(application.filesDir, "drafts").apply { mkdirs() }

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { RecordingCleanup.removeOrphaned(draftsDir) }
    }

    fun onPermissionResult(granted: Boolean, canShowRationale: Boolean, hasAskedBefore: Boolean) {
        _uiState.update { RecordingReducer.permissionResult(it, granted, canShowRationale, hasAskedBefore) }
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
                    RecordingReducer.stopSucceeded(it, File(engine.lastRecordingPath()).name)
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
        // Safety net for an abnormal teardown that skipped stopIfRecording():
        // AudioEngine.close() cancels and discards any still-active recording
        // rather than leaving its writer thread behind.
        pollingJob?.cancel()
        engine.close()
        engine.release()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
    }
}
