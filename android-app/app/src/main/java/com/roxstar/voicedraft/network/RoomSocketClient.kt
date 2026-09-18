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
    data class SpinStarted(
        val roomId: String,
        val spinId: String,
        val status: String,
        val startedAt: String?,
        val eligiblePlayers: List<SpinPlayerDto>,
        val remainingPlayers: List<SpinPlayerDto>,
        val sequenceNumber: Long,
    ) : RoomSocketEvent
    data class UserEliminated(
        val roomId: String,
        val spinId: String,
        val eliminatedUser: SpinPlayerDto,
        val eliminationOrder: Int,
        val remainingPlayers: List<SpinPlayerDto>,
        val sequenceNumber: Long,
    ) : RoomSocketEvent
    data class WinnerAnnounced(
        val roomId: String,
        val spinId: String,
        val status: String,
        val winner: SpinPlayerDto,
        val completedAt: String?,
        val sequenceNumber: Long,
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
        const val EVENT_SPIN_STARTED = "spin_started"
        const val EVENT_USER_ELIMINATED = "user_eliminated"
        const val EVENT_WINNER_ANNOUNCED = "winner_announced"

        const val CLIENT_JOIN_ROOM = "join_room"
        const val CLIENT_LEAVE_ROOM = "leave_room"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var socket: Socket? = null
    @Volatile
    private var currentUserId: String? = null
    @Volatile
    private var activeRoomId: String? = null

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<RoomSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RoomSocketEvent> = _events.asSharedFlow()

    /**
     * Connects to the backend Socket.IO server on behalf of [userId].
     * Passes the user identity in handshake auth and headers.
     */
    fun connect(userId: String) {
        currentUserId = userId
        disconnect()

        _connectionState.value = SocketConnectionState.CONNECTING

        val baseUrl = baseUrlProvider().trimEnd('/')
        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            reconnectionAttempts = 10
            reconnectionDelay = 1000L
            reconnectionDelayMax = 5000L
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
     * Attempts a fresh reconnect using the last stored user identity.
     */
    fun reconnect() {
        val uid = currentUserId ?: return
        connect(uid)
    }

    /**
     * Disconnects the socket and tears down listeners cleanly.
     */
    fun disconnect() {
        activeRoomId = null
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
     * If the socket is currently connecting, remembers the room and emits automatically upon connection.
     */
    fun joinRoom(
        roomId: String,
        onAck: ((success: Boolean, code: String?) -> Unit)? = null,
    ) {
        val trimmed = roomId.trim()
        if (trimmed.isEmpty()) return

        activeRoomId = trimmed
        val s = socket
        if (s != null && s.connected()) {
            emitJoinRoom(s, trimmed, onAck)
        } else {
            // Socket still connecting: will automatically be sent in setupSocketListeners once connected
            onAck?.invoke(true, null)
        }
    }

    private fun emitJoinRoom(
        s: Socket,
        roomId: String,
        onAck: ((success: Boolean, code: String?) -> Unit)? = null,
    ) {
        val payload = JSONObject().apply {
            put("roomId", roomId)
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
        activeRoomId = null
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
            // Auto-join pending active room if present
            activeRoomId?.let { rId ->
                emitJoinRoom(s, rId)
            }
        }

        s.on(Socket.EVENT_DISCONNECT) {
            _connectionState.value = SocketConnectionState.DISCONNECTED
        }

        s.on("reconnect") {
            _connectionState.value = SocketConnectionState.CONNECTED
            activeRoomId?.let { rId ->
                emitJoinRoom(s, rId)
            }
        }

        s.on("reconnecting") {
            _connectionState.value = SocketConnectionState.CONNECTING
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

        s.on(EVENT_SPIN_STARTED) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val spinId = json.getString("spinId")
                val status = json.optString("status", "RUNNING")
                val startedAt = json.optString("startedAt").takeIf { it.isNotEmpty() }
                val eligiblePlayers = parseSpinPlayers(json.optJSONArray("eligiblePlayers"))
                val remainingPlayers = parseSpinPlayers(json.optJSONArray("remainingPlayers"))
                val sequenceNumber = json.optLong("sequenceNumber", 0L)
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.SpinStarted(
                            roomId = roomId,
                            spinId = spinId,
                            status = status,
                            startedAt = startedAt,
                            eligiblePlayers = eligiblePlayers,
                            remainingPlayers = remainingPlayers,
                            sequenceNumber = sequenceNumber,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed spin_started: ${e.message}")) }
            }
        }

        s.on(EVENT_USER_ELIMINATED) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val spinId = json.getString("spinId")
                val eliminatedUser = SpinPlayerDto.fromJson(json.getJSONObject("eliminatedUser"))
                val eliminationOrder = json.getInt("eliminationOrder")
                val remainingPlayers = parseSpinPlayers(json.optJSONArray("remainingPlayers"))
                val sequenceNumber = json.optLong("sequenceNumber", 0L)
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.UserEliminated(
                            roomId = roomId,
                            spinId = spinId,
                            eliminatedUser = eliminatedUser,
                            eliminationOrder = eliminationOrder,
                            remainingPlayers = remainingPlayers,
                            sequenceNumber = sequenceNumber,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed user_eliminated: ${e.message}")) }
            }
        }

        s.on(EVENT_WINNER_ANNOUNCED) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                val roomId = json.getString("roomId")
                val spinId = json.getString("spinId")
                val status = json.optString("status", "COMPLETED")
                val winner = SpinPlayerDto.fromJson(json.getJSONObject("winner"))
                val completedAt = json.optString("completedAt").takeIf { it.isNotEmpty() }
                val sequenceNumber = json.optLong("sequenceNumber", 0L)
                scope.launch {
                    _events.emit(
                        RoomSocketEvent.WinnerAnnounced(
                            roomId = roomId,
                            spinId = spinId,
                            status = status,
                            winner = winner,
                            completedAt = completedAt,
                            sequenceNumber = sequenceNumber,
                        )
                    )
                }
            } catch (e: Exception) {
                scope.launch { _events.emit(RoomSocketEvent.Error("Malformed winner_announced: ${e.message}")) }
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

    private fun parseSpinPlayers(array: JSONArray?): List<SpinPlayerDto> {
        if (array == null) return emptyList()
        val list = ArrayList<SpinPlayerDto>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj != null) {
                list.add(SpinPlayerDto.fromJson(obj))
            }
        }
        return list
    }
}
