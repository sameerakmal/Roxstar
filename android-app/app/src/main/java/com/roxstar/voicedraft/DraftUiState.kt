package com.roxstar.voicedraft

data class DraftUiState(
    val drafts: List<DraftWithStatus> = emptyList(),
    val isLoading: Boolean = false,
    /** ID of the draft currently being played, or null if idle. */
    val playingDraftId: String? = null,
)
