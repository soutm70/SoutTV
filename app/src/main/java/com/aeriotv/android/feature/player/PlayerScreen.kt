package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils

private const val TAG = "PlayerScreen"

/**
 * Phase 1a player screen. Hardcoded to Mux's public HLS test stream.
 *
 * Composable lifecycle:
 *  1. AndroidView factory creates MPVPlayerView.
 *  2. DisposableEffect runs Utils.copyAssets + MPVPlayerView.initialize once
 *     (BaseMPVView.initialize internally calls MPV.create, initOptions,
 *      MPV.init, postInitOptions, observeProperties).
 *  3. Once initialized, MPVPlayerView.playFile loads the URL.
 *  4. DisposableEffect onDispose calls MPVPlayerView.destroy to release native handle.
 *
 * Phase 2 will replace the hardcoded URL with a real channel pulled from a Room-backed
 * playlist source.
 */
@Composable
fun PlayerScreen(
    streamUrl: String = TEST_HLS_STREAM,
    isLive: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Utils.copyAssets(ctx)
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path

                val view = MPVPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.isLive = isLive
                    this.caFilePath = "$configDir/cacert.pem"
                }

                Log.i(TAG, "Initializing MPV (configDir=$configDir cacheDir=$cacheDir)")
                view.initialize(configDir, cacheDir)

                // Logging observer: surfaces libmpv warnings/errors in logcat under our tag.
                view.mpv.addLogObserver(object : MPV.LogObserver {
                    override fun logMessage(prefix: String, level: Int, text: String) {
                        Log.i(TAG, "[mpv $prefix/L$level] ${text.trimEnd()}")
                    }
                })

                // Event observer: log key playback events while we verify playback works.
                view.mpv.addObserver(object : MPV.EventObserver {
                    override fun eventProperty(name: String) {
                        // Property changed but type-erased; details come via the typed variants.
                    }
                    override fun eventProperty(name: String, value: Long) {
                        Log.d(TAG, "prop $name=$value")
                    }
                    override fun eventProperty(name: String, value: Boolean) {
                        Log.d(TAG, "prop $name=$value")
                    }
                    override fun eventProperty(name: String, value: String) {
                        Log.d(TAG, "prop $name=$value")
                    }
                    override fun eventProperty(name: String, value: Double) {
                        if (name != "time-pos") Log.d(TAG, "prop $name=$value")
                    }
                    override fun eventProperty(name: String, value: MPVNode) {
                        Log.d(TAG, "prop $name (node)")
                    }
                    override fun event(eventId: Int, data: MPVNode) {
                        val label = when (eventId) {
                            MPVEvents.START_FILE -> "START_FILE"
                            MPVEvents.FILE_LOADED -> "FILE_LOADED"
                            MPVEvents.END_FILE -> "END_FILE"
                            MPVEvents.VIDEO_RECONFIG -> "VIDEO_RECONFIG"
                            MPVEvents.AUDIO_RECONFIG -> "AUDIO_RECONFIG"
                            MPVEvents.PLAYBACK_RESTART -> "PLAYBACK_RESTART"
                            MPVEvents.SEEK -> "SEEK"
                            MPVEvents.SHUTDOWN -> "SHUTDOWN"
                            else -> "event#$eventId"
                        }
                        Log.i(TAG, "mpv $label")
                    }
                })

                Log.i(TAG, "Loading stream: $streamUrl")
                view.playFile(streamUrl)

                view
            },
            onRelease = { view ->
                Log.i(TAG, "Releasing MPV")
                view.destroy()
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // AndroidView.onRelease handles native cleanup. Nothing extra needed here yet.
        }
    }
}

/**
 * Mux's official public HLS test stream. Big Buck Bunny, ~10 min VOD.
 * Stable for years, no auth required. Used here purely to prove the player pipeline works.
 */
private const val TEST_HLS_STREAM =
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
