package com.roxstar.voicedraft.spin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roxstar.voicedraft.network.ParticipantDto
import com.roxstar.voicedraft.ui.components.RoxStarTopBar
import com.roxstar.voicedraft.ui.theme.RoxStarBackground

/**
 * Screen providing a dedicated view for the Spin elimination game.
 */
@Composable
fun SpinScreen(
    spinViewModel: SpinViewModel,
    roomId: String,
    currentUserId: String?,
    isRoomOwner: Boolean,
    participantCount: Int,
    roomParticipants: List<ParticipantDto> = emptyList(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by spinViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            spinViewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            spinViewModel.clearStatusMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RoxStarBackground),
    ) {
        RoxStarTopBar(
            title = "Spin Elimination Arena",
            subtitle = "Room: ${roomId.take(8)}...",
            onBack = onNavigateBack,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SpinView(
                    uiState = uiState,
                    isRoomOwner = isRoomOwner,
                    currentUserId = currentUserId,
                    participantCount = participantCount,
                    roomParticipants = roomParticipants,
                    onStartSpin = {
                        if (currentUserId != null) {
                            spinViewModel.startSpin(roomId, currentUserId)
                        }
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }
    }
}
