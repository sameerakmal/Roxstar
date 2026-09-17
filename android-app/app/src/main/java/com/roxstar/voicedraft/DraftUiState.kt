package com.roxstar.voicedraft

data class DraftUiState(
    val drafts: List<DraftWithStatus> = emptyList(),
    val isLoading: Boolean = false,
    /** ID of the draft currently being played, or null if idle. */
    val playingDraftId: String? = null,
    /** ID of the draft currently being shared with backend, or null if idle. */
    val sharingDraftId: String? = null,
    /** Status/error message from the most recent share attempt. */
    val shareMessage: String? = null,
)
