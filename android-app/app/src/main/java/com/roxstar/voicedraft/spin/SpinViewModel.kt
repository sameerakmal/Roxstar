package com.roxstar.voicedraft.spin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roxstar.voicedraft.network.ActiveSpinDto
import com.roxstar.voicedraft.network.RoomApiClient
import com.roxstar.voicedraft.network.RoomSocketClient
import com.roxstar.voicedraft.network.RoomSocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the real-time Spin elimination game state.
 *
 * Listens to Socket.IO events (spin_started, user_eliminated, winner_announced)
 * via [RoomSocketClient] and triggers authoritative game initiation via [RoomApiClient].
 */
class SpinViewModel(
    private val apiClient: RoomApiClient = RoomApiClient(),
    private val socketClient: RoomSocketClient = RoomSocketClient(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpinUiState())
    val uiState: StateFlow<SpinUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            socketClient.events.collectLatest { event ->
                when (event) {
                    is RoomSocketEvent.SpinStarted -> {
                        _uiState.update { current ->
                            SpinReducer.spinStarted(current, event)
                        }
                    }

                    is RoomSocketEvent.UserEliminated -> {
                        _uiState.update { current ->
                            SpinReducer.userEliminated(current, event)
                        }
                    }

                    is RoomSocketEvent.WinnerAnnounced -> {
                        _uiState.update { current ->
                            SpinReducer.winnerAnnounced(current, event)
                        }
                    }

                    is RoomSocketEvent.RoomStateReceived -> {
                        val active = event.state.activeSpin
                        if (active != null) {
                            _uiState.update { current ->
                                SpinReducer.activeSpinRestored(current, active)
                            }
                        }
                    }

                    else -> {
                        // Other room events handled by RoomViewModel
                    }
                }
            }
        }
    }

    /**
     * Starts the spin elimination game for [roomId] on behalf of [userId] (room owner).
     */
    fun startSpin(roomId: String, userId: String) {
        val trimmedRoom = roomId.trim()
        val trimmedUser = userId.trim()

        if (trimmedRoom.isBlank() || trimmedUser.isBlank()) {
            _uiState.update { SpinReducer.startFailed(it, "Room ID and User ID are required to start spin") }
            return
        }

        _uiState.update { SpinReducer.startRequested(it) }

        viewModelScope.launch {
            val result = apiClient.startSpin(trimmedRoom, trimmedUser)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                _uiState.update {
                    SpinReducer.startFailed(
                        it,
                        "Failed to start spin: ${error?.message ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    /**
     * Restores spin state from an authoritative [ActiveSpinDto] snapshot.
     */
    fun restoreFromActiveSpin(activeSpin: ActiveSpinDto) {
        _uiState.update { current ->
            SpinReducer.activeSpinRestored(current, activeSpin)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { SpinReducer.clearError(it) }
    }

    fun clearStatusMessage() {
        _uiState.update { SpinReducer.clearStatus(it) }
    }

    fun reset() {
        _uiState.update { SpinReducer.reset() }
    }
}
