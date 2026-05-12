package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.Menu
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * Multi-playlist switcher reachable from Settings root. Lists every saved
 * playlist with an Active checkmark on the current one; tap to switch, long-
 * press for delete confirm. Plus button in the top bar routes to the same
 * Choose-Source-Type onboarding screen used for first-run setup, except after
 * the new playlist persists the user pops back here instead of being thrown
 * into the player.
 *
 * Mirrors iOS Playlists screen (project_aeriotv_ios_canon.md "Settings" >
 * "Playlists section" implicit in canon since the iOS test-server screenshots
 * show only a single playlist; multi-playlist UX is taken from iOS source).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenPlaylistDetail: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val playlists: List<PlaylistEntity> by viewModel.allPlaylists
        .collectAsStateWithLifecycle(initialValue = emptyList<PlaylistEntity>())
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeId = state.playlist?.id

    var pendingDelete by remember { mutableStateOf<PlaylistEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Playlists", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                IconButton(onClick = onAddPlaylist) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add playlist",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No saved playlists. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Local working copy so the drag preview updates fluidly without
        // hitting Room on every onMove frame. We commit the order to the
        // repository on drag end (applyPlaylistOrder).
        var workingOrder by remember(playlists) { mutableStateOf(playlists) }
        androidx.compose.runtime.LaunchedEffect(playlists) {
            workingOrder = playlists
        }
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            workingOrder = workingOrder.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.adaptiveFormWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(workingOrder, key = { it.id }) { pl ->
                ReorderableItem(reorderState, key = pl.id) { isDragging ->
                    SwipeablePlaylistRow(
                        playlist = pl,
                        isActive = pl.id == activeId,
                        isDragging = isDragging,
                        onTap = {
                            if (pl.id == activeId) {
                                onOpenPlaylistDetail()
                            } else {
                                viewModel.switchToPlaylist(pl.id)
                            }
                        },
                        onLongPress = { pendingDelete = pl },
                        onSwipedToDelete = { pendingDelete = pl },
                        dragHandle = {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .draggableHandle(
                                        onDragStopped = {
                                            viewModel.applyPlaylistOrder(workingOrder.map { it.id })
                                        },
                                    )
                                    .size(24.dp),
                            )
                        },
                    )
                }
            }
        }
        }
    }

    pendingDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "This removes \"${pl.name}\" and its credentials from this device. " +
                        if (pl.id == activeId) "The next playlist on the list will be activated." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = pl.id
                    pendingDelete = null
                    viewModel.deletePlaylist(id)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    dragHandle: @Composable (() -> Unit)? = null,
    isDragging: Boolean = false,
) {
    val elevation = if (isDragging) 6.dp else 0.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            dragHandle()
            Spacer(Modifier.size(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.channelCount} channels • ${playlist.sourceType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = "▸",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Wraps a [PlaylistRow] in Material 3's SwipeToDismissBox so the user can
 * swipe either direction to delete (mirrors iOS SettingsView swipe action).
 * The dismiss action surfaces the same confirmation dialog as long-press, so
 * a wayward swipe never silently destroys a playlist row + its credentials.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeablePlaylistRow(
    playlist: com.aeriotv.android.core.data.db.entity.PlaylistEntity,
    isActive: Boolean,
    isDragging: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipedToDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ||
                value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            ) {
                onSwipedToDelete()
                // Don't actually dismiss the row here — the confirmation dialog
                // owns the delete. Returning false snaps the row back to settled.
                false
            } else {
                true
            }
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.dismissDirection ==
                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
                ) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) {
        PlaylistRow(
            playlist = playlist,
            isActive = isActive,
            isDragging = isDragging,
            dragHandle = dragHandle,
            onTap = onTap,
            onLongPress = onLongPress,
        )
    }
}
