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
}
