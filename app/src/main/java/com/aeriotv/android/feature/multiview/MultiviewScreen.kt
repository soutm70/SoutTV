package com.aeriotv.android.feature.multiview

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.player.MPVPlayerView
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor
import `is`.xyz.mpv.Utils

private const val TAG = "MultiviewScreen"

/**
 * Multiview tile grid. Mirrors iOS MultiviewContainerView (Aerio/Features/
 * Multiview/MultiviewContainerView.swift) — one MPVPlayerView per selected
 * channel, only the audio-focused tile plays sound (others get aid=no), tap a
 * tile to swap audio focus.
 *
 * Phase 11b ships the 1/2/4/9-tile shape. 3/5/6/7/8 reuse the same column
 * grid logic with empty tail tiles. Audio-focus visual indicator (the
 * configurable centerIcon / grayPersistent / themeFading modes from iOS
 * MultiviewAudioFocusStyle) is the default centerIcon variant only;
 * Phase 11c surfaces the Settings toggle and other modes.
 */
@Composable
fun MultiviewScreen(
    onClose: () -> Unit,
    httpHeaders: Map<String, String> = emptyMap(),
    storeHandle: MultiviewStoreHandle = rememberMultiviewStoreHandle(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val selected by storeHandle.selected.collectAsState()
    val focused by storeHandle.audioFocusedIndex.collectAsState()
    val bufferSize by settingsVm.streamBufferSize.collectAsState(initial = "default")
    val audioFocusStyle by settingsVm.multiviewAudioFocusStyle.collectAsState(initial = "centerIcon")
    val tilePadding by settingsVm.multiviewTilePadding.collectAsState(initial = false)
    val tileRounded by settingsVm.multiviewTileCornersRounded.collectAsState(initial = false)

    var chromeVisible by remember { mutableStateOf(true) }
    // Long-press a tile -> relocate mode. The next tap swaps positions with
    // the relocating tile. Tap the same tile again (or the close X) to cancel.
    var relocatingIndex by remember { mutableStateOf<Int?>(null) }
    // For the themeFading mode: track the last time audio focus changed so the
    // accent border can auto-hide after 5s. Resets when the user taps a new tile.
    var focusActivityAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focused) { focusActivityAt = System.currentTimeMillis() }
    var focusFadedOut by remember { mutableStateOf(false) }
    LaunchedEffect(focusActivityAt) {
        focusFadedOut = false
        kotlinx.coroutines.delay(5_000L)
        focusFadedOut = true
    }

    if (selected.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No tiles selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloseButton(onClose = onClose)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        TileGrid(
            tiles = selected,
            focusedIndex = focused,
            relocatingIndex = relocatingIndex,
            httpHeaders = httpHeaders,
            cachingMs = bufferMillisFor(bufferSize),
            audioFocusStyle = audioFocusStyle,
            tilePadding = tilePadding,
            tileRounded = tileRounded,
            chromeVisible = chromeVisible,
            focusFadedOut = focusFadedOut,
            onTileTap = { idx ->
                val r = relocatingIndex
                if (r != null && r != idx) {
                    storeHandle.swap(r, idx)
                    relocatingIndex = null
                } else if (r == idx) {
                    relocatingIndex = null
                } else {
                    storeHandle.setAudioFocus(idx)
                }
            },
            onTileLongPress = { idx ->
                relocatingIndex = if (relocatingIndex == idx) null else idx
            },
        )

        if (chromeVisible) {
            CloseButton(onClose = {
                if (relocatingIndex != null) relocatingIndex = null else onClose()
            })
            val countLabel = relocatingIndex?.let { "Tap a tile to swap" }
                ?: "${selected.size} / ${storeHandle.maxTiles}"
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (relocatingIndex != null)
                    MaterialTheme.colorScheme.primary
                else
                    Color.White.copy(alpha = 0.85f),
                fontWeight = if (relocatingIndex != null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 18.dp, top = 18.dp),
            )
        }
    }

    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            kotlinx.coroutines.delay(4_000L)
            chromeVisible = false
        }
    }

    DisposableEffect(Unit) { onDispose { /* per-tile views own their lifecycle */ } }
}

@Composable
private fun BoxScope.CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 14.dp, top = 14.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Exit multiview",
                tint = Color.White,
            )
        }
    }
}

/**
 * Compute (rows, cols) for an N-tile grid. iOS reference values:
 *   1 -> 1x1, 2 -> 1x2, 3 -> 2x2 (one empty),
 *   4 -> 2x2, 5/6 -> 2x3, 7/8/9 -> 3x3.
 */
