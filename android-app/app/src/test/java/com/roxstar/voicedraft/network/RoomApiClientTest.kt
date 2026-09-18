package com.roxstar.voicedraft.network

import com.roxstar.voicedraft.Draft
import com.roxstar.voicedraft.Effect
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RoomApiClient

    private val testUserId = "661234567890123456789012"
    private val testRoomId = "661234567890123456789034"
    private val testDraftId = "661234567890123456789056"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RoomApiClient(baseUrlProvider = { server.url("/").toString() })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun shareDraft_sendsCorrectRequestAndParsesSuccessfulResponse() = runBlocking {
        val jsonResponse = """
            {
                "draftId": "$testDraftId",
                "name": "My Voice Take",
                "durationMs": 4500,
                "effect": "ECHO",
                "fileLocation": "/path/to/take.wav",
                "sharedByUserId": "$testUserId",
                "sharedAt": "2026-09-17T12:00:00.000Z"
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.shareDraft(
            roomId = testRoomId,
            draftId = testDraftId,
            userId = testUserId,
        )

        assertTrue("Expected successful share", result.isSuccess)
        val dto = result.getOrThrow()
        assertEquals(testDraftId, dto.draftId)
        assertEquals("My Voice Take", dto.name)
        assertEquals(4500L, dto.durationMs)
        assertEquals("ECHO", dto.effect)
        assertEquals(testUserId, dto.sharedByUserId)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rooms/$testRoomId/drafts", recorded.path)
        assertEquals(testUserId, recorded.getHeader("x-user-id"))
        assertTrue(recorded.body.readUtf8().contains(testDraftId))
    }

    @Test
    fun shareDraft_handlesIdempotent200Response() = runBlocking {
        val jsonResponse = """
            {
                "draftId": "$testDraftId",
                "name": "Duplicate Share",
                "durationMs": 3000,
                "effect": "NONE",
                "fileLocation": "/path/to/dup.wav",
                "sharedByUserId": "$testUserId",
                "sharedAt": "2026-09-17T12:05:00.000Z"
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.shareDraft(
            roomId = testRoomId,
            draftId = testDraftId,
            userId = testUserId,
        )

        assertTrue(result.isSuccess)
        assertEquals(testDraftId, result.getOrThrow().draftId)
    }

    @Test
    fun shareDraft_mapsBackendErrorEnvelopeToApiException() = runBlocking {
        val errorJson = """
            {
                "error": {
                    "code": "ROOM_NOT_FOUND",
                    "message": "Room $testRoomId not found",
                    "details": []
                }
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody(errorJson)
        )

        val result = client.shareDraft(
            roomId = testRoomId,
            draftId = testDraftId,
            userId = testUserId,
        )

        assertTrue("Expected failure", result.isFailure)
        val exception = result.exceptionOrNull() as? ApiException
        assertNotNull("Expected ApiException", exception)
        assertEquals(404, exception!!.statusCode)
        assertEquals("ROOM_NOT_FOUND", exception.errorCode)
        assertTrue(exception.message!!.contains("Room $testRoomId not found"))
    }

    @Test
    fun createDraft_sendsCorrectPayloadAndParsesDraftDto() = runBlocking {
        val jsonResponse = """
            {
                "id": "$testDraftId",
                "ownerUserId": "$testUserId",
                "name": "New Local Draft",
                "durationMs": 2500,
                "effect": "REVERB",
                "fileLocation": "/storage/draft.wav",
                "createdAt": "2026-09-17T12:10:00.000Z"
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.createDraft(
            name = "New Local Draft",
            durationMs = 2500L,
            fileLocation = "/storage/draft.wav",
            effect = "REVERB",
            userId = testUserId,
        )

        assertTrue(result.isSuccess)
        val draft = result.getOrThrow()
        assertEquals(testDraftId, draft.id)
        assertEquals("New Local Draft", draft.name)
        assertEquals("REVERB", draft.effect)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/drafts", recorded.path)
        assertEquals(testUserId, recorded.getHeader("x-user-id"))
    }

    @Test
    fun createAndShareDraft_autoRegistersUuidDraftBeforeSharing() = runBlocking {
        val draft = Draft(
            id = "3fa85f64-5717-4562-b3fc-2c963f66afa6", // 36-char local UUID
            name = "UUID Take",
            createdAt = 1726574400000L,
            durationMs = 3200L,
            effect = Effect.PITCH_SHIFT,
            filePath = "/data/user/0/com.roxstar.voicedraft/files/drafts/take.wav",
        )

        // Step 1: createDraft response
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "id": "$testDraftId",
                        "ownerUserId": "$testUserId",
                        "name": "${draft.name}",
                        "durationMs": ${draft.durationMs},
                        "effect": "PITCH_SHIFT",
                        "fileLocation": "${draft.filePath}",
                        "createdAt": "2026-09-17T12:15:00.000Z"
                    }
                    """.trimIndent()
                )
        )

        // Step 2: shareDraft response
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "draftId": "$testDraftId",
                        "name": "${draft.name}",
                        "durationMs": ${draft.durationMs},
                        "effect": "PITCH_SHIFT",
                        "fileLocation": "${draft.filePath}",
                        "sharedByUserId": "$testUserId",
                        "sharedAt": "2026-09-17T12:15:05.000Z"
                    }
                    """.trimIndent()
                )
        )

        val result = client.createAndShareDraft(
            roomId = testRoomId,
            draft = draft,
            userId = testUserId,
        )

        assertTrue(result.isSuccess)
        assertEquals(testDraftId, result.getOrThrow().draftId)

        // Verify request sequence: 1st POST /drafts, 2nd POST /rooms/.../drafts
        val req1 = server.takeRequest()
        assertEquals("/drafts", req1.path)
        val req2 = server.takeRequest()
        assertEquals("/rooms/$testRoomId/drafts", req2.path)
    }

    @Test
    fun createRoom_sendsCorrectRequestAndParsesState() = runBlocking {
        val jsonResponse = """
            {
                "room": {
                    "id": "$testRoomId",
                    "status": "ACTIVE",
                    "ownerUserId": "$testUserId",
                    "createdAt": "2026-09-17T12:00:00.000Z",
                    "updatedAt": "2026-09-17T12:00:00.000Z"
                },
                "participants": [
                    {
                        "userId": "$testUserId",
                        "displayName": "Alice",
                        "membershipState": "ACTIVE",
                        "connectionState": "DISCONNECTED",
                        "joinedAt": "2026-09-17T12:00:00.000Z"
                    }
                ],
                "sharedDrafts": []
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.createRoom(userId = testUserId)
        assertTrue(result.isSuccess)
        val state = result.getOrThrow()
        assertEquals(testRoomId, state.room.id)
        assertEquals("ACTIVE", state.room.status)
        assertEquals(1, state.participants.size)
        assertEquals("Alice", state.participants[0].displayName)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rooms", recorded.path)
        assertEquals(testUserId, recorded.getHeader("x-user-id"))
    }

    @Test
    fun joinRoom_sendsCorrectRequestAndParsesState() = runBlocking {
        val jsonResponse = """
            {
                "room": {
                    "id": "$testRoomId",
                    "status": "ACTIVE",
                    "ownerUserId": "$testUserId"
                },
                "participants": [],
                "sharedDrafts": []
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.joinRoom(roomId = testRoomId, userId = testUserId)
        assertTrue(result.isSuccess)
        assertEquals(testRoomId, result.getOrThrow().room.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rooms/$testRoomId/join", recorded.path)
    }

    @Test
    fun getRoomState_sendsGetRequestAndParsesState() = runBlocking {
        val jsonResponse = """
            {
                "room": {
                    "id": "$testRoomId",
                    "status": "ACTIVE",
                    "ownerUserId": "$testUserId"
                },
                "participants": [],
                "sharedDrafts": []
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.getRoomState(roomId = testRoomId, userId = testUserId)
        assertTrue(result.isSuccess)
        assertEquals(testRoomId, result.getOrThrow().room.id)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/rooms/$testRoomId", recorded.path)
    }

    @Test
    fun backendConfig_honorsBaseUrlConfiguration() {
        val custom = "http://192.168.1.50:8080/"
        BackendConfig.baseUrl = custom
        assertEquals("http://192.168.1.50:8080", BackendConfig.baseUrl)

        BackendConfig.resetToDefault()
        assertFalse(BackendConfig.baseUrl.endsWith("/"))
    }

    @Test
    fun createUser_sendsCorrectRequestAndParsesUser() = runBlocking {
        val jsonResponse = """
            {
                "id": "661234567890123456789099",
                "displayName": "Guest 42",
                "createdAt": "2026-09-17T14:00:00.000Z"
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = client.createUser("Guest 42")
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("661234567890123456789099", user.id)
        assertEquals("Guest 42", user.displayName)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/users", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"displayName\":\"Guest 42\""))
    }

    @Test
    fun isValidObjectId_correctlyIdentifies24CharHex() {
        assertTrue(RoomApiClient.isValidObjectId("661234567890123456789012"))
        assertTrue(RoomApiClient.isValidObjectId("abcdef1234567890abcdef12"))
        assertFalse(RoomApiClient.isValidObjectId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) // UUID
        assertFalse(RoomApiClient.isValidObjectId("short"))
        assertFalse(RoomApiClient.isValidObjectId(""))
    }
}
