package com.aeriotv.android.feature.main

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iOS/tvOS "Syncing | Tap for Info" background-activity pill
 * (HomeView.swift MainTabView, BackgroundWorkDetailsView).
 *
 * IMPORTANT: this is an ACTIVITY light, NOT a cross-device-sync status. iOS
 * drives the identical badge purely off content-fetch flags (channel list, EPG
 * parse, On Demand movies/series); it has nothing to do with iCloud/Drive. Its
 * job is to tell the user WHY the UI is still churning during a big library load
 * (a 700-category Xtream walk can run minutes), then vanish when work finishes.
 * There is no success/error/"last synced" state -- it simply reflects the flags.
 *
 * Placement: a top-start capsule (spinner + text) at the [MainScaffold] root,
 * so it floats over tab content for both the phone and TV shells. It is not
 * rendered while a stream is fullscreen because the fullscreen player covers
 * MainScaffold entirely (a different nav destination).
 *
 * Interaction: on phone the pill is tappable and opens [SyncDetailsDialog]
 * listing the active task labels + a live elapsed-seconds counter. On TV it is a
 * NON-focusable status indicator -- a focusable element that appears/disappears
 * as work starts/stops would yank D-pad focus around the screen -- so it shows
 * the same spinner + text without a tap target.
 */
@Composable
fun SyncActivityPill(
    active: Boolean,
    labels: List<String>,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    // When the current burst of work began, for the details popup's elapsed
    // counter (iOS bgWorkStartedAt). Reset whenever work flips off.
    var startedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (active && startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        if (!active) startedAt = 0L
    }
    var showDetails by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = active,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.5f))
                .then(
                    if (!isTv) Modifier.clickable { showDetails = true } else Modifier,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (isTv) 15.dp else 12.dp),
                strokeWidth = 2.dp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                text = if (isTv) "Syncing" else "Syncing | Tap for Info",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (isTv) 0.9f else 0.75f),
            )
        }
    }

    if (showDetails) {
        SyncDetailsDialog(
            labels = labels,
            startedAtRealtime = startedAt,
            onDismiss = { showDetails = false },
        )
    }
}

/**
 * iOS BackgroundWorkDetailsView parity: a small popup listing the currently
 * running background tasks + a live elapsed-seconds counter. Phone only (the
 * pill is non-interactive on TV).
 */
@Composable
private fun SyncDetailsDialog(
    labels: List<String>,
    startedAtRealtime: Long,
    onDismiss: () -> Unit,
) {
    val elapsed by produceState(initialValue = 0L, startedAtRealtime) {
        while (true) {
            value = if (startedAtRealtime == 0L) 0L
            else (SystemClock.elapsedRealtime() - startedAtRealtime) / 1000L
            delay(1000L)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text("Background Activity") },
        text = {
            Column {
                if (labels.isEmpty()) {
                    Text(
                        text = "Nothing running right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    labels.forEach { label ->
                        Text(
                            text = "•  $label",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Elapsed: ${elapsed}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
