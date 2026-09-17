package com.roxstar.voicedraft

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the local Draft list and orchestrates playback of a selected draft.
 *
 * Observes [PlaybackViewModel.uiState] to keep [DraftUiState.playingDraftId] in sync
 * so the Draft list can show which item is currently playing without reaching into
 * the playback ViewModel directly.
 */
class DraftViewModel(
    application: Application,
    private val playbackViewModel: PlaybackViewModel,
) : AndroidViewModel(application) {

    // Secondary constructor for ViewModelProvider (no DI framework required).
    constructor(application: Application) : this(
        application,
        PlaybackViewModel(application),
    )

    private val draftRepo = DraftRepository(
        File(application.filesDir, "drafts").apply { mkdirs() },
    )

    private val _uiState = MutableStateFlow(DraftUiState())
    val uiState: StateFlow<DraftUiState> = _uiState.asStateFlow()

    val playbackUiState: StateFlow<PlaybackUiState> = playbackViewModel.uiState

    init {
        loadDrafts()
        // Mirror playback state into DraftUiState so the list can highlight the playing row.
        viewModelScope.launch {
            playbackViewModel.uiState.collectLatest { playback ->
                _uiState.update { draft ->
                    draft.copy(
                        playingDraftId = if (playback.phase == PlaybackPhase.PLAYING) {
                            playback.activeDraftId
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    /** Reloads the draft list from disk. */
    fun loadDrafts() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val drafts = withContext(Dispatchers.IO) { draftRepo.loadAll() }
            _uiState.update { it.copy(drafts = drafts, isLoading = false) }
        }
    }

    /** Deletes the draft and refreshes the list. Stops playback if this draft was playing. */
    fun deleteDraft(id: String) {
        if (_uiState.value.playingDraftId == id) {
            playbackViewModel.stop()
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { draftRepo.deleteDraft(id) }
            loadDrafts()
        }
    }

    /** Plays [draft] via the shared playback pipeline. */
    fun play(draft: Draft) {
        playbackViewModel.play(draft)
    }

    /** Stops the currently playing draft. */
    fun stop() {
        playbackViewModel.stop()
    }

    override fun onCleared() {
        playbackViewModel.reset()
        super.onCleared()
    }
}
