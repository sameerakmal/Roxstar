package com.roxstar.voicedraft.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSocketClientTest {

    @Test
    fun eventNames_matchBackendContractExactly() {
        assertEquals("room_state", RoomSocketClient.EVENT_ROOM_STATE)
        assertEquals("user_joined", RoomSocketClient.EVENT_USER_JOINED)
        assertEquals("user_left", RoomSocketClient.EVENT_USER_LEFT)
        assertEquals("draft_shared", RoomSocketClient.EVENT_DRAFT_SHARED)
        assertEquals("join_room", RoomSocketClient.CLIENT_JOIN_ROOM)
        assertEquals("leave_room", RoomSocketClient.CLIENT_LEAVE_ROOM)
    }

    @Test
    fun userJoinedPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "user": {
                    "userId": "661234567890123456789034",
                    "displayName": "Bob"
                },
                "participants": [
                    {
                        "userId": "661234567890123456789034",
                        "displayName": "Bob",
                        "membershipState": "ACTIVE",
                        "connectionState": "CONNECTED",
                        "joinedAt": "2026-09-17T12:00:00.000Z"
                    }
                ]
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val roomId = json.getString("roomId")
        val userObj = json.getJSONObject("user")
        val userId = userObj.getString("userId")
        val displayName = userObj.getString("displayName")
        val participantsArray = json.getJSONArray("participants")

        val participants = ArrayList<ParticipantDto>()
        for (i in 0 until participantsArray.length()) {
            participants.add(ParticipantDto.fromJson(participantsArray.getJSONObject(i)))
        }

        val event = RoomSocketEvent.UserJoined(
            roomId = roomId,
            userId = userId,
            displayName = displayName,
            participants = participants,
        )

        assertEquals("661234567890123456789012", event.roomId)
        assertEquals("661234567890123456789034", event.userId)
        assertEquals("Bob", event.displayName)
        assertEquals(1, event.participants.size)
        assertEquals("CONNECTED", event.participants[0].connectionState)
    }

    @Test
    fun userLeftPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "user": {
                    "userId": "661234567890123456789034",
                    "displayName": "Bob"
                },
                "reason": "LEFT",
                "participants": []
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val event = RoomSocketEvent.UserLeft(
            roomId = json.getString("roomId"),
            userId = json.getJSONObject("user").getString("userId"),
            displayName = json.getJSONObject("user").getString("displayName"),
            reason = json.optString("reason", "LEFT"),
            participants = emptyList(),
        )

        assertEquals("LEFT", event.reason)
        assertEquals("Bob", event.displayName)
        assertTrue(event.participants.isEmpty())
    }

    @Test
    fun draftSharedPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "draft": {
                    "draftId": "661234567890123456789056",
                    "name": "Shared Voice Clip",
                    "durationMs": 4200,
                    "effect": "REVERB",
                    "fileLocation": "/path/to/clip.wav",
                    "sharedByUserId": "661234567890123456789034",
                    "sharedAt": "2026-09-17T12:05:00.000Z"
                }
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val draft = SharedDraftDto.fromJson(json.getJSONObject("draft"))
        val event = RoomSocketEvent.DraftShared(
            roomId = json.getString("roomId"),
            draft = draft,
        )

        assertEquals("661234567890123456789056", event.draft.draftId)
        assertEquals("Shared Voice Clip", event.draft.name)
        assertEquals("REVERB", event.draft.effect)
        assertEquals(4200L, event.draft.durationMs)
    }

    @Test
    fun roomStateDto_parsesCorrectly() {
        val jsonString = """
            {
                "room": {
                    "id": "661234567890123456789012",
                    "status": "ACTIVE",
                    "ownerUserId": "661234567890123456789034",
                    "createdAt": "2026-09-17T12:00:00.000Z",
                    "updatedAt": "2026-09-17T12:00:00.000Z"
                },
                "participants": [
                    {
                        "userId": "661234567890123456789034",
                        "displayName": "Alice",
                        "membershipState": "ACTIVE",
                        "connectionState": "CONNECTED"
                    }
                ],
                "sharedDrafts": [
                    {
                        "draftId": "661234567890123456789056",
                        "name": "Demo Take",
                        "durationMs": 3000,
                        "effect": "NONE",
                        "fileLocation": "/storage/take.wav",
                        "sharedByUserId": "661234567890123456789034"
                    }
                ]
            }
        """.trimIndent()

        val state = RoomStateDto.fromJson(JSONObject(jsonString))
        assertEquals("661234567890123456789012", state.room.id)
        assertEquals("ACTIVE", state.room.status)
        assertEquals(1, state.participants.size)
        assertEquals("Alice", state.participants[0].displayName)
        assertEquals(1, state.sharedDrafts.size)
        assertEquals("Demo Take", state.sharedDrafts[0].name)
    }

    @Test
    fun spinStartedPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "spinId": "661234567890123456789099",
                "status": "RUNNING",
                "startedAt": "2026-09-17T12:00:00.000Z",
                "eligiblePlayers": [
                    { "userId": "u1", "displayName": "Alice", "status": "ACTIVE" },
                    { "userId": "u2", "displayName": "Bob", "status": "ACTIVE" },
                    { "userId": "u3", "displayName": "Charlie", "status": "ACTIVE" }
                ],
                "remainingPlayers": [
                    { "userId": "u1", "displayName": "Alice", "status": "ACTIVE" },
                    { "userId": "u2", "displayName": "Bob", "status": "ACTIVE" },
                    { "userId": "u3", "displayName": "Charlie", "status": "ACTIVE" }
                ],
                "sequenceNumber": 1
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val event = RoomSocketEvent.SpinStarted(
            roomId = json.getString("roomId"),
            spinId = json.getString("spinId"),
            status = json.optString("status", "RUNNING"),
            startedAt = json.optString("startedAt"),
            eligiblePlayers = (0 until json.getJSONArray("eligiblePlayers").length()).map {
                SpinPlayerDto.fromJson(json.getJSONArray("eligiblePlayers").getJSONObject(it))
            },
            remainingPlayers = (0 until json.getJSONArray("remainingPlayers").length()).map {
                SpinPlayerDto.fromJson(json.getJSONArray("remainingPlayers").getJSONObject(it))
            },
            sequenceNumber = json.getLong("sequenceNumber"),
        )

        assertEquals("661234567890123456789099", event.spinId)
        assertEquals("RUNNING", event.status)
        assertEquals(3, event.eligiblePlayers.size)
        assertEquals(3, event.remainingPlayers.size)
        assertEquals(1L, event.sequenceNumber)
    }

    @Test
    fun userEliminatedPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "spinId": "661234567890123456789099",
                "eliminatedUser": {
                    "userId": "u2",
                    "displayName": "Bob",
                    "status": "ELIMINATED",
                    "eliminationOrder": 1,
                    "eliminationReason": "TIMER"
                },
                "eliminationOrder": 1,
                "remainingPlayers": [
                    { "userId": "u1", "displayName": "Alice", "status": "ACTIVE" },
                    { "userId": "u3", "displayName": "Charlie", "status": "ACTIVE" }
                ],
                "sequenceNumber": 2
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val event = RoomSocketEvent.UserEliminated(
            roomId = json.getString("roomId"),
            spinId = json.getString("spinId"),
            eliminatedUser = SpinPlayerDto.fromJson(json.getJSONObject("eliminatedUser")),
            eliminationOrder = json.getInt("eliminationOrder"),
            remainingPlayers = (0 until json.getJSONArray("remainingPlayers").length()).map {
                SpinPlayerDto.fromJson(json.getJSONArray("remainingPlayers").getJSONObject(it))
            },
            sequenceNumber = json.getLong("sequenceNumber"),
        )

        assertEquals("661234567890123456789099", event.spinId)
        assertEquals("u2", event.eliminatedUser.userId)
        assertEquals(1, event.eliminationOrder)
        assertEquals("TIMER", event.eliminatedUser.eliminationReason)
        assertEquals(2, event.remainingPlayers.size)
        assertEquals(2L, event.sequenceNumber)
    }

    @Test
    fun winnerAnnouncedPayload_parsesCorrectly() {
        val jsonString = """
            {
                "roomId": "661234567890123456789012",
                "spinId": "661234567890123456789099",
                "status": "COMPLETED",
                "winner": {
                    "userId": "u1",
                    "displayName": "Alice",
                    "status": "WINNER"
                },
                "completedAt": "2026-09-17T12:01:00.000Z",
                "sequenceNumber": 3
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val event = RoomSocketEvent.WinnerAnnounced(
            roomId = json.getString("roomId"),
            spinId = json.getString("spinId"),
            status = json.getString("status"),
            winner = SpinPlayerDto.fromJson(json.getJSONObject("winner")),
            completedAt = json.getString("completedAt"),
            sequenceNumber = json.getLong("sequenceNumber"),
        )

        assertEquals("661234567890123456789099", event.spinId)
        assertEquals("COMPLETED", event.status)
        assertEquals("u1", event.winner.userId)
        assertEquals("Alice", event.winner.displayName)
        assertEquals(3L, event.sequenceNumber)
    }

    @Test
    fun roomStateDto_withActiveSpin_parsesCorrectly() {
        val jsonString = """
            {
                "room": {
                    "id": "661234567890123456789012",
                    "status": "ACTIVE",
                    "ownerUserId": "661234567890123456789034"
                },
                "participants": [],
                "sharedDrafts": [],
                "activeSpin": {
                    "spinId": "661234567890123456789099",
                    "status": "RUNNING",
                    "startedAt": "2026-09-17T12:00:00.000Z",
                    "participants": [
                        { "userId": "u1", "displayName": "Alice", "status": "ACTIVE" }
                    ],
                    "remainingPlayers": [
                        { "userId": "u1", "displayName": "Alice", "status": "ACTIVE" }
                    ],
                    "lastSequenceNumber": 1
                }
            }
        """.trimIndent()

        val state = RoomStateDto.fromJson(JSONObject(jsonString))
        assertNotNull(state.activeSpin)
        val active = state.activeSpin!!
        assertEquals("661234567890123456789099", active.spinId)
        assertEquals("RUNNING", active.status)
        assertEquals(1, active.participants.size)
        assertEquals(1L, active.lastSequenceNumber)
    }
}
