package com.aeriotv.android.feature.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView

/**
 * AerioTV's libmpv view. Ports iOS Aerio/App/MPVPlayerView.swift option sequence
 * (lines 3055-3800) to Android. Every option here has a source-line reference to
 * the iOS file so parity drift can be audited.
 *
 * @param isLive Whether this view will play live streams. Switches a few demuxer/cache
 *               options between live (low-latency) and VOD (smooth-resume) values.
 *               iOS MPVPlayerView.swift line 3194/3326.
 */
class MPVPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var isLive: Boolean = true,
) : BaseMPVView(context, attrs) {

    private val tag = "MPVPlayerView"

    init {
        // Black background while the underlying Surface waits for its
        // first decoded frame. SurfaceView punches a hole through the View
        // hierarchy to draw its surface, so during the gap between
        // SurfaceHolder.surfaceCreated and the first frame arriving, the
        // surface is whatever the GPU left in that buffer last (often the
        // host Box's background, which used to be navy app-background --
        // hence the "blue box" the user reported). Setting black on the
        // view itself + on the holder format guarantees the pre-first-
        // frame visual is solid black. Mirrors iOS PlayerView's black
        // backgroundColor + AVSampleBufferDisplayLayer.backgroundColor.
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    /**
     * Absolute path to the Mozilla CA bundle copied from the AAR's assets/cacert.pem
     * to the app's filesDir by `Utils.copyAssets`. PlayerScreen sets this BEFORE
     * calling [initialize] so initOptions can wire it as the TLS root store.
     * Without it, mbedTLS rejects every HTTPS handshake - Android does not expose
     * its system trust store in a format libmpv can read.
     */
    var caFilePath: String? = null

    /**
     * Optional HTTP headers to attach to every fetch the underlying ffmpeg/mpv stream
     * stack makes (segment requests, manifests, etc.). Used for Dispatcharr API key
     * auth on `/proxy/ts/stream/...` and any future XC/server-specific custom headers.
     * Mirrors iOS MPVPlayerView.swift lines 3516-3527 (`user-agent` + `http-header-fields`).
     */
    var httpHeaders: Map<String, String> = emptyMap()

    /**
     * User-chosen stream buffer in milliseconds, from Settings -> Network -> Buffer Size.
     * Mirrors iOS MPVPlayerView.swift line 3681 -> 3705. Applied at postInitOptions as
     * `cache-secs` + `demuxer-readahead-secs`. Live streams enforce a 5s minimum
     * regardless of user choice to absorb audio-decoder underruns.
     */
    var cachingMs: Int = 1_000

    /**
     * Pre-init options (iOS lines 3055-3527, before mpv_initialize).
     * Order matters for some options (hwdec must be set before init).
     */
    override fun initOptions() {
        val m = mpv

        m.setOptionString("subs-match-os-language", "yes")  // iOS 3107
        m.setOptionString("subs-fallback", "yes")           // iOS 3108

        m.setOptionString("profile", "fast")                // iOS 3113 — disable expensive post-processing for mobile

        // Hardware decode. Android analog of iOS videotoolbox-copy (iOS 3157).
        // Copy path: decoder writes to a CPU-readable buffer, app uploads to GL.
        // Avoids the v1.7.x 10-bit HEVC blue-screen seen with zero-copy paths.
        m.setOptionString("hwdec", "mediacodec-copy")
        // iOS 3172. Allow 90 consecutive decode failures before falling back to software.
        // Live MPEG-TS mid-GOP joins can need ~3s at 30fps to hit the next keyframe.
        m.setOptionString("hwdec-software-fallback", "90")
        m.setOptionString("vd-lavc-threads", "1")           // iOS 3183 — parser thread safety

        // Cache pause behavior. iOS 3194.
        m.setOptionString("cache-pause-wait", if (isLive) "0" else "2")

        m.setOptionString("initial-audio-sync", "no")       // iOS 3235
        m.setOptionString("vd-lavc-fast", "yes")            // iOS 3236
        m.setOptionString("vd-lavc-skiploopfilter", "nonref") // iOS 3237

        // HTTP reconnect on transient drops. iOS 3240-3241.
        m.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2"
        )
        m.setOptionString("network-timeout", "30")          // iOS 3256 — cold-start TLS/OCSP headroom

        // TLS root store (Android-specific; iOS uses SecureTransport, no equivalent option).
        // mpv-android-lib bundles Mozilla cacert.pem in its AAR assets; Utils.copyAssets
        // places it in filesDir at init. Without this, mbedtls fails every HTTPS handshake.
        caFilePath?.let { path ->
            m.setOptionString("tls-ca-file", path)
            m.setOptionString("tls-verify", "yes")
        }

        // HTTP headers for the stream URL (iOS lines 3516-3527). User-Agent is its own
        // option; everything else goes as one CRLF-separated string under
        // `http-header-fields`. Crucial for Dispatcharr API-key proxy URLs and any
        // server that requires Authorization on /proxy/ts/stream/<uuid>.
        val userAgent = httpHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (userAgent != null) {
            m.setOptionString("user-agent", userAgent)
        }
        val otherHeaders = httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (otherHeaders.isNotEmpty()) {
            val packed = otherHeaders.entries.joinToString(separator = "\r\n") { (k, v) -> "$k: $v" }
            m.setOptionString("http-header-fields", packed)
        }

        if (isLive) {
            // Live-only demuxer tuning. iOS 3326-3332.
            // analyzeduration=1.5 (was 0.1; 100ms not enough for 30fps FPS lock — v1.7.0 fix).
            m.setOptionString("demuxer-lavf-analyzeduration", "1.5")
            // probesize=1MB (was 32K; needed for UHD HEVC SPS/PPS/VPS NALs).
            m.setOptionString("demuxer-lavf-probesize", "1048576")
            m.setOptionString("cache-pause-initial", "no")
            m.setOptionString("hls-bitrate", "max")
            m.setOptionString("video-latency-hacks", "yes")
        }

        // Frame-drop + A/V sync. iOS 3373-3375.
        m.setOptionString("framedrop", "vo")
        m.setOptionString("video-sync", "audio")
        m.setOptionString("video-timing-offset", "0")

        // Black-flash mitigation. iOS 3422-3423.
        m.setOptionString("demuxer-lavf-o", "fflags=+discardcorrupt")

        Log.i(tag, "initOptions complete (isLive=$isLive)")
    }

    /**
     * Post-init options (iOS lines 3761-3800, after mpv_initialize via property API).
     * Properties can only be set on an initialized MPV instance.
     */
    override fun postInitOptions() {
        val m = mpv

        // iOS re-asserts demuxer options as properties after init (belt and suspenders).
        m.setPropertyString("demuxer-lavf-probe-info", "auto")          // iOS 3761
        m.setPropertyString("demuxer-lavf-analyzeduration", "1.5")      // iOS 3762
        m.setPropertyString("demuxer-lavf-probesize", "1048576")        // iOS 3763

        m.setPropertyString("hr-seek-framedrop", "yes")                 // iOS 3791

        // Upgrade frame-drop now that init is complete. iOS 3771.
        m.setPropertyString("framedrop", "decoder+vo")

        // Cache window. Mirrors iOS MPVPlayerView.swift:3679+ — user-chosen buffer
        // size (set via [cachingMs] before initialize()) is the floor, with a 5s
        // live-minimum enforcement so audio-output underruns don't freeze video.
        val effectiveMs = if (isLive) maxOf(cachingMs, 5_000) else cachingMs
        val effectiveSecs = effectiveMs / 1000.0
        m.setPropertyString("cache", "yes")
        m.setPropertyString("demuxer-readahead-secs", String.format(java.util.Locale.US, "%.1f", effectiveSecs))
        m.setPropertyString("cache-secs", String.format(java.util.Locale.US, "%.1f", effectiveSecs))

        Log.i(tag, "postInitOptions complete")
    }

    /**
     * Properties to observe via MPV's property-change event stream.
     * iOS observes time-pos, duration, pause, volume, etc. for SwiftUI state binding.
     * Phase 1a: observe the minimum needed to verify playback.
     */
    override fun observeProperties() {
        val m = mpv
        // Format codes from libmpv mpv_format enum.
        // 1=NODE, 2=STRING, 3=OSD_STRING, 4=FLAG, 5=INT64, 6=DOUBLE
        val MPV_FORMAT_FLAG = 3
        val MPV_FORMAT_INT64 = 4
        val MPV_FORMAT_DOUBLE = 5
        val MPV_FORMAT_STRING = 1

        m.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
        m.observeProperty("duration", MPV_FORMAT_DOUBLE)
        m.observeProperty("pause", MPV_FORMAT_FLAG)
        m.observeProperty("eof-reached", MPV_FORMAT_FLAG)
        m.observeProperty("video-format", MPV_FORMAT_STRING)
        m.observeProperty("audio-codec-name", MPV_FORMAT_STRING)
        m.observeProperty("width", MPV_FORMAT_INT64)
        m.observeProperty("height", MPV_FORMAT_INT64)

        Log.i(tag, "observeProperties complete")
    }
}
