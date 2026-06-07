package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * tvOS ServerSyncView ("Setting Up") parity: a staged-loading card shown while
 * the playlist hydrates. Mirrors the four iOS stages (EPG / VOD / DVR /
 * preferences) with the same pending -> loading -> done iconography.
 *
 * The Android load is monolithic (PlaylistViewModel goes Bootstrapping ->
 * ChannelsReady with no per-stage signals), so the stages reveal sequentially
 * and the host navigates away the moment the real load reaches ChannelsReady.
 * A Skip control lets the user jump to the guide immediately.
 */
@Composable
fun SettingUpScreen(onSkip: () -> Unit) {
    val stages = remember {
        listOf("Loading EPG", "Loading VOD", "Loading DVR", "Loading preferences")
    }
    // Index of the stage currently loading; earlier stages render as done.
    var active by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (active < stages.size) {
            delay(700L)
            active++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = "Setting Up",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(vertical = 8.dp),
            ) {
                stages.forEachIndexed { i, label ->
                    val status = when {
                        i < active -> StageStatus.Done
                        i == active -> StageStatus.Loading
                        else -> StageStatus.Pending
                    }
                    StageRow(
                        label = label,
                        status = status,
                        showSynced = i == stages.lastIndex && status == StageStatus.Done,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            TextButton(onClick = onSkip) {
                Text("Skip", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private enum class StageStatus { Pending, Loading, Done }

@Composable
private fun StageRow(label: String, status: StageStatus, showSynced: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StageStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                StageStatus.Done -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp),
                )
                StageStatus.Pending -> Icon(
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (status == StageStatus.Pending) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            if (showSynced) {
                Text(
                    text = "Synced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
