package com.roxstar.voicedraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roxstar.voicedraft.room.RoomScreen
import com.roxstar.voicedraft.room.RoomSession
import com.roxstar.voicedraft.room.RoomViewModel
import com.roxstar.voicedraft.spin.SpinScreen
import com.roxstar.voicedraft.ui.components.RoxStarBottomNavigation
import com.roxstar.voicedraft.ui.components.RoxStarTab
import com.roxstar.voicedraft.ui.theme.VoiceDraftTheme

/**
 * Single-activity host with modern RoxStar bottom navigation.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomSession.init(this)
        setContent {
            VoiceDraftTheme {
                MainAppHost()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the shared native engine when the process is being torn down.
        AudioEngineHolder.release()
    }
}

private enum class AppView {
    MAIN_TABS,
    DEDICATED_SPIN,
}

@Composable
private fun MainAppHost(
    roomViewModel: RoomViewModel = viewModel(),
    draftViewModel: DraftViewModel = viewModel(),
) {
    var currentTab by remember { mutableStateOf(RoxStarTab.DASHBOARD) }
    var currentView by remember { mutableStateOf(AppView.MAIN_TABS) }

    val roomUiState by roomViewModel.uiState.collectAsState()
    val spinUiState by roomViewModel.spinViewModel.uiState.collectAsState()
    val draftUiState by draftViewModel.uiState.collectAsState()
    val recentRooms by RoomSession.recentRooms.collectAsState()

    when (currentView) {
        AppView.DEDICATED_SPIN -> {
            SpinScreen(
                spinViewModel = roomViewModel.spinViewModel,
                roomId = roomUiState.currentRoomId.orEmpty(),
                currentUserId = roomUiState.currentUserId,
                isRoomOwner = roomUiState.roomInfo?.ownerUserId == roomUiState.currentUserId,
                participantCount = roomUiState.participants.size,
                roomParticipants = roomUiState.participants,
                onNavigateBack = { currentView = AppView.MAIN_TABS },
            )
        }

        AppView.MAIN_TABS -> {
            Scaffold(
                bottomBar = {
                    RoxStarBottomNavigation(
                        currentTab = currentTab,
                        onTabSelected = { selected ->
                            currentTab = selected
                        },
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    when (currentTab) {
                        RoxStarTab.DASHBOARD -> {
                            DashboardScreen(
                                onNavigateToStudio = { currentTab = RoxStarTab.STUDIO },
                                onNavigateToDrafts = { currentTab = RoxStarTab.DRAFTS },
                                onNavigateToRoom = { currentTab = RoxStarTab.ROOM },
                                onNavigateToSpin = {
                                    if (roomUiState.isInRoom) {
                                        currentView = AppView.DEDICATED_SPIN
                                    } else {
                                        currentTab = RoxStarTab.ROOM
                                    }
                                },
                                activeRoomId = roomUiState.currentRoomId,
                                activeUserName = RoomSession.activeUserName ?: RoomSession.savedUserName.value,
                                recentRooms = recentRooms,
                                recentDrafts = draftUiState.drafts.map { it.draft },
                                playingDraftId = draftUiState.playingDraftId,
                                onPlayDraft = { draftViewModel.play(it) },
                                onStopDraft = { draftViewModel.stop() },
                                spinStatus = spinUiState.status,
                                participantCount = roomUiState.participants.size,
                            )
                        }

                        RoxStarTab.STUDIO -> {
                            RecordingScreen(
                                onNavigateToDrafts = { currentTab = RoxStarTab.DRAFTS },
                                onNavigateToRoom = { currentTab = RoxStarTab.ROOM },
                            )
                        }

                        RoxStarTab.DRAFTS -> {
                            DraftListScreen(
                                viewModel = draftViewModel,
                                onNavigateBack = { currentTab = RoxStarTab.DASHBOARD },
                                onNavigateToRoom = { currentTab = RoxStarTab.ROOM },
                                onNavigateToStudio = { currentTab = RoxStarTab.STUDIO },
                            )
                        }

                        RoxStarTab.ROOM -> {
                            RoomScreen(
                                viewModel = roomViewModel,
                                onNavigateBack = { currentTab = RoxStarTab.DASHBOARD },
                            )
                        }
                    }
                }
            }
        }
    }
}
