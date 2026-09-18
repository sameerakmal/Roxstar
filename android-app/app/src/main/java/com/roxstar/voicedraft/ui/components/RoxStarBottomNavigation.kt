package com.roxstar.voicedraft.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roxstar.voicedraft.ui.theme.RoxStarCard
import com.roxstar.voicedraft.ui.theme.RoxStarPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryContainer
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary

enum class RoxStarTab {
    DASHBOARD,
    STUDIO,
    DRAFTS,
    ROOM,
}

@Composable
fun RoxStarBottomNavigation(
    currentTab: RoxStarTab,
    onTabSelected: (RoxStarTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = RoxStarCard,
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                icon = "🏠",
                label = "Home",
                isSelected = currentTab == RoxStarTab.DASHBOARD,
                onClick = { onTabSelected(RoxStarTab.DASHBOARD) },
            )
            NavItem(
                customIcon = {
                    RoxStarMicIcon(
                        color = if (currentTab == RoxStarTab.STUDIO) RoxStarPrimary else RoxStarTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                label = "Studio",
                isSelected = currentTab == RoxStarTab.STUDIO,
                onClick = { onTabSelected(RoxStarTab.STUDIO) },
            )
            NavItem(
                icon = "📁",
                label = "Drafts",
                isSelected = currentTab == RoxStarTab.DRAFTS,
                onClick = { onTabSelected(RoxStarTab.DRAFTS) },
            )
            NavItem(
                icon = "👥",
                label = "Room & Spin",
                isSelected = currentTab == RoxStarTab.ROOM,
                onClick = { onTabSelected(RoxStarTab.ROOM) },
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: String = "",
    customIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textColor by animateColorAsState(
        targetValue = if (isSelected) RoxStarPrimary else RoxStarTextSecondary,
        label = "nav_text_color",
    )
    val pillBg by animateColorAsState(
        targetValue = if (isSelected) RoxStarPrimaryContainer else Color.Transparent,
        label = "nav_pill_bg",
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(pillBg)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (customIcon != null) {
                customIcon()
            } else {
                Text(text = icon, fontSize = 20.sp)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}
