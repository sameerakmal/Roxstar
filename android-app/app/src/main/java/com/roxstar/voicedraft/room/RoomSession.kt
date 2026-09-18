package com.roxstar.voicedraft.room

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global session tracker for active room identity and persistence.
 *
 * Persists the user's chosen display name and recent rooms list so they don't
 * need to re-enter their username or type 24-char hex room IDs every time.
 */
object RoomSession {
    private const val PREFS_NAME = "roxstar_room_session"
    private const val KEY_USER_NAME = "saved_user_name"
    private const val KEY_RECENT_ROOMS = "recent_rooms"

    private var prefs: SharedPreferences? = null

    var activeRoomId: String? = null
        internal set
    var activeUserId: String? = null
        internal set
    var activeUserName: String? = null
        internal set

    private val _savedUserName = MutableStateFlow("")
    val savedUserName: StateFlow<String> = _savedUserName.asStateFlow()

    private val _recentRooms = MutableStateFlow<List<String>>(emptyList())
    val recentRooms: StateFlow<List<String>> = _recentRooms.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val storedName = p.getString(KEY_USER_NAME, "") ?: ""
            _savedUserName.value = storedName

            val storedRecent = p.getString(KEY_RECENT_ROOMS, "") ?: ""
            if (storedRecent.isNotBlank()) {
                _recentRooms.value = storedRecent.split(",").filter { it.isNotBlank() }
            }
        }
    }

    fun saveUserName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            _savedUserName.value = trimmed
            prefs?.edit()?.putString(KEY_USER_NAME, trimmed)?.apply()
        }
    }

    fun recordJoinedRoom(roomId: String, userId: String, userName: String) {
        activeRoomId = roomId
        activeUserId = userId
        activeUserName = userName
        saveUserName(userName)

        val current = _recentRooms.value.toMutableList()
        current.remove(roomId)
        current.add(0, roomId)
        val capped = current.take(5)
        _recentRooms.value = capped
        prefs?.edit()?.putString(KEY_RECENT_ROOMS, capped.joinToString(","))?.apply()
    }

    fun clearActiveRoom() {
        activeRoomId = null
        activeUserId = null
        activeUserName = null
    }
}
