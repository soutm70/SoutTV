package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MAX
import com.aeriotv.android.core.preferences.GUIDE_SCALE_MIN
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.multiview.MultiviewStoreHandle
import com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check

/**
 * EPG Guide grid (channels on Y, time on X). Mirrors iOS EPGGuideView.
 *
 * Layout:
 *   +---------+--------------------- horizontalScroll -------------------->
 *   | (corner)|  10:00 | 11:00 | 12:00 | 13:00 | 14:00 | ...
 *   +---------+----------------------------------------------------------
 *   | [logo]  |  [programme]  [programme]      [programme]
 *   | ESPN HD |
 *   +---------+----------------------------------------------------------
 *   | [logo]  |       [programme]    [programme]    [programme]
 *   | TBS HD  |
 *   +---------+----------------------------------------------------------
 *
 * A single horizontalScroll state is shared across the time-header row and every
 * channel row so they pan together. Programmes are absolutely positioned in
 * their row via Modifier.offset, with x derived from
 *   (programme.start - windowStart) / msPerDp.
 *
 * "Now" indicator: a 2dp vertical cyan line drawn over the programme strip at
 * the current-time x position, recomputed every minute.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewMode: LiveTVViewMode,
    canToggleViewMode: Boolean,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val epgWindowHours by settingsVm.epgWindowHours.collectAsStateWithLifecycle(initialValue = 24)
    val multiviewStore = rememberMultiviewStoreHandle()

    // Guide timeline zoom (iOS guideScale). `liveScale` tracks the in-flight
    // pinch and the discrete selector; it's seeded from the persisted value and
    // re-synced whenever that changes (e.g. selector tap or another screen).
    // The pinch commits to the store on gesture end so we hit DataStore once
    // per gesture, not every frame.
    val storedScale by settingsVm.guideScale.collectAsStateWithLifecycle(initialValue = 1f)
    var liveScale by remember { mutableStateOf(storedScale) }
    LaunchedEffect(storedScale) { liveScale = storedScale }
    val scale = liveScale.coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
    val scaledHourWidth = GuideMetrics.HOUR_WIDTH * scale

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }

    // Tick "now" forward every 30s so the indicator + currently-airing tinting stay
    // accurate without forcing the whole tree to recompose on every frame.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Guide window: 1h of history before "now", then `epgWindowHours` ahead.
    // The user picks the span in Settings > Network > EPG Window
    // (6/12/24/36/48/72h or "All available"). iOS `epgWindowHours` parity.
    val windowStart = remember(nowMillis) {
        // Floor `now` to the start of the current hour to keep header labels clean.
        val hourMs = 3_600_000L
        (nowMillis / hourMs) * hourMs - hourMs
    }
    // "All available" (sentinel 0) spans from windowStart to the latest loaded
    // programme end, clamped to a 6h floor so a thin EPG still scrolls. A
    // numeric hour value is just that many hours wide.
    val windowDurationMs = remember(epgWindowHours, state.epgByChannel, windowStart) {
        if (epgWindowHours > 0) {
            epgWindowHours.toLong() * 3_600_000L
        } else {
            val latestEnd = state.epgByChannel.values.asSequence()
                .flatten()
                .maxOfOrNull { it.endMillis } ?: (windowStart + 24L * 3_600_000L)
            (latestEnd - windowStart).coerceAtLeast(6L * 3_600_000L)
        }
    }

    val groups by remember(state.channels) {
        derivedStateOf {
            val unique = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
            listOf(PlaylistViewModel.ALL_GROUPS) + unique
        }
    }

    val filteredChannels by remember(state.channels, state.selectedGroup) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            state.channels.asSequence()
                .filter {
                    state.selectedGroup == PlaylistViewModel.ALL_GROUPS ||
                            it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .sortedWith(compareBy({ it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name.lowercase() }))
                .toList()
        }
    }

    val timeFormatter = remember { SimpleDateFormat("h a", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() } }

    // Horizontal scroll state shared across the time-header row + every channel
    // row so they pan together.
    val horizontalScrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        // Jump-to-now scroller. Coroutine-driven so animateScrollTo can
        // suspend; matches iOS EPGGuideView's "scroll back to now" button
        // which snaps the time axis so the now-indicator sits ~1/4 of the
        // way across the viewport.
        val jumpScope = rememberCoroutineScope()
        val density = LocalDensity.current
        TopAppBar(
            title = {
                val playlistName = state.playlist?.name?.takeIf { it.isNotBlank() } ?: "Live TV"
                Text(
                    text = "$playlistName  •  ${filteredChannels.size} / ${state.channels.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            actions = {
                // Jump-to-now button. Highlights primary tint when the
                // horizontal scroll has drifted more than two hours from
                // the now-indicator, so it visually announces itself only
                // when actually useful. Matches iOS EPGGuideView nav-bar
                // "now" affordance.
                val nowOffsetPx = with(density) {
                    msToDp(nowMillis - windowStart, scaledHourWidth).toPx()
                }
                val visibleCenter = horizontalScrollState.value +
                    with(density) { scaledHourWidth.toPx() }
                val nowOffScreen = kotlin.math.abs(visibleCenter - nowOffsetPx) >
                    with(density) { (scaledHourWidth * 2).toPx() }
                // Discrete zoom selector (iOS guideScale, complements pinch).
                ZoomSelector(
                    scale = scale,
                    onSelect = { settingsVm.setGuideScale(it) },
                )
                IconButton(onClick = {
                    jumpScope.launch {
                        // Land the now-indicator ~1/4 into the viewport so
                        // the user sees a bit of past + the upcoming block
                        // of programmes. iOS uses the same offset.
                        val target = (nowOffsetPx - with(density) { scaledHourWidth.toPx() / 2f })
                            .toInt().coerceAtLeast(0)
                        horizontalScrollState.animateScrollTo(target)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.AccessTime,
                        contentDescription = "Jump to now",
                        tint = if (nowOffScreen)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canToggleViewMode) {
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == LiveTVViewMode.Guide)
                                Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                            contentDescription = if (viewMode == LiveTVViewMode.Guide) "Switch to List" else "Switch to Guide",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        // Group filter chips - same UX as the List view so toggling between
        // modes keeps the user's filter context.
        if (groups.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it }) { group ->
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { viewModel.onGroupSelected(group) },
                        label = { Text(group) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        // Time-header row: empty corner over the channel rail + horizontally-scrolled hour labels.
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(GuideMetrics.RAIL_WIDTH)
                    .height(GuideMetrics.HEADER_HEIGHT)
                    .background(MaterialTheme.colorScheme.background),
            )
            Box(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val hourCount = (windowDurationMs / 3_600_000L).toInt()
                Row(
                    modifier = Modifier
                        .width(scaledHourWidth * hourCount)
                        .height(GuideMetrics.HEADER_HEIGHT),
                ) {
                    for (i in 0 until hourCount) {
                        val slotStart = windowStart + i * 3_600_000L
                        Box(
                            modifier = Modifier
                                .width(scaledHourWidth)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = timeFormatter.format(java.util.Date(slotStart)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // Pinch-to-zoom the timeline. Custom detector that only acts on
                // two-or-more pointers so single-finger pans still reach the
                // LazyColumn's vertical scroll + the rows' horizontal scroll.
                // Commits to DataStore once per gesture (on lift).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var didZoom = false
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    liveScale = (liveScale * zoom)
                                        .coerceIn(GUIDE_SCALE_MIN, GUIDE_SCALE_MAX)
                                    didZoom = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (didZoom) settingsVm.setGuideScale(liveScale)
                    }
                },
        ) {
            items(items = filteredChannels, key = { it.id }) { channel ->
                val programmes = state.epgByChannel[channel.tvgID].orEmpty()
                ChannelGuideRow(
                    channel = channel,
                    programmes = programmes,
                    windowStart = windowStart,
                    windowDurationMs = windowDurationMs,
                    hourWidth = scaledHourWidth,
                    nowMillis = nowMillis,
                    horizontalScrollState = horizontalScrollState,
                    onChannelClick = { onChannelClick(channel) },
                    onProgrammeClick = { programme ->
                        programInfoTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    onProgrammeRecord = { programme ->
                        recordTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    isFavorite = channel.id in favoriteIds,
                    onToggleFavorite = { favoritesVm.toggle(channel) },
                    palette = palette,
                    multiviewStore = multiviewStore,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
            }
        }
    }

    programInfoTarget?.let { target ->
        ProgramInfoSheet(
            target = target,
            onDismiss = { programInfoTarget = null },
        )
    }
    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuideRow(
    channel: M3UChannel,
    programmes: List<EPGProgramme>,
    windowStart: Long,
    windowDurationMs: Long,
    hourWidth: androidx.compose.ui.unit.Dp,
    nowMillis: Long,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    onChannelClick: () -> Unit,
    onProgrammeClick: (EPGProgramme) -> Unit,
    onProgrammeRecord: (EPGProgramme) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    palette: CategoryPaletteState,
    multiviewStore: MultiviewStoreHandle,
) {
    val multiviewSelected by multiviewStore.selected.collectAsStateWithLifecycle()
    val inMultiview = multiviewSelected.any { it.id == channel.id }
    val atCap = multiviewSelected.size >= multiviewStore.maxTiles && !inMultiview
    val context = LocalContext.current
    var railMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GuideMetrics.ROW_HEIGHT),
    ) {
        // Sticky-left channel rail. Tap plays the channel; long-press opens the
        // favorites toggle. Mirrors iOS EPGGuideView channel-rail .contextMenu
        // (EPGGuideView.swift:2823).
        Row(
            modifier = Modifier
                .width(GuideMetrics.RAIL_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                .combinedClickable(
                    onClick = onChannelClick,
                    onLongClick = { railMenuOpen = true },
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channel.channelNumber?.let { num ->
                Text(
                    text = num.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenu(
                expanded = railMenuOpen,
                onDismissRequest = { railMenuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
                    },
                    onClick = {
                        railMenuOpen = false
                        onToggleFavorite()
                    },
                )
                if (channel.url.isNotBlank()) {
                    DropdownMenuItem(
                        enabled = !atCap,
                        text = {
                            Text(
                                text = when {
                                    inMultiview -> "Remove from Multiview"
                                    atCap -> "Multiview full"
                                    else -> "Add to Multiview"
                                },
                            )
                        },
                        onClick = {
                            railMenuOpen = false
                            if (!atCap || inMultiview) multiviewStore.toggle(channel)
                        },
                    )
                }
            }
        }

        // Programme strip - horizontally scrolled with the header.
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
        ) {
            val totalWidth = hourWidth * (windowDurationMs / 3_600_000L).toInt()
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                // Programme cells, positioned by offset.
                programmes.forEach { programme ->
                    // Clip to the window so off-screen programmes don't bloat the layout.
                    val rawStart = programme.startMillis
                    val rawEnd = programme.endMillis
                    if (rawEnd <= windowStart) return@forEach
                    val windowEnd = windowStart + windowDurationMs
                    if (rawStart >= windowEnd) return@forEach
                    val clippedStart = rawStart.coerceAtLeast(windowStart)
                    val clippedEnd = rawEnd.coerceAtMost(windowEnd)
                    val xDp = msToDp(clippedStart - windowStart, hourWidth)
                    val wDp = msToDp(clippedEnd - clippedStart, hourWidth)
                    val isLive = programme.startMillis <= nowMillis && programme.endMillis > nowMillis
                    ProgrammeCell(
                        programme = programme,
                        channelName = channel.name,
                        channelId = channel.id,
                        widthDp = wDp,
                        isLive = isLive,
                        // Dispatcharr bulk grid drops <category>; fall back
                        // to the channel's group title so guide cells still
                        // tint even before any per-program lazy enrichment
                        // hits. Mirrors the same fallback in ChannelRow.
                        categoryTint = palette.tintFor(
                            rawCategory = programme.category,
                            isLive = isLive,
                            fallback = channel.groupTitle,
                        ),
                        modifier = Modifier.offset(x = xDp),
                        onClick = { onProgrammeClick(programme) },
                        onRecord = { onProgrammeRecord(programme) },
                        canAddToMultiview = channel.url.isNotBlank() && (!atCap || inMultiview),
                        inMultiview = inMultiview,
                        onToggleMultiview = { multiviewStore.toggle(channel) },
                    )
                }
                // "Now" indicator - 2dp cyan line, only drawn when "now" falls inside the window.
                if (nowMillis in (windowStart + 1)..(windowStart + windowDurationMs - 1)) {
                    val nowX = msToDp(nowMillis - windowStart, hourWidth)
                    Box(
                        modifier = Modifier
                            .offset(x = nowX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgrammeCell(
    programme: EPGProgramme,
    channelName: String,
    channelId: String,
    widthDp: androidx.compose.ui.unit.Dp,
    isLive: Boolean,
    categoryTint: androidx.compose.ui.graphics.Color?,
    modifier: Modifier,
    onClick: () -> Unit,
    onRecord: () -> Unit,
    canAddToMultiview: Boolean,
    inMultiview: Boolean,
    onToggleMultiview: () -> Unit,
    remindersVm: RemindersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val key = remember(programme, channelName) {
        reminderKey(channelName, programme.title, programme.startMillis)
    }
    val isReminderSet by remindersVm.observeIsSet(key)
        .collectAsStateWithLifecycle(initialValue = false)
    val bg = categoryTint ?: if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    val borderColor = if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.surfaceVariant
    val titleColor = if (isLive)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(start = 1.dp, end = 1.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text("Program Info") },
                onClick = {
                    menuOpen = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(if (isReminderSet) "Cancel Reminder" else "Set Reminder")
                },
                onClick = {
                    menuOpen = false
                    if (isReminderSet) {
                        remindersVm.cancelReminder(key)
                        Toast.makeText(context, "Reminder cancelled.", Toast.LENGTH_SHORT).show()
                    } else {
                        remindersVm.setReminder(
                            channelName = channelName,
                            programTitle = programme.title,
                            startMillis = programme.startMillis,
                            endMillis = programme.endMillis,
                            channelId = channelId,
                        )
                        Toast.makeText(context, "Reminder set.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (canAddToMultiview) {
                DropdownMenuItem(
                    text = {
                        Text(if (inMultiview) "Remove from Multiview" else "Add to Multiview")
                    },
                    onClick = {
                        menuOpen = false
                        onToggleMultiview()
                    },
                )
            }
            if (programme.endMillis > System.currentTimeMillis()) {
                val recordLabel = if (isLive) "Record from Now" else "Record"
                DropdownMenuItem(
                    text = { Text(recordLabel) },
                    onClick = {
                        menuOpen = false
                        onRecord()
                    },
                )
            }
        }
        Text(
            text = programme.title.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (programme.description.isNotBlank()) {
            Text(
                text = programme.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Preset zoom levels for the discrete selector (within GUIDE_SCALE_MIN..MAX). */
