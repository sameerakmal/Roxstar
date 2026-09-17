package com.roxstar.voicedraft.network

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Socket.IO connection status for the real-time room communication channel.
 */
enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * Real-time room events received over Socket.IO from the backend.
 */
sealed interface RoomSocketEvent {
    data class RoomStateReceived(val state: RoomStateDto) : RoomSocketEvent
    data class UserJoined(
        val roomId: String,
        val userId: String,
        val displayName: String,
        val participants: List<ParticipantDto>,
    ) : RoomSocketEvent
    data class UserLeft(
        val roomId: String,
        val userId: String,
        val displayName: String,
        val reason: String,
        val participants: List<ParticipantDto>,
    ) : RoomSocketEvent
    data class DraftShared(
        val roomId: String,
        val draft: SharedDraftDto,
    ) : RoomSocketEvent
    data class Error(val message: String) : RoomSocketEvent
}

/**
 * Manages the Socket.IO lifecycle and room subscriptions for the RoxStar backend.
 *
 * Exposes connection state and real-time room events as Kotlin [StateFlow] and [SharedFlow].
 * Contains no UI or Compose logic.
 */
class RoomSocketClient(
    private val baseUrlProvider: () -> String = { BackendConfig.baseUrl },
) {
    companion object {
        const val EVENT_ROOM_STATE = "room_state"
        const val EVENT_USER_JOINED = "user_joined"
        const val EVENT_USER_LEFT = "user_left"
        const val EVENT_DRAFT_SHARED = "draft_shared"

        const val CLIENT_JOIN_ROOM = "join_room"
        const val CLIENT_LEAVE_ROOM = "leave_room"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<RoomSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RoomSocketEvent> = _events.asSharedFlow()

    /**
     * Connects to the backend Socket.IO server on behalf of [userId].
     * Passes the user identity in handshake auth and headers.
     */
    fun connect(userId: String) {
        disconnect()

        _connectionState.value = SocketConnectionState.CONNECTING

        val baseUrl = baseUrlProvider().trimEnd('/')
        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            reconnectionAttempts = 5
            reconnectionDelay = 1000L
            auth = mapOf("userId" to userId)
            extraHeaders = mapOf("x-user-id" to listOf(userId))
        }

        try {
            val s = IO.socket(URI.create(baseUrl), options)
            setupSocketListeners(s)
            s.connect()
            socket = s
        } catch (e: Exception) {
            _connectionState.value = SocketConnectionState.ERROR
            scope.launch {
                _events.emit(RoomSocketEvent.Error("Connection initialization failed: ${e.message}"))
            }
        }
    }

    /**
     * Disconnects the socket and tears down listeners cleanly.
     */
    fun disconnect() {
        socket?.let { s ->
            s.off()
            s.disconnect()
            s.close()
        }
        socket = null
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    /**
     * Subscribes the socket to [roomId].
     * Emits `join_room` with an acknowledgment callback.
     */
    fun joinRoom(
        roomId: String,
        onAck: ((success: Boolean, code: String?) -> Unit)? = null,
    ) {
        val s = socket
        if (s == null || !s.connected()) {
            onAck?.invoke(false, "NOT_CONNECTED")
            return
        }

        val payload = JSONObject().apply {
            put("roomId", roomId.trim())
        }

        val ackCallback = Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok", false) ?: false
            val code = response?.optString("code")?.takeIf { it.isNotEmpty() }
            onAck?.invoke(ok, code)
        }

        s.emit(CLIENT_JOIN_ROOM, payload, ackCallback)
    }

    /**
     * Leaves the socket room for [roomId].
     */
    fun leaveRoom(
        roomId: String,
        onAck: ((success: Boolean) -> Unit)? = null,
    ) {
        val s = socket
        if (s == null || !s.connected()) {
            onAck?.invoke(false)
            return
        }

        val payload = JSONObject().apply {
            put("roomId", roomId.trim())
        }

        val ackCallback = Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok", true) ?: true
            onAck?.invoke(ok)
        }

        s.emit(CLIENT_LEAVE_ROOM, payload, ackCallback)
    }

    private fun setupSocketListeners(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            _connectionState.value = SocketConnectionState.CONNECTED
        }

        s.on(Socket.EVENT_DISCONNECT) {
            _connectionState.value = SocketConnectionState.DISCONNECTED
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            _connectionState.value = SocketConnectionState.ERROR
            val errObj = args.firstOrNull()
            val msg = when (errObj) {
                is Throwable -> errObj.message ?: "Connection error"
                is JSONObject -> errObj.optString("message", errObj.toString())
                else -> errObj?.toString() ?: "Connection error"
            }
            scope.launch {
                _events.emit(RoomSocketEvent.Error(msg))
            }
        }

        s.on(EVENT_ROOM_STATE) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val state = RoomStateDto.fromJson(json)
                scope.launch { _events.emit(RoomSocketEvent.RoomStateReceived(state)) }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed room_state: ${e.message}")) }
            }
        }

        s.on(EVENT_USER_JOINED) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val userObj = json.getJSONObject("user")
                val userId = userObj.getString("userId")
                val displayName = userObj.getString("displayName")
                val participants = parseParticipants(json.optJSONArray("participants"))
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.UserJoined(
                            roomId = roomId,
                            userId = userId,
                            displayName = displayName,
                            participants = participants,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed user_joined: ${e.message}")) }
            }
        }

        s.on(EVENT_USER_LEFT) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val userObj = json.getJSONObject("user")
                val userId = userObj.getString("userId")
                val displayName = userObj.getString("displayName")
                val reason = json.optString("reason", "LEFT")
                val participants = parseParticipants(json.optJSONArray("participants"))
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.UserLeft(
                            roomId = roomId,
                            userId = userId,
                            displayName = displayName,
                            reason = reason,
                            participants = participants,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed user_left: ${e.message}")) }
            }
        }

        s.on(EVENT_DRAFT_SHARED) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val draftObj = json.getJSONObject("draft")
                val draft = SharedDraftDto.fromJson(draftObj)
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.DraftShared(
                            roomId = roomId,
                            draft = draft,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed draft_shared: ${e.message}")) }
            }
        }
    }

    private fun parseParticipants(array: JSONArray?): List<ParticipantDto> {
        if (array == null) return emptyList()
        val list = ArrayList<ParticipantDto>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj != null) {
                list.add(ParticipantDto.fromJson(obj))
            }
        }
        return list
    }
}
