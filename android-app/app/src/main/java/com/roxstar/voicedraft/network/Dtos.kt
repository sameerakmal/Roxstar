package com.roxstar.voicedraft.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data transfer objects matching the backend REST API contracts.
 */

data class SharedDraftDto(
    val draftId: String,
    val name: String,
    val durationMs: Long,
    val effect: String,
    val fileLocation: String,
    val sharedByUserId: String,
    val sharedAt: String,
) {
    companion object {
        fun fromJson(json: JSONObject): SharedDraftDto = SharedDraftDto(
            draftId = json.getString("draftId"),
            name = json.getString("name"),
            durationMs = json.getLong("durationMs"),
            effect = json.optString("effect", "NONE"),
            fileLocation = json.getString("fileLocation"),
            sharedByUserId = json.getString("sharedByUserId"),
            sharedAt = json.optString("sharedAt", ""),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("draftId", draftId)
        put("name", name)
        put("durationMs", durationMs)
        put("effect", effect)
        put("fileLocation", fileLocation)
        put("sharedByUserId", sharedByUserId)
        put("sharedAt", sharedAt)
    }
}

data class DraftDto(
    val id: String,
    val ownerUserId: String,
    val name: String,
    val durationMs: Long,
    val effect: String,
    val fileLocation: String,
    val createdAt: String,
) {
    companion object {
        fun fromJson(json: JSONObject): DraftDto = DraftDto(
            id = json.getString("id"),
            ownerUserId = json.getString("ownerUserId"),
            name = json.getString("name"),
            durationMs = json.getLong("durationMs"),
            effect = json.optString("effect", "NONE"),
            fileLocation = json.getString("fileLocation"),
            createdAt = json.optString("createdAt", ""),
        )
    }
}

data class ShareDraftRequest(
    val draftId: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("draftId", draftId)
    }
}

data class CreateDraftRequest(
    val name: String,
    val durationMs: Long,
    val fileLocation: String,
    val effect: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("durationMs", durationMs)
        put("fileLocation", fileLocation)
        if (effect != null) {
            put("effect", effect)
        }
    }
}

data class RoomDto(
    val id: String,
    val status: String,
    val ownerUserId: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): RoomDto = RoomDto(
            id = json.getString("id"),
            status = json.getString("status"),
            ownerUserId = json.getString("ownerUserId"),
            createdAt = json.optString("createdAt").takeIf { it.isNotEmpty() },
            updatedAt = json.optString("updatedAt").takeIf { it.isNotEmpty() },
        )
    }
}

data class ParticipantDto(
    val userId: String,
    val displayName: String,
    val membershipState: String,
    val connectionState: String,
    val joinedAt: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): ParticipantDto = ParticipantDto(
            userId = json.getString("userId"),
            displayName = json.getString("displayName"),
            membershipState = json.getString("membershipState"),
            connectionState = json.getString("connectionState"),
            joinedAt = json.optString("joinedAt").takeIf { it.isNotEmpty() },
        )
    }
}

data class SpinPlayerDto(
    val userId: String,
    val displayName: String,
    val status: String,
    val eliminationOrder: Int? = null,
    val eliminationReason: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): SpinPlayerDto = SpinPlayerDto(
            userId = json.getString("userId"),
            displayName = json.getString("displayName"),
            status = json.getString("status"),
            eliminationOrder = if (json.has("eliminationOrder") && !json.isNull("eliminationOrder")) {
                json.getInt("eliminationOrder")
            } else {
                null
            },
            eliminationReason = json.optString("eliminationReason").takeIf { it.isNotEmpty() },
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("userId", userId)
        put("displayName", displayName)
        put("status", status)
        eliminationOrder?.let { put("eliminationOrder", it) }
        eliminationReason?.let { put("eliminationReason", it) }
    }
}

data class ActiveSpinDto(
    val spinId: String,
    val status: String,
    val startedAt: String? = null,
    val participants: List<SpinPlayerDto> = emptyList(),
    val remainingPlayers: List<SpinPlayerDto> = emptyList(),
    val winner: SpinPlayerDto? = null,
    val lastSequenceNumber: Long = 0L,
) {
    companion object {
        fun fromJson(json: JSONObject): ActiveSpinDto {
            val participantsArray = json.optJSONArray("participants") ?: JSONArray()
            val participants = ArrayList<SpinPlayerDto>(participantsArray.length())
            for (i in 0 until participantsArray.length()) {
                val item = participantsArray.optJSONObject(i)
                if (item != null) participants.add(SpinPlayerDto.fromJson(item))
            }

            val remainingArray = json.optJSONArray("remainingPlayers") ?: JSONArray()
            val remaining = ArrayList<SpinPlayerDto>(remainingArray.length())
            for (i in 0 until remainingArray.length()) {
                val item = remainingArray.optJSONObject(i)
                if (item != null) remaining.add(SpinPlayerDto.fromJson(item))
            }

            val winnerObj = json.optJSONObject("winner")
            val winner = if (winnerObj != null) SpinPlayerDto.fromJson(winnerObj) else null

            return ActiveSpinDto(
                spinId = json.getString("spinId"),
                status = json.getString("status"),
                startedAt = json.optString("startedAt").takeIf { it.isNotEmpty() },
                participants = participants,
                remainingPlayers = remaining,
                winner = winner,
                lastSequenceNumber = json.optLong("lastSequenceNumber", 0L),
            )
        }
    }
}

