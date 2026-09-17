package com.roxstar.voicedraft.room

import com.roxstar.voicedraft.network.ParticipantDto
import com.roxstar.voicedraft.network.RoomDto
import com.roxstar.voicedraft.network.SharedDraftDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomUiStateTest {

    @Test
    fun initialState_isDisconnectedAndNotInRoom() {
        val state = RoomUiState()

        assertEquals(RoomConnectionStatus.DISCONNECTED, state.connectionStatus)
        assertFalse(state.isJoiningOrLeaving)
        assertFalse(state.isInRoom)
        assertNull(state.currentRoomId)
        assertNull(state.currentUserId)
        assertNull(state.roomInfo)
        assertTrue(state.participants.isEmpty())
        assertTrue(state.sharedDrafts.isEmpty())
        assertNull(state.errorMessage)
        assertNull(state.statusMessage)
    }

    @Test
    fun isInRoom_returnsTrueWhenRoomIdIsPresent() {
        val inRoom = RoomUiState(currentRoomId = "661234567890123456789012")
        assertTrue(inRoom.isInRoom)

        val lobby = RoomUiState(currentRoomId = null)
        assertFalse(lobby.isInRoom)
    }

    @Test
    fun stateCopy_updatesFieldsCorrectly() {
        val initial = RoomUiState()
        val roomDto = RoomDto(id = "room-1", status = "ACTIVE", ownerUserId = "user-1")
        val participant = ParticipantDto(
            userId = "user-1",
            displayName = "Alice",
            membershipState = "ACTIVE",
            connectionState = "CONNECTED",
        )
        val draft = SharedDraftDto(
            draftId = "draft-1",
            name = "Take 1",
            durationMs = 3000L,
            effect = "ECHO",
            fileLocation = "/take1.wav",
            sharedByUserId = "user-1",
            sharedAt = "2026-09-17T12:00:00.000Z",
        )

        val updated = initial.copy(
            connectionStatus = RoomConnectionStatus.CONNECTED,
            currentRoomId = "room-1",
            currentUserId = "user-1",
            roomInfo = roomDto,
            participants = listOf(participant),
            sharedDrafts = listOf(draft),
            statusMessage = "Joined room room-1",
        )

        assertTrue(updated.isInRoom)
        assertEquals(RoomConnectionStatus.CONNECTED, updated.connectionStatus)
        assertEquals("room-1", updated.currentRoomId)
        assertEquals("user-1", updated.currentUserId)
        assertEquals(1, updated.participants.size)
        assertEquals("Alice", updated.participants[0].displayName)
        assertEquals(1, updated.sharedDrafts.size)
        assertEquals("Take 1", updated.sharedDrafts[0].name)
        assertEquals("Joined room room-1", updated.statusMessage)
    }
}
