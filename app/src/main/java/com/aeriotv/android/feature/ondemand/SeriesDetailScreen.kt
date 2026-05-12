package com.aeriotv.android.feature.ondemand

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Series detail screen. Mirrors iOS VODDetailView (Aerio/Features/VOD/VODDetailView.swift)
 * for series: hero with backdrop + bottom-leading poster + title block, no Play
 * button (the user picks an episode instead), Trailer + TMDB chip row, meta
 * rows, then per-season episode list with thumbnail / duration / air-date /
 * rating / plot per row — matching TVEpisodeRowButton on iOS (line 827-979).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onBack: () -> Unit,
    onEpisodeClick: (DispatcharrVODEpisode) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val series = viewModel.seriesById(seriesId)
    val info = state.seriesProviderInfo[seriesId]
    val recent by watchVm.observeRecent(50).collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current

    LaunchedEffect(seriesId) {
        viewModel.loadEpisodes(seriesId)
        viewModel.loadSeriesProviderInfo(seriesId)
    }
    BackHandler(enabled = true) { onBack() }

    val episodes = state.episodesBySeries[seriesId].orEmpty()
    val isLoading = seriesId in state.episodesLoadingFor
    val error = state.episodesErrorFor[seriesId]

    // Season picker state. iOS defaults to the first season; honour the
    // sorted-keys order so "Specials" (season 0) never accidentally lands as
    // the default when seasons 1+ exist.
    val seasons = remember(episodes) {
        episodes
            .groupBy { it.seasonNumber ?: 0 }
            .toSortedMap()
    }
    var selectedSeason by remember(seasons.keys) {
        mutableStateOf(seasons.keys.firstOrNull { it != 0 } ?: seasons.keys.firstOrNull() ?: 0)
    }

    val episodesInSeason = remember(seasons, selectedSeason) {
        seasons[selectedSeason]
            ?.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    // Continue Watching pick: most-recently-updated episode that (a) belongs
    // to this series and (b) isn't within 5 min of the end. Same heuristic
    // iOS VODDetailView applies (architecture spec section D).
    val continueWatchingEpisode = remember(recent, episodes) {
        val episodeIds = episodes.asSequence().map { it.uuid }.toSet()
        val pick = recent.firstOrNull { row ->
            row.videoId in episodeIds &&
                row.positionMs > 0L &&
                (row.durationMs <= 0L || row.positionMs < row.durationMs - 5 * 60 * 1000L)
        }
        pick?.let { row -> episodes.firstOrNull { it.uuid == row.videoId }?.to(row) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (series == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Series not found",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    SeriesHeroSection(series = series, info = info)
                }
                item {
                    SeriesInfoSection(
                        series = series,
                        info = info,
                        onOpenUrl = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }

                continueWatchingEpisode?.let { (episode, progress) ->
                    item(key = "continue-watching") {
                        ContinueWatchingCard(
                            episode = episode,
                            positionMs = progress.positionMs,
                            durationMs = progress.durationMs,
                            onResume = { onEpisodeClick(episode) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (seasons.size > 1) {
                    item(key = "season-picker") {
                        SeasonPicker(
                            seasons = seasons.keys.toList(),
                            selected = selectedSeason,
                            onSelect = { selectedSeason = it },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (isLoading && episodes.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (error != null && episodes.isEmpty()) {
                    item {
                        Text(
                            text = "Couldn't load episodes: $error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                } else if (episodes.isEmpty()) {
                    item {
                        Text(
                            text = "Server returned no episodes for this series.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = episodesInSeason, key = { it.id }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { onEpisodeClick(ep) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        FloatingBackButton(onClick = onBack)
    }
}

@Composable
private fun SeriesHeroSection(series: DispatcharrVODSeries, info: DispatcharrVODProviderInfo?) {
    val heroUrl = info?.backdropUrl ?: series.posterUrl
    val posterUrl = series.posterUrl ?: info?.posterUrl
    val displayYear = info?.year?.toString() ?: series.year?.toString()
    val displayRating = (info?.rating?.takeIf { it.isNotBlank() } ?: series.rating)
        ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
        ?.takeIf { it.isNotBlank() && it != "0.0" }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 11f),
    ) {
        if (!heroUrl.isNullOrBlank()) {
            AsyncImage(
                model = heroUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = series.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                // iOS VODDetailView line 312 sets the title to `item.name`
                // without appending the year; the year already shows on the
                // meta strip below. Dispatcharr often serves titles that
                // already embed "(YYYY)" so appending duplicates it.
                Text(
                    text = series.displayName.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                MetaStripCompact(year = displayYear, rating = displayRating)
            }
        }
    }
}

@Composable
private fun MetaStripCompact(year: String?, rating: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!year.isNullOrBlank()) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!rating.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFA502),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeriesInfoSection(
    series: DispatcharrVODSeries,
    info: DispatcharrVODProviderInfo?,
    onOpenUrl: (String) -> Unit,
) {
    val plot = info?.effectivePlot?.takeIf { it.isNotBlank() } ?: series.plot?.takeIf { it.isNotBlank() }
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: series.genre?.takeIf { it.isNotBlank() }
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() }
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() }
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: series.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/tv/$it"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!plot.isNullOrBlank()) {
            Text(
                text = plot,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailerUrl != null || tmdbUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (trailerUrl != null) {
                    PillButton(icon = Icons.Outlined.PlayCircle, text = "Trailer") { onOpenUrl(trailerUrl) }
                }
                if (tmdbUrl != null) {
                    PillButton(icon = Icons.Outlined.Info, text = "View on TMDB") { onOpenUrl(tmdbUrl) }
                }
            }
        }
        if (!genre.isNullOrBlank()) MetaRow("Genre", genre)
        if (!cast.isNullOrBlank()) MetaRow("Cast", cast)
        if (!director.isNullOrBlank()) MetaRow("Director", director)
        if (!country.isNullOrBlank()) MetaRow("Country", country)
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SeasonPicker(seasons: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = seasons) { season ->
            val isSelected = season == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    )
                    .clickable { onSelect(season) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Episode row — 16:9 still thumbnail on the left, title with episode number,
 * duration · air date · rating metadata strip, plot, and a play icon on the
 * right. Mirrors iOS TVEpisodeRowButton (VODDetailView lines 842-957).
 */
@Composable
private fun EpisodeRow(
    episode: DispatcharrVODEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            val still = episode.stillImageUrl
            if (!still.isNullOrBlank()) {
                AsyncImage(
                    model = still,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            val titleLine = buildString {
                episode.episodeNumber?.let { append("E$it · ") }
                append(episode.displayName.ifBlank { "Episode ${episode.id}" })
            }
            Text(
                text = titleLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val pieces = listOfNotNull(
                episode.durationSecs?.takeIf { it > 0 }?.let { formatEpisodeDuration(it) },
                episode.airDate?.let { formatAirDate(it) }?.takeIf { it.isNotBlank() },
                episode.rating?.takeIf { it.isNotBlank() && it != "0.0" }
                    ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
                    ?.let { "★ $it" },
            )
            if (pieces.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pieces.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val plot = episode.effectivePlot?.takeIf { it.isNotBlank() }
            if (plot != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier
                .size(28.dp)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    episode: DispatcharrVODEpisode,
    positionMs: Long,
    durationMs: Long,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingMin = if (durationMs > positionMs) {
        ((durationMs - positionMs) / 60_000L).toInt().coerceAtLeast(1)
    } else 0
    val seasonEpisodeTag = listOfNotNull(
        episode.seasonNumber?.let { "S$it" },
        episode.episodeNumber?.let { "E$it" },
    ).joinToString("·")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                if (seasonEpisodeTag.isNotBlank()) append(seasonEpisodeTag).append("  ·  ")
                append(episode.displayName.ifBlank { "Episode ${episode.id}" })
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Resume")
            }
            if (remainingMin > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "$remainingMin min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FloatingBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 8.dp, start = 8.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatEpisodeDuration(secs: Int): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * Dispatcharr emits air_date as `yyyy-MM-dd` (POSIX, UTC). Format to the
 * user's locale short style — iOS does the same conversion in
 * VODEpisode.displayAirDate (VODModels.swift lines 469-484).
 */
private fun formatAirDate(raw: String): String {
    val trimmed = raw.takeIf { it.length >= 10 } ?: return raw
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(trimmed.substring(0, 10)) ?: return raw
        DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }.getOrDefault(raw)
}
