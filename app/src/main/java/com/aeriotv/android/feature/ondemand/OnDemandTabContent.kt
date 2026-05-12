package com.aeriotv.android.feature.ondemand

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODMovie

/**
 * On Demand tab. Mirrors iOS MoviesView (Aerio/Features/VOD/MoviesView.swift):
 * search bar + poster grid. Phase 10a Movies-first-page only; Series + the
 * "Continue Watching" rail land in Phase 10b/10c.
 *
 * Playback wiring (tap a poster -> VOD player route) is Phase 10b. Phase 10a
 * surfaces an informational Toast so the grid is verifiable end-to-end
 * without needing the player wiring first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDemandTabContent(
    modifier: Modifier = Modifier,
    viewModel: OnDemandViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val count = state.totalCount.takeIf { it > 0 }
                val titleText = if (count != null)
                    "On Demand  •  ${state.movies.size} / $count"
                else
                    "On Demand"
                Text(titleText, style = MaterialTheme.typography.titleMedium)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (state.unsupportedSource) {
            EmptyState(
                title = "On Demand needs Dispatcharr",
                body = "Switch to a Dispatcharr playlist in Settings to browse movies.",
            )
            return@Column
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search movies") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
        )

        if (state.isLoading && state.movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Column
        }
        state.error?.let { err ->
            if (state.movies.isEmpty()) {
                Text(
                    text = "Couldn't load movies: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }
        }
        if (state.visible.isEmpty()) {
            EmptyState(
                title = if (state.searchQuery.isNotBlank()) "No matches" else "No movies",
                body = if (state.searchQuery.isNotBlank())
                    "Try a different search term."
                else
                    "Dispatcharr returned an empty Movies library. Confirm VOD is enabled on the server.",
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.visible, key = { it.id }) { movie ->
                MoviePoster(
                    movie = movie,
                    onClick = {
                        Toast.makeText(
                            context,
                            "VOD playback lands with Phase 10b: ${movie.displayName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}

@Composable
private fun MoviePoster(
    movie: DispatcharrVODMovie,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f) // standard movie-poster aspect
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            val poster = movie.posterUrl
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = movie.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = movie.displayName.ifBlank { "Untitled" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (movie.year != null || !movie.rating.isNullOrBlank()) {
            val meta = listOfNotNull(
                movie.year?.toString(),
                movie.rating?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
