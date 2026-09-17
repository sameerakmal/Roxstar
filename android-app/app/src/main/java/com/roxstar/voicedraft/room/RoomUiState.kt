package com.roxstar.voicedraft.room

import com.roxstar.voicedraft.network.ParticipantDto
import com.roxstar.voicedraft.network.RoomDto
import com.roxstar.voicedraft.network.SharedDraftDto

/**
 * Real-time connection status for the joined room.
 */
enum class RoomConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * UI state for the Room screen.
 */
data class RoomUiState(
    /** Socket.IO connection status. */
    val connectionStatus: RoomConnectionStatus = RoomConnectionStatus.DISCONNECTED,
    /** Indicates an in-flight REST join, create, or leave operation. */
    val isJoiningOrLeaving: Boolean = false,
    /** ID of the currently joined room, or null if in the lobby. */
    val currentRoomId: String? = null,
    /** Active user ID for this room session. */
    val currentUserId: String? = null,
    /** Authoritative room metadata from the server. */
    val roomInfo: RoomDto? = null,
    /** Active participants and presence information. */
    val participants: List<ParticipantDto> = emptyList(),
    /** Real-time and persisted drafts shared into this room. */
    val sharedDrafts: List<SharedDraftDto> = emptyList(),
    /** Error message to be presented to the user. */
    val errorMessage: String? = null,
    /** Transient status banner/snackbar message (e.g. user joined, draft shared). */
    val statusMessage: String? = null,
) {
    val isInRoom: Boolean
        get() = currentRoomId != null
}