data class SpinStateDto(
    val spinId: String,
    val roomId: String,
    val status: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val startedByUserId: String? = null,
    val abortReason: String? = null,
    val participants: List<SpinPlayerDto> = emptyList(),
    val remainingPlayers: List<SpinPlayerDto> = emptyList(),
    val winner: SpinPlayerDto? = null,
    val lastSequenceNumber: Long = 0L,
) {
    companion object {
        fun fromJson(json: JSONObject): SpinStateDto {
            val participantsArray = json.optJSONArray("participants") ?: JSONArray()
            val participants = ArrayList<SpinPlayerDto>(participantsArray.length())
            for (i in 0 until participantsArray.length()) {
                val item = participantsArray.optJSONObject(i)
                if (item != null) participants.add(SpinPlayerDto.fromJson(item))
            }

            val remainingArray = json.optJSONArray("remainingPlayers") ?: JSONArray()
            val remaining = ArrayList<SpinPlayerDto>(remainingArray.length())
            for (i in 0 until remainingArray.length()) {
                val item = remainingArray.optJSONObject(i)
                if (item != null) remaining.add(SpinPlayerDto.fromJson(item))
            }

            val winnerObj = json.optJSONObject("winner")
            val winner = if (winnerObj != null) SpinPlayerDto.fromJson(winnerObj) else null

            return SpinStateDto(
                spinId = json.getString("spinId"),
                roomId = json.getString("roomId"),
                status = json.getString("status"),
                startedAt = json.optString("startedAt").takeIf { it.isNotEmpty() },
                completedAt = json.optString("completedAt").takeIf { it.isNotEmpty() },
                startedByUserId = json.optString("startedByUserId").takeIf { it.isNotEmpty() },
                abortReason = json.optString("abortReason").takeIf { it.isNotEmpty() },
                participants = participants,
                remainingPlayers = remaining,
                winner = winner,
                lastSequenceNumber = json.optLong("lastSequenceNumber", 0L),
            )
        }
    }
}

data class RoomStateDto(
    val room: RoomDto,
    val participants: List<ParticipantDto> = emptyList(),
    val sharedDrafts: List<SharedDraftDto> = emptyList(),
    val activeSpin: ActiveSpinDto? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): RoomStateDto {
            val roomJson = json.getJSONObject("room")
            val participantsArray = json.optJSONArray("participants") ?: JSONArray()
            val sharedDraftsArray = json.optJSONArray("sharedDrafts") ?: JSONArray()

            val participants = ArrayList<ParticipantDto>(participantsArray.length())
            for (i in 0 until participantsArray.length()) {
                participants.add(ParticipantDto.fromJson(participantsArray.getJSONObject(i)))
            }

            val sharedDrafts = ArrayList<SharedDraftDto>(sharedDraftsArray.length())
            for (i in 0 until sharedDraftsArray.length()) {
                sharedDrafts.add(SharedDraftDto.fromJson(sharedDraftsArray.getJSONObject(i)))
            }

            val activeSpinObj = json.optJSONObject("activeSpin")
            val activeSpin = if (activeSpinObj != null) ActiveSpinDto.fromJson(activeSpinObj) else null

            return RoomStateDto(
                room = RoomDto.fromJson(roomJson),
                participants = participants,
                sharedDrafts = sharedDrafts,
                activeSpin = activeSpin,
            )
        }
    }
}

data class UserDto(
    val id: String,
    val displayName: String,
    val createdAt: String,
) {
    companion object {
        fun fromJson(json: JSONObject): UserDto = UserDto(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            createdAt = json.optString("createdAt", ""),
        )
    }
}

data class ApiErrorDetail(
    val field: String? = null,
    val message: String,
)

data class ApiErrorDto(
    val code: String,
    val message: String,
    val details: List<ApiErrorDetail> = emptyList(),
) {
    companion object {
        fun parse(responseBody: String): ApiErrorDto? = try {
            val root = JSONObject(responseBody)
            val errObj = root.optJSONObject("error") ?: root
            val code = errObj.optString("code", "UNKNOWN_ERROR")
            val message = errObj.optString("message", "An unexpected error occurred")
            val detailsArray = errObj.optJSONArray("details")
            val details = ArrayList<ApiErrorDetail>()
            if (detailsArray != null) {
                for (i in 0 until detailsArray.length()) {
                    val item = detailsArray.optJSONObject(i)
                    if (item != null) {
                        details.add(
                            ApiErrorDetail(
                                field = item.optString("field").takeIf { it.isNotEmpty() },
                                message = item.optString("message", ""),
                            )
                        )
                    }
                }
            }
            ApiErrorDto(code = code, message = message, details = details)
        } catch (_: Exception) {
            null
        }
    }
}

class ApiException(
    val statusCode: Int,
    val errorCode: String,
    message: String,
    val errorDetails: List<ApiErrorDetail> = emptyList(),
) : Exception("[$statusCode $errorCode] $message")
