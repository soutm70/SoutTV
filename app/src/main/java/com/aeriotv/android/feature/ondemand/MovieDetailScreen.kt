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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel

/**
 * Movie detail screen. Mirrors iOS VODDetailView (Aerio/Features/VOD/VODDetailView.swift)
 * line 268-362 hero pattern + 365-418 info section:
 *
 *  - Hero ZStack: 16:9 backdrop image + bottom-gradient overlay so the title
 *    block stays readable over busy artwork. Small movie poster anchored to
 *    the bottom-leading corner with the title / meta strip / Play CTA next
 *    to it. Backdrop image comes from provider-info; falls back to the
 *    poster if provider-info hasn't finished loading.
 *  - Plot copy directly below.
 *  - Trailer + View on TMDB pill chip row.
 *  - Genre / Cast / Director / Country labeled rows.
 *
 * Provider-info (cast / director / country / trailer / backdrop) loads lazily
 * on first paint via [OnDemandViewModel.loadMovieProviderInfo]; the screen
 * renders whatever's available immediately so the user never sees an empty
 * shell while the upstream Xtream fetch (Dispatcharr line 1693-1701) runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieUuid: String,
    onBack: () -> Unit,
    onPlay: (DispatcharrVODMovie) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val movie = viewModel.movieByUuid(movieUuid)
    val context = LocalContext.current
    val recent by watchVm.observeRecent(50).collectAsStateWithLifecycle(initialValue = emptyList())
    val progress = recent.firstOrNull { it.videoId == movieUuid }
    val hasResume = progress != null && progress.positionMs > 0L &&
        (progress.durationMs <= 0L || progress.positionMs < progress.durationMs - 5 * 60_000L)
    val info = movie?.id?.let { state.movieProviderInfo[it] }

    LaunchedEffect(movie?.id) {
        movie?.id?.let { viewModel.loadMovieProviderInfo(it) }
    }
    BackHandler(enabled = true) { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (movie == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Movie not found",
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
                    HeroSection(
                        movie = movie,
                        info = info,
                        hasResume = hasResume,
                        onPlay = { onPlay(movie) },
                    )
                }
                item {
                    InfoSection(
                        movie = movie,
                        info = info,
                        onOpenUrl = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
            }
        }

        // Floating back button overlaid on the hero. Mirrors iOS's small
        // chevron pill drawn on top of the backdrop (the regular nav bar
        // is hidden so the artwork bleeds to the top safe area).
        FloatingBackButton(onClick = onBack)
    }
}

@Composable
private fun HeroSection(
    movie: DispatcharrVODMovie,
    info: DispatcharrVODProviderInfo?,
    hasResume: Boolean,
    onPlay: () -> Unit,
) {
    val heroUrl = info?.backdropUrl ?: movie.logo?.url
    val posterUrl = movie.logo?.url ?: info?.posterUrl
    val displayYear = info?.year?.toString() ?: movie.year?.toString()
    val displayRating = (info?.rating?.takeIf { it.isNotBlank() } ?: movie.rating)
        ?.let { runCatching { String.format("%.1f", it.toDouble()) }.getOrDefault(it) }
        ?.takeIf { it.isNotBlank() && it != "0.0" }
    val durationSecs = info?.durationSecs?.takeIf { it > 0 } ?: movie.durationSecs?.takeIf { it > 0 }

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
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        // Gradient overlay so the title block reads against any artwork.
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
                        startY = 0f,
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
            // Small poster anchored to bottom-leading like iOS VODDetailView
            // line 304-309.
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = movie.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // iOS VODDetailView line 312 sets the title directly to
                // `item.name` without appending the year; the year already
                // shows on the meta strip below. Dispatcharr often serves
                // titles that already embed "(YYYY)" — appending the
                // resolved year on top of that gives "'Til Death (2006) (2006)".
                Text(
                    text = movie.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                MetaStrip(
                    year = displayYear,
                    rating = displayRating,
                    durationSecs = durationSecs,
                    typeLabel = "MOVIE",
                )
                Spacer(Modifier.height(10.dp))
                PlayCta(hasResume = hasResume, onClick = onPlay)
            }
        }
    }
}

/**
 * Year · ★ rating · 1h 19m · MOVIE pill. Each piece independently optional —
 * a sparse movie row (e.g. only year known) shouldn't show a dangling " · ".
 * Mirrors iOS VODDetailView lines 317-353.
 */
@Composable
private fun MetaStrip(
    year: String?,
    rating: String?,
    durationSecs: Int?,
    typeLabel: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        if (durationSecs != null && durationSecs > 0) {
            Text(
                text = formatDuration(durationSecs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!typeLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Big rounded cyan Play / Resume button. */
@Composable
private fun PlayCta(hasResume: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = if (hasResume) "Resume" else "Play",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoSection(
    movie: DispatcharrVODMovie,
    info: DispatcharrVODProviderInfo?,
    onOpenUrl: (String) -> Unit,
) {
    val plot = info?.effectivePlot?.takeIf { it.isNotBlank() } ?: movie.plot?.takeIf { it.isNotBlank() }
    val genre = info?.effectiveGenre?.takeIf { it.isNotBlank() } ?: movie.genre?.takeIf { it.isNotBlank() }
    val cast = info?.effectiveCast?.takeIf { it.isNotBlank() }
    val director = info?.effectiveDirector?.takeIf { it.isNotBlank() }
    val country = info?.effectiveCountry?.takeIf { it.isNotBlank() }
    val trailerUrl = info?.effectiveTrailer?.let { youtubeUrl(it) }
    val tmdbUrl = (info?.tmdbId ?: movie.tmdbId)?.takeIf { it.isNotBlank() }?.let {
        "https://www.themoviedb.org/movie/$it"
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
                    PillButton(
                        icon = Icons.Outlined.PlayCircle,
                        text = "Trailer",
                        onClick = { onOpenUrl(trailerUrl) },
                    )
                }
                if (tmdbUrl != null) {
                    PillButton(
                        icon = Icons.Outlined.Info,
                        text = "View on TMDB",
                        onClick = { onOpenUrl(tmdbUrl) },
                    )
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
private fun FloatingBackButton(onClick: () -> Unit) {
    // statusBarsPadding pushes the button below the system status bar / notch
    // so the tap target lands inside the user-actionable safe area. iOS uses
    // the toolbar slot for this; we float over the hero, so the inset has
    // to be applied explicitly.
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

/**
 * Build a YouTube watch URL from whatever shape Dispatcharr stores
 * `youtube_trailer` in. Most providers send just the 11-char video key
 * (`dQw4w9WgXcQ`); a stray full URL or `youtu.be/<key>` shows up
 * occasionally. Mirrors iOS VODDetailView.trailerURL (line 488-498).
 */
internal fun youtubeUrl(raw: String): String? {
    val key = raw.trim()
    if (key.isEmpty()) return null
    if (key.startsWith("http://") || key.startsWith("https://")) return key
    if (key.startsWith("youtu.be/")) return "https://$key"
    return "https://www.youtube.com/watch?v=$key"
}

private fun formatDuration(totalSecs: Int): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
