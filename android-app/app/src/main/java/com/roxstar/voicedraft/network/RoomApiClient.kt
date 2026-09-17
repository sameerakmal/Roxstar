package com.roxstar.voicedraft.network

import com.roxstar.voicedraft.Draft
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * REST API client for Room and Draft backend services using OkHttp.
 *
 * All requests are sent to the dynamically resolved [baseUrlProvider], ensuring
 * no hardcoded URLs exist in client logic.
 */
class RoomApiClient(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val baseUrlProvider: () -> String = { BackendConfig.baseUrl },
) {
    companion object {
        private const val HEADER_USER_ID = "x-user-id"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        private val HEX_OBJECT_ID_REGEX = Regex("^[0-9a-fA-F]{24}$")

        fun isValidObjectId(id: String): Boolean = HEX_OBJECT_ID_REGEX.matches(id.trim())
    }

    private val baseUrl: String
        get() = baseUrlProvider().trimEnd('/')

    /**
     * Shares an existing backend [draftId] into the specified [roomId].
     * Endpoint: `POST /rooms/{roomId}/drafts`
     * Header: `x-user-id: {userId}`
     * Body: `{"draftId": "{draftId}"}`
     */
    suspend fun shareDraft(
        roomId: String,
        draftId: String,
        userId: String,
    ): Result<SharedDraftDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms/${roomId.trim()}/drafts"
        val bodyJson = ShareDraftRequest(draftId = draftId.trim()).toJson().toString()
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            SharedDraftDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Registers a draft's metadata with the backend repository.
     * Endpoint: `POST /drafts`
     * Header: `x-user-id: {userId}`
     */
    suspend fun createDraft(
        name: String,
        durationMs: Long,
        fileLocation: String,
        effect: String = "NONE",
        userId: String,
    ): Result<DraftDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/drafts"
        val bodyJson = CreateDraftRequest(
            name = name,
            durationMs = durationMs,
            fileLocation = fileLocation,
            effect = effect,
        ).toJson().toString()

        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            DraftDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Bridges local [Draft] models with backend rooms:
     * 1. If [draft.id] is already a 24-char hex ObjectId, shares directly.
     * 2. Otherwise, first creates the draft on the backend via `POST /drafts` to obtain
     *    a valid backend ObjectId, then shares it with the room via `POST /rooms/{roomId}/drafts`.
     */
    suspend fun createAndShareDraft(
        roomId: String,
        draft: Draft,
        userId: String,
    ): Result<SharedDraftDto> {
        val backendDraftId = if (isValidObjectId(draft.id)) {
            draft.id
        } else {
            val createResult = createDraft(
                name = draft.name,
                durationMs = draft.durationMs,
                fileLocation = draft.filePath,
                effect = draft.effect.name,
                userId = userId,
            )
            val created = createResult.getOrElse { return Result.failure(it) }
            created.id
        }

        return shareDraft(
            roomId = roomId,
            draftId = backendDraftId,
            userId = userId,
        )
    }

    /**
     * Creates a new room where the calling user becomes the owner.
     * Endpoint: `POST /rooms`
     */
    suspend fun createRoom(userId: String): Result<RoomStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            RoomStateDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Joins an existing room idempotently.
     * Endpoint: `POST /rooms/{roomId}/join`
     */
    suspend fun joinRoom(roomId: String, userId: String): Result<RoomStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms/${roomId.trim()}/join"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            RoomStateDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Leaves an existing room and marks membership as LEFT.
     * Endpoint: `POST /rooms/{roomId}/leave`
     */
    suspend fun leaveRoom(roomId: String, userId: String): Result<RoomStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms/${roomId.trim()}/leave"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            RoomStateDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Retrieves the current room state.
     * Endpoint: `GET /rooms/{roomId}`
     */
    suspend fun getRoomState(roomId: String, userId: String): Result<RoomStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms/${roomId.trim()}"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .get()
            .build()

        executeRequest(request) { responseBody ->
            RoomStateDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Starts a new spin in [roomId] on behalf of room owner [userId].
     * Endpoint: `POST /rooms/{roomId}/spins`
     * Header: `x-user-id: {userId}`
     */
    suspend fun startSpin(roomId: String, userId: String): Result<SpinStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rooms/${roomId.trim()}/spins"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeRequest(request) { responseBody ->
            SpinStateDto.fromJson(JSONObject(responseBody))
        }
    }

    /**
     * Retrieves the state of an existing spin [spinId].
     * Endpoint: `GET /spins/{spinId}`
     * Header: `x-user-id: {userId}`
     */
    suspend fun getSpinState(spinId: String, userId: String): Result<SpinStateDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/spins/${spinId.trim()}"
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_USER_ID, userId.trim())
            .get()
            .build()

        executeRequest(request) { responseBody ->
            SpinStateDto.fromJson(JSONObject(responseBody))
        }
    }

    private fun <T> executeRequest(
        request: Request,
        parser: (String) -> T,
    ): Result<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parser(bodyString))
                } else {
                    val parsedError = ApiErrorDto.parse(bodyString)
                    val exception = if (parsedError != null) {
                        ApiException(
                            statusCode = response.code,
                            errorCode = parsedError.code,
                            message = parsedError.message,
                            errorDetails = parsedError.details,
                        )
                    } else {
                        ApiException(
                            statusCode = response.code,
                            errorCode = "HTTP_${response.code}",
                            message = "HTTP request failed with status ${response.code}: $bodyString",
                        )
                    }
                    Result.failure(exception)
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
