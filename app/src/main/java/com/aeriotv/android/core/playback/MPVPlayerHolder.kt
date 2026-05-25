package com.aeriotv.android.core.playback

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.aeriotv.android.feature.player.MPVPlayerView
import `is`.xyz.mpv.Utils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hoists the active [MPVPlayerView] out of any single composable lifecycle so
 * the underlying libmpv handle survives when PlayerScreen exits (system back
 * → mini-player). Without this, every nav transition tears MPV down + does a
 * second multi-second native re-init, and audio cuts.
 *
 * Pattern:
 *   - PlayerScreen calls [acquireOrCreate] inside its AndroidView factory.
 *     The first call constructs MPV; subsequent calls (after a back-out and
 *     resume) detach the same view from its prior parent and hand it back.
 *   - AndroidView's onRelease calls [detach] instead of destroy, leaving MPV
 *     alive in the holder while the surface is unparented.
 *   - When the user dismisses via the explicit X button, the caller invokes
 *     [destroy] to tear MPV down for real.
 *
 * Threading: all entry points expect the main thread (MPVPlayerView attaches
 * to the view hierarchy synchronously).
 */
@Singleton
class MPVPlayerHolder @Inject constructor() {

    var view: MPVPlayerView? = null
        private set

    /** The most-recent channel id played through the held MPV instance, so a
     * resuming PlayerScreen knows whether to skip the playFile re-init. */
    var currentChannelId: String? = null

    /** Off-main scope for the blocking libmpv teardown (mpv_terminate_destroy
     * joins decode/render/audio threads and would ANR the close button). */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Return the active MPV view, creating it once on first call. The view is
     * detached from any prior parent before return so the caller can attach
     * it to a fresh ViewGroup (AndroidView's host frame).
     */
    fun acquireOrCreate(
        context: Context,
        caFilePath: String,
        cachingMs: Int,
        isLive: Boolean,
        httpHeaders: Map<String, String>,
        configDir: String,
        cacheDir: String,
    ): MPVPlayerView {
        val existing = view
        if (existing != null) {
            // The hosting AndroidView frame from the prior composition still
            // holds this view as a child; detach so the new frame can adopt.
            (existing.parent as? ViewGroup)?.removeView(existing)
            // The new chrome may have a different buffer/headers config — push
            // those onto the live instance without re-initialising.
            existing.cachingMs = cachingMs
            existing.httpHeaders = httpHeaders
            // Re-enable video + unpause: a close (see [destroy]) leaves the
            // retained handle paused with vid=no. The `pause` property survives
            // loadfile, so without this an in-place reuse would load the new
            // channel but stay frozen on the first frame.
            runCatching { existing.mpv.setPropertyString("vid", "auto") }
            runCatching { existing.mpv.setPropertyString("pause", "no") }
            return existing
        }
        Log.i(TAG, "Creating fresh MPVPlayerView in holder")
        Utils.copyAssets(context)
        val fresh = MPVPlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            this.isLive = isLive
            this.caFilePath = caFilePath
            this.httpHeaders = httpHeaders
            this.cachingMs = cachingMs
        }
        // Time the first-create cost so the MpvLibraryWarmup benefit is
        // visible in logcat. On a cold launch without the warmup this
        // typically prints 800-2000ms on real hardware (the JNI bridge +
        // codec/protocol registration the warmup pre-pays). With the
        // warmup, this prints under 100ms because the global registrations
        // are already cached and only the per-handle init runs.
        val initMs = kotlin.system.measureTimeMillis {
            fresh.initialize(configDir, cacheDir)
        }
        Log.i(
            TAG,
            "MPVPlayerView.initialize() completed in ${initMs}ms " +
                "(warmup=${com.aeriotv.android.feature.player.MpvLibraryWarmup.isComplete})",
        )
        view = fresh
        return fresh
    }

    /** Composable-unmount hook. Does NOT destroy MPV; PlaybackService is
     * responsible for keeping the process alive while audio continues. */
    fun detach() {
        val v = view ?: return
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Toggle MPV video output. Used when entering / exiting the mini-player
     * so GPU isn't running for an unparented surface. Audio is unaffected. */
    fun setVideoEnabled(enabled: Boolean) {
        val v = view ?: return
        runCatching { v.mpv.setPropertyString("vid", if (enabled) "auto" else "no") }
            .onFailure { Log.w(TAG, "setVideoEnabled $enabled failed", it) }
    }

    /** Pause / resume playback without tearing anything down. */
    fun setPaused(paused: Boolean) {
        val v = view ?: return
        runCatching { v.mpv.setPropertyString("pause", if (paused) "yes" else "no") }
            .onFailure { Log.w(TAG, "setPaused $paused failed", it) }
    }

    fun isPaused(): Boolean = view?.mpv?.getPropertyString("pause") == "yes"

    /**
     * Close handler for the player X button + mini-player dismiss. Stops
     * playback and detaches the SurfaceView, but deliberately does NOT tear
     * down the libmpv core.
     *
     * Why not call MPV.destroy() (mpv_terminate_destroy / nativeDestroy):
     *  1. It BLOCKS the calling thread for several seconds while libmpv joins
     *     its demuxer/decoder/render/audio threads. Invoked from the close
     *     button's main-thread onClick, that froze the UI past the 5s input
     *     dispatch deadline -> "AerioTV isn't responding" ANR on close.
     *  2. On mpv-android-lib 0.1.12 nativeDestroy also releases process-global
     *     JNI state that the warmed handle + the next nativeCreate need
     *     (Phase 82), so the *next* stream opened after a close came up dead
     *     ("close one, start another crashes").
     *
     * Instead we `stop` (frees the demuxer/decoders/audio output for the
     * current file) and detach the view, but RETAIN the MPV handle. The next
     * channel reuses it via [acquireOrCreate] + playFile -- the same retain-
     * and-reuse path the mini-player resume already relies on, and it makes
     * the next open instant. The handle is reclaimed when the process dies.
     * currentChannelId is cleared so the next acquire re-issues playFile.
     */
    fun destroy() {
        val v = view ?: return
        view = null
        currentChannelId = null
        Log.i(TAG, "Player close: vid=no -> detach -> async native teardown")
        // ORDER MATTERS. Two separate things were ANR'ing the X-close, both on
        // the main thread:
        //  1. removeView() fires SurfaceHolder.surfaceDestroyed synchronously,
        //     and the lib's handler blocks waiting for libmpv's render thread
        //     to release the surface -- which, while the live stream is still
        //     decoding, took >5s. Setting vid=no FIRST stops rendering so the
        //     surface releases immediately and removeView returns at once
        //     (same trick the mini-player uses before it detaches).
        //  2. mpv_terminate_destroy (nativeDestroy) joins libmpv's demuxer/
        //     decode/audio threads and blocks for seconds. Run it on a
        //     background dispatcher. The retained warmup handle (Phase 94)
        //     keeps the process-global JNI state alive, so destroying this
        //     user handle does NOT poison the next nativeCreate (the Phase 82
        //     failure only happened when NO handle remained).
        // The next open creates a fresh handle (view is null), which renders
        // video reliably -- reusing a detached handle across a new loadfile
        // left the video output black.
        runCatching { v.mpv.setPropertyString("vid", "no") }
        (v.parent as? ViewGroup)?.removeView(v)
        val mpv = v.mpv
        teardownScope.launch {
            runCatching { mpv.destroy() }
                .onFailure { Log.w(TAG, "async mpv teardown failed", it) }
        }
    }

    companion object { private const val TAG = "MPVPlayerHolder" }
}