private fun gridShapeFor(count: Int): Pair<Int, Int> = when {
    count <= 1 -> 1 to 1
    count == 2 -> 1 to 2
    count <= 4 -> 2 to 2
    count <= 6 -> 2 to 3
    else -> 3 to 3
}

@Composable
private fun TileGrid(
    tiles: List<M3UChannel>,
    focusedIndex: Int,
    relocatingIndex: Int?,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tilePadding: Boolean,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    onTileTap: (Int) -> Unit,
    onTileLongPress: (Int) -> Unit,
) {
    val (rows, cols) = gridShapeFor(tiles.size)
    val pad = if (tilePadding) 4.dp else 0.dp
    Column(modifier = Modifier.fillMaxSize()) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (c in 0 until cols) {
                    val index = r * cols + c
                    val channel = tiles.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(pad),
                    ) {
                        if (channel != null) {
                            Tile(
                                channel = channel,
                                isAudioFocused = index == focusedIndex,
                                isRelocating = index == relocatingIndex,
                                httpHeaders = httpHeaders,
                                cachingMs = cachingMs,
                                audioFocusStyle = audioFocusStyle,
                                tileRounded = tileRounded,
                                chromeVisible = chromeVisible,
                                focusFadedOut = focusFadedOut,
                                onTap = { onTileTap(index) },
                                onLongPress = { onTileLongPress(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    channel: M3UChannel,
    isAudioFocused: Boolean,
    isRelocating: Boolean,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = if (tileRounded) RoundedCornerShape(8.dp) else RoundedCornerShape(0.dp)
    // Resolve the focus indicator for this tile. iOS canon:
    //   centerIcon: speaker icon fades with the chrome
    //   grayPersistent: muted gray border always around the active tile
    //   themeFading: cyan border that auto-hides after 5s of inactivity
    val showCenterIcon = isAudioFocused && audioFocusStyle == "centerIcon" && chromeVisible
    val borderColor = when {
        isRelocating -> MaterialTheme.colorScheme.primary
        isAudioFocused && audioFocusStyle == "grayPersistent" ->
            Color.White.copy(alpha = 0.5f)
        isAudioFocused && audioFocusStyle == "themeFading" && !focusFadedOut ->
            MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderWidth = when {
        isRelocating -> 3.dp
        isAudioFocused && (audioFocusStyle == "grayPersistent" ||
                (audioFocusStyle == "themeFading" && !focusFadedOut)) -> 2.dp
        else -> 1.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        // Tracks the URL the held MPV instance is currently playing. Lets
        // `update` distinguish a channel-flip (URL changed) from an aid-only
        // recomposition, so we call playFile (libmpv loadfile, replace mode)
        // only when needed. Mirrors iOS swapStreamIfChanged + the in-place
        // tile-swap pattern from commits e627ca7 / b34fa82 / 8fb0d5a.
        val currentUrlRef = remember { mutableStateOf("") }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Utils.copyAssets(ctx)
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path
                val view = MPVPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.isLive = true
                    this.caFilePath = "$configDir/cacert.pem"
                    this.httpHeaders = httpHeaders
                    this.cachingMs = cachingMs
                }
                view.initialize(configDir, cacheDir)
                Log.i(TAG, "Tile MPV loading: ${channel.name}")
                if (channel.url.isNotBlank()) {
                    view.playFile(channel.url)
                    currentUrlRef.value = channel.url
                }
                // Initial audio focus state.
                view.mpv.setPropertyString("aid", if (isAudioFocused) "auto" else "no")
                view
            },
            update = { view ->
                // In-place stream swap: when the positional Tile sees a new
                // channel (via MultiviewStore.swap or replaceTile), don't
                // teardown — just hand the new URL to the existing mpv handle.
                if (channel.url.isNotBlank() && currentUrlRef.value != channel.url) {
                    Log.i(TAG, "Tile MPV swap: ${currentUrlRef.value} -> ${channel.url}")
                    view.playFile(channel.url)
                    currentUrlRef.value = channel.url
                }
                view.mpv.setPropertyString("aid", if (isAudioFocused) "auto" else "no")
            },
            onRelease = { view ->
                Log.i(TAG, "Tile MPV releasing: ${channel.name}")
                view.destroy()
            },
        )
        // Channel-name overlay (top-left). Always shown — iOS canon keeps the
        // tile's broadcast bug visible alongside the channel label.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Audio-focus indicator. centerIcon mode shows the speaker icon over
        // the active tile while the chrome is visible. grayPersistent and
        // themeFading are border-only — handled above on the outer Box.
        if (showCenterIcon) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
    }
}
