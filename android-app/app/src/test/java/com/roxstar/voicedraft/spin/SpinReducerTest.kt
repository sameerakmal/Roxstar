package com.roxstar.voicedraft.spin

import com.roxstar.voicedraft.network.ActiveSpinDto
import com.roxstar.voicedraft.network.RoomSocketEvent
import com.roxstar.voicedraft.network.SpinPlayerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinReducerTest {

    private val player1 = SpinPlayerDto(
        userId = "user-1",
        displayName = "Alice",
        status = "ACTIVE",
    )
    private val player2 = SpinPlayerDto(
        userId = "user-2",
        displayName = "Bob",
        status = "ACTIVE",
    )
    private val player3 = SpinPlayerDto(
        userId = "user-3",
        displayName = "Charlie",
        status = "ACTIVE",
    )

    @Test
    fun initialState_isIdle() {
        val state = SpinUiState()

        assertEquals(SpinGameStatus.IDLE, state.status)
        assertFalse(state.isSpinActive)
        assertFalse(state.isSpinFinished)
        assertNull(state.spinId)
        assertTrue(state.eligiblePlayers.isEmpty())
        assertTrue(state.remainingPlayers.isEmpty())
        assertTrue(state.eliminatedPlayers.isEmpty())
        assertNull(state.recentlyEliminatedUser)
        assertNull(state.winner)
        assertFalse(state.isStarting)
        assertNull(state.errorMessage)
        assertNull(state.statusMessage)
    }

    @Test
    fun startRequested_setsIsStarting() {
        val initial = SpinUiState(errorMessage = "Previous error")
        val updated = SpinReducer.startRequested(initial)

        assertTrue(updated.isStarting)
        assertNull(updated.errorMessage)
    }

    @Test
    fun startFailed_resetsIsStartingAndSetsError() {
        val starting = SpinUiState(isStarting = true)
        val updated = SpinReducer.startFailed(starting, "Room requires at least 3 players")

        assertFalse(updated.isStarting)
        assertEquals("Room requires at least 3 players", updated.errorMessage)
    }

    @Test
    fun spinStarted_transitionsToRunningAndSetsPlayers() {
        val starting = SpinUiState(isStarting = true)
        val event = RoomSocketEvent.SpinStarted(
            roomId = "room-1",
            spinId = "spin-1",
            status = "RUNNING",
            startedAt = "2026-09-17T12:00:00.000Z",
            eligiblePlayers = listOf(player1, player2, player3),
            remainingPlayers = listOf(player1, player2, player3),
            sequenceNumber = 1L,
        )

        val updated = SpinReducer.spinStarted(starting, event)

        assertEquals("spin-1", updated.spinId)
        assertEquals(SpinGameStatus.RUNNING, updated.status)
        assertTrue(updated.isSpinActive)
        assertFalse(updated.isSpinFinished)
        assertFalse(updated.isStarting)
        assertEquals(3, updated.eligiblePlayers.size)
        assertEquals(3, updated.remainingPlayers.size)
        assertTrue(updated.eliminatedPlayers.isEmpty())
        assertNull(updated.winner)
        assertEquals(1L, updated.lastSequenceNumber)
        assertNotNull(updated.statusMessage)
    }

    @Test
    fun userEliminated_updatesRemainingAndEliminatedLists() {
        val running = SpinUiState(
            spinId = "spin-1",
            status = SpinGameStatus.RUNNING,
            eligiblePlayers = listOf(player1, player2, player3),
            remainingPlayers = listOf(player1, player2, player3),
            eliminatedPlayers = emptyList(),
        )

        val eliminatedBob = player2.copy(
            status = "ELIMINATED",
            eliminationOrder = 1,
            eliminationReason = "TIMER",
        )

        val event = RoomSocketEvent.UserEliminated(
            roomId = "room-1",
            spinId = "spin-1",
            eliminatedUser = eliminatedBob,
            eliminationOrder = 1,
            remainingPlayers = listOf(player1, player3),
            sequenceNumber = 2L,
        )

        val updated = SpinReducer.userEliminated(running, event)

        assertEquals(2, updated.remainingPlayers.size)
        assertEquals(1, updated.eliminatedPlayers.size)
        assertEquals("user-2", updated.eliminatedPlayers[0].userId)
        assertEquals(eliminatedBob, updated.recentlyEliminatedUser)
        assertEquals(2L, updated.lastSequenceNumber)
        assertTrue(updated.statusMessage!!.contains("Bob"))
    }

    @Test
    fun winnerAnnounced_transitionsToCompletedAndSetsWinner() {
        val running = SpinUiState(
            spinId = "spin-1",
            status = SpinGameStatus.RUNNING,
            eligiblePlayers = listOf(player1, player2, player3),
            remainingPlayers = listOf(player1),
            eliminatedPlayers = listOf(player2, player3),
        )

        val winnerAlice = player1.copy(status = "WINNER")
        val event = RoomSocketEvent.WinnerAnnounced(
            roomId = "room-1",
            spinId = "spin-1",
            status = "COMPLETED",
            winner = winnerAlice,
            completedAt = "2026-09-17T12:01:00.000Z",
            sequenceNumber = 3L,
        )

        val updated = SpinReducer.winnerAnnounced(running, event)

        assertEquals(SpinGameStatus.COMPLETED, updated.status)
        assertFalse(updated.isSpinActive)
        assertTrue(updated.isSpinFinished)
        assertEquals(winnerAlice, updated.winner)
        assertEquals(3L, updated.lastSequenceNumber)
        assertTrue(updated.statusMessage!!.contains("Winner: Alice"))
    }

    @Test
    fun activeSpinRestored_populatesStateFromActiveSpinDto() {
        val initial = SpinUiState()
        val activeDto = ActiveSpinDto(
            spinId = "spin-99",
            status = "RUNNING",
            startedAt = "2026-09-17T12:00:00.000Z",
            participants = listOf(
                player1,
                player2.copy(status = "ELIMINATED", eliminationOrder = 1),
                player3,
            ),
            remainingPlayers = listOf(player1, player3),
            winner = null,
            lastSequenceNumber = 5L,
        )

        val restored = SpinReducer.activeSpinRestored(initial, activeDto)

        assertEquals("spin-99", restored.spinId)
        assertEquals(SpinGameStatus.RUNNING, restored.status)
        assertEquals(3, restored.eligiblePlayers.size)
        assertEquals(2, restored.remainingPlayers.size)
        assertEquals(1, restored.eliminatedPlayers.size)
        assertEquals("user-2", restored.eliminatedPlayers[0].userId)
        assertEquals(5L, restored.lastSequenceNumber)
    }

    @Test
    fun clearStatus_and_clearError_and_reset() {
        val state = SpinUiState(
            statusMessage = "Message",
            errorMessage = "Error",
            status = SpinGameStatus.RUNNING,
        )

        val noStatus = SpinReducer.clearStatus(state)
        assertNull(noStatus.statusMessage)
        assertEquals("Error", noStatus.errorMessage)

        val noError = SpinReducer.clearError(state)
        assertNull(noError.errorMessage)
        assertEquals("Message", noError.statusMessage)

        val resetState = SpinReducer.reset()
        assertEquals(SpinGameStatus.IDLE, resetState.status)
    }
}
