package com.roxstar.voicedraft.spin

import com.roxstar.voicedraft.network.ActiveSpinDto
import com.roxstar.voicedraft.network.RoomSocketEvent
import com.roxstar.voicedraft.network.SpinPlayerDto

/**
 * Game status of the real-time Spin elimination game.
 */
enum class SpinGameStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    ABORTED,
}

/**
 * Immutable UI state for the live Spin elimination game.
 */
data class SpinUiState(
    val spinId: String? = null,
    val status: SpinGameStatus = SpinGameStatus.IDLE,
    val eligiblePlayers: List<SpinPlayerDto> = emptyList(),
    val remainingPlayers: List<SpinPlayerDto> = emptyList(),
    val eliminatedPlayers: List<SpinPlayerDto> = emptyList(),
    val recentlyEliminatedUser: SpinPlayerDto? = null,
    val winner: SpinPlayerDto? = null,
    val isStarting: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val lastSequenceNumber: Long = 0L,
) {
    val isSpinActive: Boolean
        get() = status == SpinGameStatus.RUNNING

    val isSpinFinished: Boolean
        get() = status == SpinGameStatus.COMPLETED || status == SpinGameStatus.ABORTED
}

/**
 * Pure reducer for [SpinUiState] transitions.
 * Side-effect free, testable on JVM without Android or Socket dependencies.
 */
object SpinReducer {

    fun startRequested(current: SpinUiState): SpinUiState {
        return current.copy(
            isStarting = true,
            errorMessage = null,
        )
    }

    fun startFailed(current: SpinUiState, message: String): SpinUiState {
        return current.copy(
            isStarting = false,
            errorMessage = message,
        )
    }

    fun spinStarted(current: SpinUiState, event: RoomSocketEvent.SpinStarted): SpinUiState {
        return current.copy(
            spinId = event.spinId,
            status = when (event.status) {
                "RUNNING" -> SpinGameStatus.RUNNING
                "COMPLETED" -> SpinGameStatus.COMPLETED
                "ABORTED" -> SpinGameStatus.ABORTED
                else -> SpinGameStatus.RUNNING
            },
            eligiblePlayers = event.eligiblePlayers,
            remainingPlayers = event.remainingPlayers,
            eliminatedPlayers = emptyList(),
            recentlyEliminatedUser = null,
            winner = null,
            isStarting = false,
            errorMessage = null,
            statusMessage = "Spin started with ${event.eligiblePlayers.size} players!",
            lastSequenceNumber = event.sequenceNumber,
        )
    }

    fun userEliminated(current: SpinUiState, event: RoomSocketEvent.UserEliminated): SpinUiState {
        val updatedEliminated = if (current.eliminatedPlayers.any { it.userId == event.eliminatedUser.userId }) {
            current.eliminatedPlayers
        } else {
            current.eliminatedPlayers + event.eliminatedUser
        }

        val reasonText = when (event.eliminatedUser.eliminationReason) {
            "LEFT" -> "left the room"
            "TIMER" -> "eliminated by the wheel"
            else -> "eliminated"
        }

        return current.copy(
            spinId = event.spinId,
            remainingPlayers = event.remainingPlayers,
            eliminatedPlayers = updatedEliminated,
            recentlyEliminatedUser = event.eliminatedUser,
            statusMessage = "${event.eliminatedUser.displayName} $reasonText (#${event.eliminationOrder})",
            lastSequenceNumber = event.sequenceNumber,
        )
    }

    fun winnerAnnounced(current: SpinUiState, event: RoomSocketEvent.WinnerAnnounced): SpinUiState {
        return current.copy(
            spinId = event.spinId,
            status = SpinGameStatus.COMPLETED,
            winner = event.winner,
            remainingPlayers = listOf(event.winner),
            statusMessage = "Winner: ${event.winner.displayName}!",
            lastSequenceNumber = event.sequenceNumber,
        )
    }

    fun activeSpinRestored(current: SpinUiState, spin: ActiveSpinDto): SpinUiState {
        val gameStatus = when (spin.status) {
            "RUNNING" -> SpinGameStatus.RUNNING
            "COMPLETED" -> SpinGameStatus.COMPLETED
            "ABORTED" -> SpinGameStatus.ABORTED
            else -> SpinGameStatus.IDLE
        }

        val eliminated = spin.participants.filter { it.status == "ELIMINATED" }
            .sortedBy { it.eliminationOrder ?: Int.MAX_VALUE }

        return current.copy(
            spinId = spin.spinId,
            status = gameStatus,
            eligiblePlayers = spin.participants,
            remainingPlayers = spin.remainingPlayers,
            eliminatedPlayers = eliminated,
            winner = spin.winner,
            lastSequenceNumber = spin.lastSequenceNumber,
        )
    }

    fun clearStatus(current: SpinUiState): SpinUiState = current.copy(statusMessage = null)

    fun clearError(current: SpinUiState): SpinUiState = current.copy(errorMessage = null)

    fun reset(): SpinUiState = SpinUiState()
}
