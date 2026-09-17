package com.roxstar.voicedraft.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roxstar.voicedraft.network.RoomApiClient
import com.roxstar.voicedraft.network.RoomSocketClient
import com.roxstar.voicedraft.network.RoomSocketEvent
import com.roxstar.voicedraft.network.SocketConnectionState
import com.roxstar.voicedraft.spin.SpinViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates room operations by combining:
 * 1. Authoritative REST state mutations via [RoomApiClient]
 * 2. Real-time broadcast and presence synchronization via [RoomSocketClient]
 * 3. Live Spin game elimination flow via [SpinViewModel]
 */
class RoomViewModel(
    private val apiClient: RoomApiClient = RoomApiClient(),
    val socketClient: RoomSocketClient = RoomSocketClient(),
    val spinViewModel: SpinViewModel = SpinViewModel(apiClient, socketClient),
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    init {
        // Observe Socket.IO connection status changes
        viewModelScope.launch {
            socketClient.connectionState.collectLatest { status ->
                _uiState.update { state ->
                    state.copy(
                        connectionStatus = when (status) {
                            SocketConnectionState.DISCONNECTED -> RoomConnectionStatus.DISCONNECTED
                            SocketConnectionState.CONNECTING -> RoomConnectionStatus.CONNECTING
                            SocketConnectionState.CONNECTED -> RoomConnectionStatus.CONNECTED
                            SocketConnectionState.ERROR -> RoomConnectionStatus.ERROR
                        }
                    )
                }
            }
        }

        // Observe real-time room events
        viewModelScope.launch {
            socketClient.events.collectLatest { event ->
                when (event) {
                    is RoomSocketEvent.RoomStateReceived -> {
                        _uiState.update { current ->
                            current.copy(
                                roomInfo = event.state.room,
                                participants = event.state.participants,
                                sharedDrafts = event.state.sharedDrafts,
                            )
                        }
                    }

                    is RoomSocketEvent.UserJoined -> {
                        _uiState.update { current ->
                            current.copy(
                                participants = event.participants,
                                statusMessage = "${event.displayName} joined the room",
                            )
                        }
                    }

                    is RoomSocketEvent.UserLeft -> {
                        val verb = if (event.reason == "LEFT") "left the room" else "disconnected"
                        _uiState.update { current ->
                            current.copy(
                                participants = event.participants,
                                statusMessage = "${event.displayName} $verb",
                            )
                        }
                    }

                    is RoomSocketEvent.DraftShared -> {
                        _uiState.update { current ->
                            val alreadyContains = current.sharedDrafts.any { it.draftId == event.draft.draftId }
                            val updatedList = if (alreadyContains) {
                                current.sharedDrafts
                            } else {
                                listOf(event.draft) + current.sharedDrafts
                            }
                            current.copy(
                                sharedDrafts = updatedList,
                                statusMessage = "New draft shared: \"${event.draft.name}\"",
                            )
                        }
                    }

                    is RoomSocketEvent.Error -> {
                        _uiState.update { it.copy(errorMessage = event.message) }
                    }

                    is RoomSocketEvent.SpinStarted,
                    is RoomSocketEvent.UserEliminated,
                    is RoomSocketEvent.WinnerAnnounced -> {
                        // Spin elimination game events are handled by spinViewModel
                    }
                }
            }
        }
    }

    /**
     * Creates a new room via REST and establishes the Socket.IO real-time channel.
     */
    fun createAndJoinRoom(userId: String) {
        val trimmedUser = userId.trim()
        if (trimmedUser.isBlank()) {
            _uiState.update { it.copy(errorMessage = "User ID is required") }
            return
        }

        _uiState.update { it.copy(isJoiningOrLeaving = true, errorMessage = null) }

        viewModelScope.launch {
            val result = apiClient.createRoom(trimmedUser)
            if (result.isSuccess) {
                val roomState = result.getOrThrow()
                val roomId = roomState.room.id

                _uiState.update { current ->
                    current.copy(
                        isJoiningOrLeaving = false,
                        currentRoomId = roomId,
                        currentUserId = trimmedUser,
                        roomInfo = roomState.room,
                        participants = roomState.participants,
                        sharedDrafts = roomState.sharedDrafts,
                        statusMessage = "Created room ${roomId.take(8)}...",
                    )
                }

                socketClient.connect(trimmedUser)
                socketClient.joinRoom(roomId)
            } else {
                val error = result.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isJoiningOrLeaving = false,
                        errorMessage = "Create room failed: ${error?.message ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    /**
     * Joins an existing room via REST and subscribes to its real-time socket events.
     */
    fun joinExistingRoom(roomId: String, userId: String) {
        val trimmedRoom = roomId.trim()
        val trimmedUser = userId.trim()

        if (trimmedRoom.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Room ID is required") }
            return
        }
        if (trimmedUser.isBlank()) {
            _uiState.update { it.copy(errorMessage = "User ID is required") }
            return
        }

        _uiState.update { it.copy(isJoiningOrLeaving = true, errorMessage = null) }

        viewModelScope.launch {
            val result = apiClient.joinRoom(trimmedRoom, trimmedUser)
            if (result.isSuccess) {
                val roomState = result.getOrThrow()
                val activeRoomId = roomState.room.id

                _uiState.update { current ->
                    current.copy(
                        isJoiningOrLeaving = false,
                        currentRoomId = activeRoomId,
                        currentUserId = trimmedUser,
                        roomInfo = roomState.room,
                        participants = roomState.participants,
                        sharedDrafts = roomState.sharedDrafts,
                        statusMessage = "Joined room ${activeRoomId.take(8)}...",
                    )
                }

                socketClient.connect(trimmedUser)
                socketClient.joinRoom(activeRoomId)
            } else {
                val error = result.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isJoiningOrLeaving = false,
                        errorMessage = "Join room failed: ${error?.message ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    /**
     * Leaves the current room cleanly over Socket.IO and REST.
     */
    fun leaveRoom() {
        val roomId = _uiState.value.currentRoomId
        val userId = _uiState.value.currentUserId

        _uiState.update { it.copy(isJoiningOrLeaving = true) }

        viewModelScope.launch {
            if (roomId != null && userId != null) {
                socketClient.leaveRoom(roomId)
                apiClient.leaveRoom(roomId, userId)
            }
            socketClient.disconnect()
            spinViewModel.reset()

            _uiState.update {
                it.copy(
                    isJoiningOrLeaving = false,
                    currentRoomId = null,
                    roomInfo = null,
                    participants = emptyList(),
                    sharedDrafts = emptyList(),
                    connectionStatus = RoomConnectionStatus.DISCONNECTED,
                    statusMessage = "Left room",
                )
            }
        }
    }

    /**
     * Triggers the Spin elimination game in the current room on behalf of the owner.
     */
    fun startSpin() {
        val roomId = _uiState.value.currentRoomId ?: return
        val userId = _uiState.value.currentUserId ?: return
        spinViewModel.startSpin(roomId, userId)
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        socketClient.disconnect()
        super.onCleared()
    }
}
