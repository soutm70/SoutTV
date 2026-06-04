package com.aeriotv.android.core.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.aeriotv.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3 ExoPlayer holder mirroring [MPVPlayerHolder]'s lifetime contract.
 * Hoists ONE [ExoPlayer] instance out of any single composable lifecycle so
 * the underlying codec + audio renderer survives PlayerScreen <-> mini
 * transitions and channel switches. Without this, every nav transition
 * tears the player down and the next open costs a fresh MediaCodec
 * allocation + DataSource warm-up.
 *
 * Pattern (intentionally identical to MPVPlayerHolder so PlayerScreen
 * doesn't need to know which player is mounted):
 *   - PersistentExoWindow.factory calls [acquireOrCreate] on first
 *     composition. Subsequent calls (after back-out + resume) return
 *     the same ExoPlayer reference; the caller just rebinds the
 *     PlayerView's surface to it.
 *   - AndroidView's onRelease calls [detach] instead of release(),
 *     leaving ExoPlayer alive while surface is unparented.
 *   - X-close goes through [destroy] which releases the player.
 *
 * Live TV scaffold for the Media3 migration. VOD, MediaSession, and
 * multiview are subsequent tasks (#62, #63, #64).
 *
 * Threading: all entry points expect main thread (ExoPlayer's
 * `Looper.getMainLooper()` requirement).
 */
@OptIn(UnstableApi::class)
@Singleton
class AerioExoPlayerHolder @Inject constructor() {

    var player: ExoPlayer? = null
        private set

    /** Most-recent channel id played, so a resuming PlayerScreen knows
     *  whether to skip the setMediaItem re-init. */
    var currentChannelId: String? = null

    /** Currently-applied custom HTTP headers, replayed onto the
     *  DataSource.Factory each time we build a MediaSource. Dispatcharr
     *  API-key auth lives here. */
    var httpHeaders: Map<String, String> = emptyMap()

    /**
     * Dynamic HTTP DataSource.Factory used ONLY by the player's MediaSource
     * factory, i.e. the Android Auto path where a MediaController calls
     * setMediaItems(uri) and the player resolves the source itself. It reads
     * [httpHeaders] fresh on every createDataSource so the active source's
     * Dispatcharr key rides along. The foreground path bypasses this entirely
     * (it calls player.setMediaSource(buildMediaSource(...)) directly), so this
     * factory never affects PlayerScreen playback.
     */
    private val autoDataSourceFactory = DataSource.Factory {
        val f = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        val h = httpHeaders
        if (h.isNotEmpty()) {
            f.setDefaultRequestProperties(h)
            h.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value?.let(f::setUserAgent)
        }
        f.createDataSource()
    }

    /**
     * Return the active ExoPlayer, creating it once on first call.
     * The caller is expected to bind it to a PlayerView via
     * `playerView.player = holder.acquireOrCreate(...)`.
     */
    fun acquireOrCreate(
        context: Context,
    ): ExoPlayer {
        player?.let { return it }
        Log.i(TAG, "Creating fresh ExoPlayer in holder")

        // RenderersFactory: enable SW fallback (Media3 equivalent of
        // mpv's hwdec-software-fallback). On the rare codec that fails
        // HW init the renderer transparently retries SW. The QTI HEVC-
        // in-TS bug we hit on libmpv is fixed at this layer: Media3's
        // MediaCodecRenderer pulls SPS/VPS/PPS out of in-band Annex-B
        // NALs before MediaCodec.configure, so we don't even need the
        // fallback for that case -- HW just works.
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // LoadControl: live-stream-friendly buffer durations. The
        // defaults (50s min, 50s max for VOD) over-buffer for live and
        // delay channel-tap response. Mirror our libmpv `cache-secs=5`
        // for live: keep ~2.5s buffered so a brief network hiccup
        // doesn't stall, but don't wait long before showing first frame.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 5_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val fresh = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            // Header-aware + TS-aware MediaSource factory for the Android Auto
            // path (a controller's setMediaItems(uri) -> the player resolves the
            // source itself). The foreground path bypasses this with
            // setMediaSource(buildMediaSource(...)), so this only governs Auto.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    autoDataSourceFactory,
                    DefaultExtractorsFactory().setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT),
                ),
            )
            // Persistent-view architecture: handleAudioBecomingNoisy
            // pauses on headphone unplug. This is Media3's built-in
            // equivalent of the audio focus handling we hand-rolled
            // for MPV.
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(LoggingPlayerListener)
                // Debug-only rich diagnostics firehose (codec / hwdec path,
                // input format changes, dropped frames, audio underruns) -- the
                // Android analog of iOS's libmpv log bridge. Read with
                // `adb logcat -s AerioPlayerDiag`.
                if (BuildConfig.DEBUG) addAnalyticsListener(DiagnosticAnalyticsListener)
                // Repeat off for live; setRepeatMode(REPEAT_MODE_ONE) is
                // a VOD concern.
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }

        player = fresh
        return fresh
    }

    /**
     * Build a MediaSource appropriate to the URL + apply the current
     * HTTP headers. The factory is rebuilt each time so the latest
     * headers (Dispatcharr API key, custom User-Agent) ride along.
     *
     * Optional metadata (channel name / program / logo) is attached
     * to the MediaItem so MediaSessionService can render its
     * notification + lock-screen art automatically. We mirror the
     * iOS NowPlayingManager fields here.
     */
    fun buildMediaSource(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
    ): MediaSource {
        val dataSourceFactory = httpDataSourceFactory()

        // Force-route raw .ts URLs through ProgressiveMediaSource +
        // TsExtractor. Without this, DefaultMediaSourceFactory looks at
        // the file extension and might mis-identify or fall through to
        // a generic path that doesn't know how to extract HEVC SPS/VPS/
        // PPS from MPEG-TS in-band NAL units.
        //
        // Dispatcharr serves channels as
        //   http://<host>:<port>/proxy/ts/stream/<uuid>
        // which has no extension. We detect raw TS by URL shape AND let
        // DefaultMediaSourceFactory handle .m3u8 (HLS) / .mpd (DASH) /
        // .mp4 (progressive) on its own.
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(title.orEmpty().ifBlank { url })
            .setMediaMetadata(mediaMetadata)
            .build()
        return when {
            isRawTsUrl(url) -> {
                // SINGLE_PMT is what HlsMediaSource uses internally and
                // what nearly every IPTV provider delivers: one program,
                // one PMT, one video PID, one or more audio PIDs.
                // MULTI_PMT is for mux'd transports with sibling programs
                // (BBC HD vs SD on the same TS) which Dispatcharr / Xtream
                // proxies never deliver.
                //
                // No additional FLAG_* on Media3 1.4 -- the only one
                // available is FLAG_EMIT_RAW_SUBTITLE_DATA which we leave
                // off (subtitle handling is task #66 and the parser
                // factory route is cleaner anyway).
                val extractorsFactory = DefaultExtractorsFactory()
                    .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                    .createMediaSource(mediaItem)
            }
            url.endsWith(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    /**
     * Set the media item + start loading. Equivalent of MPV's
     * mpv.command("loadfile", url). Pass [title] / [subtitle] /
     * [artworkUri] for the MediaSession notification + lock-screen
     * art.
     */
    fun playUrl(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
    ) {
        val p = player ?: run {
            Log.w(TAG, "playUrl called before acquireOrCreate")
            return
        }
        // Foreground playback wants video; re-enable it in case an Android Auto
        // session previously dropped the video track on this shared player.
        setVideoTrackEnabled(true)
        val source = buildMediaSource(url, title, subtitle, artworkUri)
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun httpDataSourceFactory(): DataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        // Apply Dispatcharr API-key / custom User-Agent. Headers are
        // applied verbatim; the User-Agent header (if present) replaces
        // the default.
        if (httpHeaders.isNotEmpty()) {
            factory.setDefaultRequestProperties(httpHeaders)
            httpHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.let(factory::setUserAgent)
        }
        return factory
    }

    private fun isRawTsUrl(url: String): Boolean {
        if (url.endsWith(".ts", ignoreCase = true)) return true
        // Dispatcharr / Xtream proxy URLs that have no file extension
        // but ARE raw MPEG-TS. The path shape is the strongest signal:
        //   /proxy/ts/stream/<uuid>
        //   /live/<user>/<pass>/<id>.ts
        //   /stream/<id>.ts
        if (url.contains("/proxy/ts/", ignoreCase = true)) return true
        if (url.contains("/live/", ignoreCase = true) && !url.contains(".m3u8")) return true
        return false
    }

    /**
     * Composable-unmount hook. Does NOT release ExoPlayer; the
     * persistent-view architecture keeps it alive across screen
     * transitions. Media3's setVideoSurface(null) cleanly releases
     * the surface binding without tearing down decode state.
     */
    fun detach() {
        val p = player ?: return
        p.setVideoSurface(null)
    }

    /** Stop playback without releasing the player. Used by the X-close
     *  and the mini's 3rd-Back dismiss. Equivalent of MPV's
     *  command("stop"). */
    fun stop() {
        val p = player ?: return
        currentChannelId = null
        p.stop()
        p.clearMediaItems()
    }

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    fun isPaused(): Boolean = player?.playWhenReady?.not() ?: true

    /**
     * Enable / disable the video track on the shared player. Android Auto plays
     * audio-only (no video on the car screen while driving), so the Auto session
     * disables video to avoid decoding frames with no surface; the foreground
     * PlayerScreen re-enables it via [playUrl]. Must be called on the main
     * thread (ExoPlayer requirement).
     */
    fun setVideoTrackEnabled(enabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Full teardown for the X-close button. Releases the codec,
     *  audio renderer, and DataSource. Next acquire creates fresh. */
    fun destroy() {
        val p = player ?: return
        player = null
        currentChannelId = null
        try {
            p.removeListener(LoggingPlayerListener)
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", t)
        }
    }

    private object LoggingPlayerListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val label = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(TAG, "ExoPlayer state -> $label")
        }
    }

    /**
     * Debug-only player diagnostics, the Android analog of iOS's libmpv log
     * bridge ([MPV-DIAG]): the chosen decoder (hwdec path, e.g.
     * c2.qti.avc.decoder), input format changes, dropped frames, audio
     * underruns, and video size. Registered only under BuildConfig.DEBUG
     * (mirrors iOS's `#if DEBUG` gate); tagged for `adb logcat -s AerioPlayerDiag`.
     */
    private object DiagnosticAnalyticsListener : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG_DIAG, "video decoder -> $decoderName (init ${initializationDurationMs}ms)")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG_DIAG, "audio decoder -> $decoderName")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG_DIAG,
                "video format -> ${format.sampleMimeType} ${format.width}x${format.height} " +
                    "@${format.frameRate}fps ${format.bitrate}bps codecs=${format.codecs}",
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG_DIAG,
                "audio format -> ${format.sampleMimeType} ${format.sampleRate}Hz " +
                    "${format.channelCount}ch ${format.bitrate}bps",
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            Log.w(TAG_DIAG, "dropped $droppedFrames frames over ${elapsedMs}ms")
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            Log.w(
                TAG_DIAG,
                "audio underrun: bufferMs=$bufferSizeMs elapsedSinceFeed=${elapsedSinceLastFeedMs}ms",
            )
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: VideoSize,
        ) {
            Log.i(TAG_DIAG, "video size -> ${videoSize.width}x${videoSize.height}")
        }
    }

    companion object {
        private const val TAG = "AerioExoPlayer"
        private const val TAG_DIAG = "AerioPlayerDiag"
    }
}
