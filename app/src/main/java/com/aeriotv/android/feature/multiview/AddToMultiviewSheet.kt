package com.aeriotv.android.feature.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel

/**
 * Bottom sheet to pick channels for the multiview tile grid. Mirrors iOS
 * AddToMultiviewSheet (project_aeriotv_ios_canon.md "+ Add to Multiview"):
 * header with "Done" + title, group filter chips, a "Recent" section, a search
 * field, and the full "All Channels" list. Each row shows logo / number / name
 * / now-playing metadata, with a cyan + when not selected and a green check
 * when already in the multiview set. Footer counter ("N / 9 max") lives in the
 * header.
 *
 * Phase 11a delivered the basic picker; this revision adds the group chips +
 * Recent + search + section headers for full iOS parity. Recents are fed by
 * RecentChannelsStore (AppPreferences.recentChannelIds), written from
 * PlayerScreen on each channel flip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToMultiviewSheet(
    onDismiss: () -> Unit,
    multiviewStore: MultiviewStoreHandle = rememberMultiviewStoreHandle(),
    playlistVm: PlaylistViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by playlistVm.state.collectAsStateWithLifecycle()
    val selected by multiviewStore.selected.collectAsState()
    val recentIds by settingsVm.recentChannelIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedIds = selected.map { it.id }.toSet()

    var selectedGroup by remember { mutableStateOf(PlaylistViewModel.ALL_GROUPS) }
    var query by remember { mutableStateOf("") }

    // Playable channels only, indexed for the recents join.
    val playable = remember(state.channels) { state.channels.filter { it.url.isNotBlank() } }
    val byId = remember(playable) { playable.associateBy { it.id } }

    // Group filter chips: "All" + each distinct groupTitle in source order.
    val groups = remember(playable) {
        listOf(PlaylistViewModel.ALL_GROUPS) +
            playable.asSequence().map { it.groupTitle }.filter { it.isNotBlank() }.distinct().toList()
    }

    // Recent rows: resolve LRU ids against the current playlist, capped to a
    // short list. Hidden while searching or when a specific group is selected
    // (matches iOS, where Recent is a top-of-list convenience for the "All"
    // view only).
    val recentChannels = remember(recentIds, byId) {
        recentIds.mapNotNull { byId[it] }.take(8)
    }
    val showRecent = query.isBlank() && selectedGroup == PlaylistViewModel.ALL_GROUPS && recentChannels.isNotEmpty()

    val filtered = remember(playable, selectedGroup, query) {
        val q = query.trim()
        playable.filter { ch ->
            (selectedGroup == PlaylistViewModel.ALL_GROUPS || ch.groupTitle.equals(selectedGroup, ignoreCase = true)) &&
                (q.isEmpty() || ch.name.contains(q, ignoreCase = true))
        }
    }

    // User report (v0.1.6, Amlogic S905X4): the channel-picker "seems to
    // instruct me to drag down, but I am not on a touchscreen." The default
    // ModalBottomSheet renders a drag handle (a grab bar that reads as
    // "drag") which is meaningless on a remote. On TV drop the handle and
    // rely on the "Done" button + BACK to dismiss; BackHandler guarantees the
    // remote BACK button closes the picker.
    val isTvDevice = (
        androidx.compose.ui.platform.LocalConfiguration.current.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION
    BackHandler { onDismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = if (isTvDevice) null else { { BottomSheetDefaults.DragHandle() } },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Add to Multiview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${selected.size} / ${multiviewStore.maxTiles} max",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }

            // Group filter chips (skip when the playlist has no real groups).
            if (groups.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = groups, key = { it }) { group ->
                        FilterChip(
                            selected = group == selectedGroup,
                            onClick = { selectedGroup = group },
                            label = { Text(group, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            // Search field.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search channels") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp), // explicit max — sheet expansion handles overflow scroll
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showRecent) {
                    item(key = "hdr_recent") { SectionHeader("Recent") }
                    items(items = recentChannels, key = { "recent_${it.id}" }) { channel ->
                        val isSel = channel.id in selectedIds
                        val now = state.epgByChannel[channel.guideMatchKey]?.nowPlaying()
                        ChannelPickerRow(
                            channel = channel,
                            nowTitle = now?.title.orEmpty(),
                            selected = isSel,
                            atCap = !isSel && selected.size >= multiviewStore.maxTiles,
                            onToggle = { multiviewStore.toggle(channel) },
                        )
                    }
                    item(key = "hdr_all") { SectionHeader("All Channels") }
                }

                items(items = filtered, key = { "all_${it.id}" }) { channel ->
                    val isSel = channel.id in selectedIds
                    val now = state.epgByChannel[channel.guideMatchKey]?.nowPlaying()
                    ChannelPickerRow(
                        channel = channel,
                        nowTitle = now?.title.orEmpty(),
                        selected = isSel,
                        atCap = !isSel && selected.size >= multiviewStore.maxTiles,
                        onToggle = { multiviewStore.toggle(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ChannelPickerRow(
    channel: M3UChannel,
    nowTitle: String,
    selected: Boolean,
    atCap: Boolean,
    onToggle: () -> Unit,
) {
    val baseColor = if (selected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(baseColor)
            .clickable(enabled = !atCap, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.channelNumber ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowTitle.isNotBlank()) {
                Text(
                    text = nowTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = if (atCap)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
