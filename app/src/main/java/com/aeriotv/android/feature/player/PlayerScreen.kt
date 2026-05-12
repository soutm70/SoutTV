package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.playlist.nowPlaying
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "PlayerScreen"
private const val AUTO_HIDE_MS = 4_000L

@Composable
fun PlayerScreen(
    channel: M3UChannel?,
    streamUrl: String,
    isLive: Boolean = true,
    httpHeaders: Map<String, String> = emptyMap(),
    programmes: List<EPGProgramme> = emptyList(),
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current

    // Surface state — overlay visibility + ad-hoc menu hosts.
    var chromeVisible by remember { mutableStateOf(true) }
    var audioOnly by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfoSnapshot?>(null) }
    var subtitles by remember { mutableStateOf<SubtitlesState?>(null) }

    // Sleep timer: stores the wall-clock millis at which the player should close.
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepRemainingMillis by remember { mutableStateOf<Long?>(null) }

    // MPV handle the overlay reads from to pull property snapshots on demand.
    var mpvView by remember { mutableStateOf<MPVPlayerView?>(null) }

    val nowProgramme = remember(programmes) { programmes.nowPlaying() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.isLive = isLive
                    this.caFilePath = "$configDir/cacert.pem"
                    this.httpHeaders = httpHeaders
                }

                Log.i(TAG, "Initializing MPV (configDir=$configDir cacheDir=$cacheDir)")
                view.initialize(configDir, cacheDir)

                view.mpv.addLogObserver(object : MPV.LogObserver {
                    override fun logMessage(prefix: String, level: Int, text: String) {
                        Log.i(TAG, "[mpv $prefix/L$level] ${text.trimEnd()}")
                    }
                })

                view.mpv.addObserver(object : MPV.EventObserver {
                    override fun eventProperty(property: String) {}
                    override fun eventProperty(property: String, value: Long) {}
                    override fun eventProperty(property: String, value: Boolean) {}
                    override fun eventProperty(property: String, value: String) {}
                    override fun eventProperty(property: String, value: Double) {}
                    override fun eventProperty(property: String, value: MPVNode) {}
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
                if (streamUrl.isNotBlank()) view.playFile(streamUrl)
                mpvView = view
                view
            },
            onRelease = { view ->
                Log.i(TAG, "Releasing MPV")
                mpvView = null
                view.destroy()
            },
        )

        // Transparent tap-target above the video to toggle chrome. MPV draws on
        // a SurfaceView under this Box so the clickable doesn't fight focus.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chromeVisible = !chromeVisible },
        )

        PlayerChromeOverlay(
            channel = channel,
            nowProgramme = nowProgramme,
            chromeVisible = chromeVisible,
            onClose = onClose,
            onAddToMultiview = {
                Toast.makeText(
                    context,
                    "Multiview lands with Phase 11.",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onShowRecord = { target -> recordTarget = target },
            onShowStreamInfo = {
                streamInfo = mpvView?.captureStreamInfo() ?: StreamInfoSnapshot(
                    videoLines = listOf("(player not ready)"),
                    audioLines = emptyList(),
                    cacheLines = emptyList(),
                    syncLines = emptyList(),
                )
            },
            onShowSubtitles = {
                val view = mpvView ?: return@PlayerChromeOverlay
                subtitles = SubtitlesState(
                    tracks = view.readSubtitleTracks(),
                    currentSid = view.readCurrentSid(),
                )
            },
            onToggleAudioOnly = {
                audioOnly = !audioOnly
                mpvView?.mpv?.setPropertyString("vid", if (audioOnly) "no" else "auto")
            },
            audioOnly = audioOnly,
            onSetSleepMinutes = { minutes ->
                sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
            },
            sleepRemainingMillis = sleepRemainingMillis,
        )
    }

    // Auto-hide chrome after 4s of stillness. The user tapping the video resets
    // it via the `chromeVisible = !chromeVisible` toggle above.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    // Sleep timer tick: every second compute remaining and fire onClose when due.
    LaunchedEffect(sleepEndsAt) {
        val target = sleepEndsAt
        if (target == null) {
            sleepRemainingMillis = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0L) {
                sleepRemainingMillis = null
                sleepEndsAt = null
                onClose()
                break
            }
            sleepRemainingMillis = remaining
            delay(1_000L)
        }
    }

    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
    streamInfo?.let { snapshot ->
        StreamInfoSheet(
            snapshot = snapshot,
            onDismiss = { streamInfo = null },
        )
    }
    subtitles?.let { state ->
        SubtitlesSheet(
            tracks = state.tracks,
            currentTrackId = state.currentSid,
            onSelect = { sid ->
                mpvView?.mpv?.setPropertyString("sid", sid?.toString() ?: "no")
                subtitles = null
            },
            onDismiss = { subtitles = null },
        )
    }

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}

private data class SubtitlesState(
    val tracks: List<SubtitleTrack>,
    val currentSid: Int?,
)