private val GUIDE_ZOOM_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Discrete guide-zoom control in the top bar: a percentage button that opens a
 * preset menu. Complements pinch-to-zoom (iOS guideScale). The active preset is
 * checkmarked; "active" tolerates small float drift from a prior pinch.
 */
@Composable
private fun ZoomSelector(scale: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = "${kotlin.math.round(scale * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            GUIDE_ZOOM_PRESETS.forEach { preset ->
                val active = kotlin.math.abs(preset - scale) < 0.02f
                DropdownMenuItem(
                    text = { Text("${(preset * 100).toInt()}%") },
                    trailingIcon = {
                        if (active) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(preset)
                    },
                )
            }
        }
    }
}

/** Layout constants for the guide grid. [HOUR_WIDTH] is the unscaled base; the
 * guide zoom (iOS guideScale) multiplies it and everything time-axis derives
 * from the scaled value via [msToDp]. */
private object GuideMetrics {
    val RAIL_WIDTH = 168.dp
    val HEADER_HEIGHT = 36.dp
    val ROW_HEIGHT = 80.dp
    /** Base (1.0x) width of one hour column. Scaled by guideScale at render. */
    val HOUR_WIDTH = 320.dp
}

private const val MS_PER_HOUR_F = 3_600_000f

/** Convert a millisecond span to its dp width on the (already scaled) time axis. */
private fun msToDp(ms: Long, hourWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (hourWidth.value * (ms.toFloat() / MS_PER_HOUR_F)).dp
