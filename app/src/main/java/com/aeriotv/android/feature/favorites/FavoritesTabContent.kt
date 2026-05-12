package com.aeriotv.android.feature.favorites

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * Favorites tab. Mirrors iOS FavoritesView (Aerio/Features/LiveTV/ChannelListView.swift:2596):
 * dense channel list of starred entries. Long-press a row to un-favorite.
 *
 * Phase 13 ships this as a flat list. Manual drag-to-reorder + Edit mode
 * land alongside the iOS-equivalent affordance pass.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesTabContent(
    modifier: Modifier = Modifier,
    onChannelClick: (M3UChannel) -> Unit,
    favoritesVm: FavoritesViewModel = hiltViewModel(),
    playlistVm: PlaylistViewModel = hiltViewModel(),
) {
    val favorites by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistState by playlistVm.state.collectAsStateWithLifecycle()

    // Join favourites rows -> currently-loaded M3UChannel objects so each row
    // has a playable URL and the same logo / number metadata as the Live TV list.
    val channels = remember(favorites, playlistState.channels) {
        val byId = playlistState.channels.associateBy { it.id }
        favorites.mapNotNull { byId[it.channelId] }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Matches the Phase 50 / 56 tab-header style — centered, titleLarge,
        // bold — so every dynamic tab top reads as one consistent surface.
        // Channel count moves out of the title (iOS Live TV doesn't surface
        // it in the header either) so the bold "Favorites" lines up with
        // "Live TV", "My Recordings", "On Demand", and "Settings" visually.
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (channels.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "No Favorites",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Long-press a channel in Live TV and tap Add to Favorites.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = channels, key = { it.id }) { channel ->
                FavoriteRow(
                    channel = channel,
                    onTap = { onChannelClick(channel) },
                    onLongPress = { favoritesVm.toggle(channel) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    channel: M3UChannel,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            channel.channelNumber?.let { num ->
                Text(
                    text = num.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
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
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.groupTitle.isNotBlank()) {
                Text(
                    text = channel.groupTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
