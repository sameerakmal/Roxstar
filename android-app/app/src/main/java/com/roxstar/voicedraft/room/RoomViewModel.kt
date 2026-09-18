package com.roxstar.voicedraft.room

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roxstar.voicedraft.Draft
import com.roxstar.voicedraft.DraftRepository
import com.roxstar.voicedraft.network.RoomApiClient
import com.roxstar.voicedraft.network.RoomSocketClient
import com.roxstar.voicedraft.network.RoomSocketEvent
import com.roxstar.voicedraft.network.SocketConnectionState
import com.roxstar.voicedraft.spin.SpinViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
                        val text = "${event.displayName} joined the room"
                        _uiState.update { current ->
                            val isDuplicate = current.activityEvents.any {
                                it.text == text && (System.currentTimeMillis() - it.timestampMs) < 5000
                            }
                            val updatedEvents = if (isDuplicate) {
                                current.activityEvents
                            } else {
                                (listOf(RoomActivityEvent(icon = "👤", text = text)) + current.activityEvents).take(30)
                            }
                            current.copy(
                                participants = event.participants,
                                statusMessage = text,
                                activityEvents = updatedEvents,
                            )
                        }
                    }

                    is RoomSocketEvent.UserLeft -> {
                        val verb = if (event.reason == "LEFT") "left the room" else "disconnected"
                        val text = "${event.displayName} $verb"
                        _uiState.update { current ->
                            val recentIdx = current.activityEvents.indexOfFirst {
                                it.text.startsWith(event.displayName) && (System.currentTimeMillis() - it.timestampMs) < 5000
                            }
                            val updatedEvents = if (recentIdx != -1 && event.reason == "LEFT") {
                                current.activityEvents.toMutableList().apply {
                                    this[recentIdx] = RoomActivityEvent(icon = "🚪", text = text)
                                }
                            } else if (recentIdx != -1 && current.activityEvents[recentIdx].text == text) {
                                current.activityEvents
                            } else {
                                (listOf(RoomActivityEvent(icon = "🚪", text = text)) + current.activityEvents).take(30)
                            }
                            current.copy(
                                participants = event.participants,
                                statusMessage = text,
                                activityEvents = updatedEvents,
                            )
                        }
                    }

                    is RoomSocketEvent.DraftShared -> {
                        val newEvent = RoomActivityEvent(icon = "🎵", text = "Draft \"${event.draft.name}\" was shared")
                        _uiState.update { current ->
                            val alreadyContains = current.sharedDrafts.any { it.draftId == event.draft.draftId }
                            val updatedList = if (alreadyContains) {
                                current.sharedDrafts
                            } else {
                                listOf(event.draft) + current.sharedDrafts
                            }
                            val updatedEvents = if (alreadyContains) {
                                current.activityEvents
                            } else {
                                (listOf(newEvent) + current.activityEvents).take(30)
                            }
                            current.copy(
                                sharedDrafts = updatedList,
                                statusMessage = "New draft shared: \"${event.draft.name}\"",
                                activityEvents = updatedEvents,
                            )
                        }
                    }

                    is RoomSocketEvent.Error -> {
                        _uiState.update { it.copy(errorMessage = event.message) }
                    }

                    is RoomSocketEvent.SpinStarted -> {
                        val newEvent = RoomActivityEvent(icon = "🎡", text = "Spin started with ${event.eligiblePlayers.size} contenders!")
                        _uiState.update { current ->
                            current.copy(
                                activityEvents = (listOf(newEvent) + current.activityEvents).take(30),
                            )
                        }
                    }

                    is RoomSocketEvent.UserEliminated -> {
                        val newEvent = RoomActivityEvent(icon = "⚡", text = "${event.eliminatedUser.displayName} was eliminated (#${event.eliminationOrder})")
                        _uiState.update { current ->
                            current.copy(
                                activityEvents = (listOf(newEvent) + current.activityEvents).take(30),
                            )
                        }
                    }

                    is RoomSocketEvent.WinnerAnnounced -> {
                        val newEvent = RoomActivityEvent(icon = "🏆", text = "Winner announced: ${event.winner.displayName}!")
                        _uiState.update { current ->
                            current.copy(
                                activityEvents = (listOf(newEvent) + current.activityEvents).take(30),
                            )
                        }
                    }
                }
            }
        }
    }

    private var syncJob: Job? = null

    /**
     * Resolves a user input to a valid backend ObjectId and display name.
     * 1. If blank: auto-creates a guest/host user.
     * 2. If valid 24-char hex ObjectId: uses directly.
     * 3. If nickname/display-name: creates user on backend to obtain a valid ObjectId.
     */
    private suspend fun resolveUserId(userInput: String, defaultPrefix: String): Result<Pair<String, String>> {
        val trimmed = userInput.trim()
        return if (trimmed.isBlank()) {
            val randomName = "$defaultPrefix ${(1000..9999).random()}"
            val userResult = apiClient.createUser(randomName)
            userResult.map { it.id to it.displayName }
        } else if (RoomApiClient.isValidObjectId(trimmed)) {
            Result.success(trimmed to trimmed)
        } else {
            val userResult = apiClient.createUser(trimmed)
            userResult.map { it.id to it.displayName }
        }
    }

    private fun startSyncLoop(roomId: String, userId: String) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                syncRoomState(roomId, userId)
            }
        }
    }

    private suspend fun syncRoomState(roomId: String, userId: String) {
        val result = apiClient.getRoomState(roomId, userId)
        if (result.isSuccess) {
            val state = result.getOrThrow()
            _uiState.update { current ->
                if (current.currentRoomId == roomId) {
                    current.copy(
                        roomInfo = state.room,
                        participants = state.participants,
                        sharedDrafts = state.sharedDrafts,
                    )
                } else current
            }
            val active = state.activeSpin
            if (active != null) {
                spinViewModel.restoreFromActiveSpin(active)
            }
        }
    }

    /**
     * Loads locally saved drafts from disk.
     */
    fun loadLocalDrafts(context: Context): List<Draft> {
        return try {
            val repo = DraftRepository(java.io.File(context.filesDir, "drafts"))
            repo.loadAll().map { it.draft }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Shares a local draft into the current room.
     */
    fun shareLocalDraft(draft: Draft, onComplete: (Boolean, String) -> Unit) {
        val roomId = _uiState.value.currentRoomId
        val userId = _uiState.value.currentUserId
        if (roomId == null || userId == null) {
            onComplete(false, "Not in an active room")
            return
        }

        viewModelScope.launch {
            val result = apiClient.createAndShareDraft(roomId, draft, userId)
            if (result.isSuccess) {
                syncRoomState(roomId, userId)
                onComplete(true, "Shared \"${draft.name}\" to room!")
            } else {
                val err = result.exceptionOrNull()
                onComplete(false, "Failed to share: ${err?.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Manually triggers an immediate synchronization of room state from the backend.
     */
    fun refreshRoomState() {
        val roomId = _uiState.value.currentRoomId ?: return
        val userId = _uiState.value.currentUserId ?: return
        viewModelScope.launch {
            syncRoomState(roomId, userId)
        }
    }

    /**
     * Re-attempts the real-time WebSocket connection and re-joins the active room.
     */
    fun retryConnection() {
        socketClient.reconnect()
        val roomId = _uiState.value.currentRoomId ?: return
        socketClient.joinRoom(roomId)
    }

    /**
     * Auto-provisions a guest user identity on the backend if none is supplied.
     */
    fun generateGuestUser(onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            val result = apiClient.createUser("Guest ${(1000..9999).random()}")
            if (result.isSuccess) {
                val user = result.getOrThrow()
                _uiState.update { it.copy(currentUserId = user.id, statusMessage = "Created user: ${user.displayName}") }
                onSuccess(user.id)
            } else {
                val err = result.exceptionOrNull()
                _uiState.update { it.copy(errorMessage = "Failed to create user: ${err?.message ?: "Unknown error"}") }
            }
        }
    }

    /**
     * Creates a new room via REST and establishes the Socket.IO real-time channel.
     * If [userId] is blank, automatically creates a guest user on the backend.
     */
    fun createAndJoinRoom(userId: String = "") {
        _uiState.update { it.copy(isJoiningOrLeaving = true, errorMessage = null) }

        viewModelScope.launch {
            val userRes = resolveUserId(userId, "Host")
            if (userRes.isFailure) {
                val error = userRes.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isJoiningOrLeaving = false,
                        errorMessage = "User creation failed: ${error?.message ?: "Unknown error"}",
                    )
                }
                return@launch
            }
            val (resolvedUserId, resolvedUserName) = userRes.getOrThrow()

            val result = apiClient.createRoom(resolvedUserId)
            if (result.isSuccess) {
                val roomState = result.getOrThrow()
                val roomId = roomState.room.id

                _uiState.update { current ->
                    current.copy(
                        isJoiningOrLeaving = false,
                        currentRoomId = roomId,
                        currentUserId = resolvedUserId,
                        roomInfo = roomState.room,
                        participants = roomState.participants,
                        sharedDrafts = roomState.sharedDrafts,
                        statusMessage = "Created room ${roomId.take(8)}...",
                    )
                }

                RoomSession.recordJoinedRoom(roomId, resolvedUserId, resolvedUserName)
                socketClient.connect(resolvedUserId)
                socketClient.joinRoom(roomId)
                startSyncLoop(roomId, resolvedUserId)
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
     * If [userId] is blank, automatically creates a guest user on the backend.
     */
    fun joinExistingRoom(roomId: String, userId: String = "") {
        val trimmedRoom = roomId.trim()

        if (trimmedRoom.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Room ID is required") }
            return
        }

        _uiState.update { it.copy(isJoiningOrLeaving = true, errorMessage = null) }

        viewModelScope.launch {
            val userRes = resolveUserId(userId, "Guest")
            if (userRes.isFailure) {
                val error = userRes.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isJoiningOrLeaving = false,
                        errorMessage = "User creation failed: ${error?.message ?: "Unknown error"}",
                    )
                }
                return@launch
            }
            val (resolvedUserId, resolvedUserName) = userRes.getOrThrow()

            val result = apiClient.joinRoom(trimmedRoom, resolvedUserId)
            if (result.isSuccess) {
                val roomState = result.getOrThrow()
                val activeRoomId = roomState.room.id

                _uiState.update { current ->
                    current.copy(
                        isJoiningOrLeaving = false,
                        currentRoomId = activeRoomId,
                        currentUserId = resolvedUserId,
                        roomInfo = roomState.room,
                        participants = roomState.participants,
                        sharedDrafts = roomState.sharedDrafts,
                        statusMessage = "Joined room ${activeRoomId.take(8)}...",
                    )
                }

                RoomSession.recordJoinedRoom(activeRoomId, resolvedUserId, resolvedUserName)
                socketClient.connect(resolvedUserId)
                socketClient.joinRoom(activeRoomId)
                startSyncLoop(activeRoomId, resolvedUserId)
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

        RoomSession.clearActiveRoom()
        syncJob?.cancel()
        syncJob = null
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

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
        syncJob = null
        socketClient.disconnect()
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}

