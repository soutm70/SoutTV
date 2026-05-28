package com.aeriotv.android.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped layout state for the single persistent MPV SurfaceView.
 *
 * Architecture (Phase 165, per research convergence):
 *
 * ONE MPVPlayerView lives in MainActivity's `setContent` root Box, OUTSIDE
 * the NavHost. Its size + position are driven by [bounds]. Routes inside
 * the NavHost (PlayerScreen, TvMiniPlayerOverlay) NEVER create their own
 * AndroidView -- they only call [requestFullscreen] / [requestMini] /
 * [hide] to flip this state. The View's parent never changes, so
 * `removeView` and the synchronous `surfaceDestroyed` callback (which
 * blocks the main thread waiting for libmpv's render thread per the
 * Streamer ANR investigation) never fire across nav transitions.
 *
 * This is the architecture TiviMate / iMPlayer / Media3-Compose docs all
 * use. See https://developer.android.com/media/media3/ui/compose --
 * "always keep PlayerSurface in tree, control via state."
 *
 * SurfaceFlinger's hardware scaler handles the resize for free (the
 * underlying buffer is `setFixedSize`'d to the video's intrinsic
 * resolution), so 210x118 mini -> 1920x1080 fullscreen is essentially
 * free pixel-pushing wise.
 */
@Singleton
class MpvWindowState @Inject constructor() {

    /** Rectangle the SurfaceView should occupy. Null = not visible. */
    data class Bounds(
        val xDp: Dp,
        val yDp: Dp,
        val widthDp: Dp,
        val heightDp: Dp,
    )

    enum class Mode { Hidden, Fullscreen, Mini }

    private val _bounds = MutableStateFlow<Bounds?>(null)
    val bounds: StateFlow<Bounds?> = _bounds.asStateFlow()

    private val _mode = MutableStateFlow(Mode.Hidden)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /**
     * Show the SurfaceView at fullscreen. Caller is typically PlayerScreen
     * on mount.
     */
    fun requestFullscreen() {
        _mode.value = Mode.Fullscreen
        _bounds.value = FULLSCREEN_BOUNDS
    }

    /**
     * Show the SurfaceView at the standard mini-player bounds for TV
     * (top-right, 210x118 dp). Caller is typically TvMiniPlayerOverlay's
     * LaunchedEffect when state goes Active.
     */
    fun requestMini() {
        _mode.value = Mode.Mini
        _bounds.value = TV_MINI_BOUNDS
    }

    /** Hide the SurfaceView (user dismissed the player). */
    fun hide() {
        _mode.value = Mode.Hidden
        _bounds.value = null
    }

    private companion object {
        // A sentinel bounds value meaning "fill the parent Box". The
        // PersistentMpvWindow composable interprets the FULLSCREEN_BOUNDS
        // marker via Modifier.fillMaxSize() instead of an explicit size,
        // so device rotation / screen-size changes don't require updating
        // this state.
        val FULLSCREEN_BOUNDS = Bounds(0.dp, 0.dp, Dp.Unspecified, Dp.Unspecified)
        // tvOS reference: 210x118 dp at top-right with 24dp end + 12dp
        // top inset. Matches the layout target from Phase 161-164.
        // x = window width - 210 - 24. We translate this via Modifier
        // .align(TopEnd) in the composable so we don't need actual x.
        val TV_MINI_BOUNDS = Bounds(0.dp, 12.dp, 210.dp, 118.dp)
    }
}
