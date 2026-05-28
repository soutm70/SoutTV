package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.playback.MPVPlayerHolder
import `is`.xyz.mpv.Utils

/**
 * The single, activity-lifetime MPV SurfaceView. Mounted ONCE inside
 * MainActivity's root Box (as a sibling above NavHost), it never changes
 * parents -- only the modifier driving its size + position changes in
 * response to [MpvWindowState] flips from PlayerScreen /
 * TvMiniPlayerOverlay.
 *
 * Phase 165: the architectural pivot the Streamer ANR investigation
 * demanded. Reparenting a SurfaceView between AndroidView frames hits
 * mpv-android-lib's blocking surfaceDestroyed callback (the block is
 * in Android's BufferQueue producer/consumer handoff, NOT in libmpv --
 * no libmpv property releases the VO synchronously without
 * mpv_terminate_destroy). The fix is structural: keep the View
 * attached to a stable parent, resize via layout params only.
 *
 * Visibility modes:
 *   - Hidden: AndroidView still mounted but inside a zero-size Box so
 *     it's invisible and consumes no layout space. libmpv stays alive
 *     in the background (PlaybackService keeps the process foreground
 *     when audio continues).
 *   - Fullscreen: fillMaxSize within the outer Box (covers the screen
 *     beneath PlayerScreen's chrome overlays drawn by the NavHost route).
 *   - Mini: 210x118 dp at top-right with 24dp end / 12dp top inset,
 *     rounded corners + black background fill.
 *
 * SurfaceView's default z-order (no setZOrderMediaOverlay / OnTop)
 * means its surface composites BELOW the window's main UI layer, so
 * Compose chrome inside the NavHost (PlayerScreen controls overlay)
 * and the mini-player's hint chip render correctly on top.
 */
@Composable
fun BoxScope.PersistentMpvWindow(
    mpvHolder: MPVPlayerHolder,
    state: MpvWindowState,
) {
    val mode by state.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Compute the parent Box modifier from mode. The AndroidView is at
    // the SAME composable position regardless of mode so Compose treats
    // it as a single, persistent slot -- no detach / reattach cycles.
    val containerModifier = when (mode) {
        MpvWindowState.Mode.Hidden -> Modifier.size(0.dp)
        MpvWindowState.Mode.Fullscreen -> Modifier
            .fillMaxSize()
            .background(Color.Black)
        MpvWindowState.Mode.Mini -> Modifier
            .align(Alignment.TopEnd)
            .padding(end = 24.dp, top = 12.dp)
            .size(width = 210.dp, height = 118.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    }

    Box(modifier = containerModifier) {
        AndroidView(
            factory = { ctx ->
                Log.i(TAG, "PersistentMpvWindow factory: acquiring view")
                Utils.copyAssets(ctx)
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path
                val view = mpvHolder.acquireOrCreate(
                    context = ctx,
                    caFilePath = "$configDir/cacert.pem",
                    cachingMs = 5_000,
                    isLive = true,
                    httpHeaders = emptyMap(),
                    configDir = configDir,
                    cacheDir = cacheDir,
                )
                view.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            // The activity-lifetime View has no onRelease teardown: it
            // outlives every composition. PlayerScreen / mini-overlay
            // only flip MpvWindowState, never own a View. Explicit user
            // close (X button) goes through mpvHolder.destroy() + state
            // .hide() which is the one path that does tear MPV down.
        )
    }
}

private const val TAG = "PersistentMpvWindow"