// ──────────────────────────────────────────────────────────────────────────
// MPV property helpers. Pulled out as MPVPlayerView extensions so they live
// alongside the player and are unit-test-able.
// ──────────────────────────────────────────────────────────────────────────

private fun MPVPlayerView.captureStreamInfo(): StreamInfoSnapshot {
    val m = mpv
    val width = m.getPropertyString("width").orZero()
    val height = m.getPropertyString("height").orZero()
    val fps = m.getPropertyString("estimated-vf-fps").orEmpty()
    val pixFmt = m.getPropertyString("video-params/pixelformat").orEmpty()
    val hwdec = m.getPropertyString("hwdec-current").orEmpty().ifBlank { "no" }
    val videoFmt = m.getPropertyString("video-format").orEmpty()
    val videoCodec = m.getPropertyString("video-codec").orEmpty()

    val audioCodec = m.getPropertyString("audio-codec").orEmpty()
    val audioRate = m.getPropertyString("audio-params/samplerate").orEmpty()
    val audioChannels = m.getPropertyString("audio-params/channels").orEmpty()
    val audioName = m.getPropertyString("audio-codec-name").orEmpty()

    val cacheSecs = m.getPropertyString("demuxer-cache-duration").orEmpty()
    val cacheKbps = m.getPropertyString("cache-speed").orEmpty()

    val avSync = m.getPropertyString("avsync").orEmpty()
    val drops = m.getPropertyString("frame-drop-count").orEmpty()

    val videoLines = buildList {
        if (videoCodec.isNotBlank()) add(videoCodec)
        val dim = "${width}x${height}".takeIf { width.isNotBlank() && height.isNotBlank() }
        listOfNotNull(
            dim,
            fps.takeIf { it.isNotBlank() }?.let { "${it.toDoubleOrNull()?.roundToOneDecimal() ?: it}fps" },
            pixFmt.takeIf { it.isNotBlank() },
        ).joinToString("  ").takeIf { it.isNotBlank() }?.let(::add)
        if (videoFmt.isNotBlank()) add("format $videoFmt")
        add("hwdec: $hwdec")
    }
    val audioLines = buildList {
        if (audioCodec.isNotBlank()) add(audioCodec)
        val tail = listOfNotNull(
            audioRate.takeIf { it.isNotBlank() }?.let { "${it}Hz" },
            audioChannels.takeIf { it.isNotBlank() }?.let { "${it}ch" },
            audioName.takeIf { it.isNotBlank() },
        ).joinToString("  ")
        if (tail.isNotBlank()) add(tail)
    }
    val cacheLines = buildList {
        val secs = cacheSecs.toDoubleOrNull()?.roundToOneDecimal() ?: cacheSecs
        val kbps = cacheKbps.toDoubleOrNull()?.let { (it / 1024).roundToOneDecimal() } ?: cacheKbps
        if (cacheSecs.isNotBlank() || cacheKbps.isNotBlank()) {
            add(listOfNotNull(
                "${secs}s".takeIf { cacheSecs.isNotBlank() },
                "${kbps} kbps".takeIf { cacheKbps.isNotBlank() },
            ).joinToString("  "))
        }
    }
    val syncLines = buildList {
        val asy = avSync.toDoubleOrNull()?.roundToOneDecimal() ?: avSync
        if (avSync.isNotBlank() || drops.isNotBlank()) {
            add(listOfNotNull(
                "${asy}s".takeIf { avSync.isNotBlank() },
                "drops: $drops".takeIf { drops.isNotBlank() },
            ).joinToString("  "))
        }
    }
    return StreamInfoSnapshot(videoLines, audioLines, cacheLines, syncLines)
}

private fun MPVPlayerView.readSubtitleTracks(): List<SubtitleTrack> {
    val m = mpv
    val countStr = m.getPropertyString("track-list/count") ?: return emptyList()
    val count = countStr.toIntOrNull() ?: return emptyList()
    val out = mutableListOf<SubtitleTrack>()
    for (i in 0 until count) {
        val type = m.getPropertyString("track-list/$i/type").orEmpty()
        if (type != "sub") continue
        val id = m.getPropertyString("track-list/$i/id")?.toIntOrNull() ?: continue
        val title = m.getPropertyString("track-list/$i/title").orEmpty()
        val lang = m.getPropertyString("track-list/$i/lang").orEmpty()
        out += SubtitleTrack(id = id, title = title, lang = lang)
    }
    return out
}

private fun MPVPlayerView.readCurrentSid(): Int? {
    val raw = mpv.getPropertyString("sid") ?: return null
    if (raw == "no" || raw == "auto") return null
    return raw.toIntOrNull()
}

private fun String?.orEmpty(): String = this ?: ""
private fun String?.orZero(): String = if (this.isNullOrBlank()) "" else this
private fun Double.roundToOneDecimal(): String = String.format(Locale.US, "%.1f", this)
