package com.aeriotv.android.core.timeshift

import android.util.Log
import com.aeriotv.android.core.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live Rewind session arbiter (task #143).
 *
 * Owns the active [TimeshiftWriter] and the UI-facing state. The
 * shared live player's tee (see [TeeDataSource] wiring in
 * AerioExoPlayerHolder) mirrors bytes into [activeWriter] whenever one
 * exists; PlayerScreen starts/stops sessions around fullscreen live
 * playback, per the locked v1 scope: fullscreen single-stream only,
 * multiview tiles / mini-player / PiP stay pure live.
 *
 * P1 wires enable + depth prefs; retention and budget ride interim
 * defaults until the P2 settings land.
 */
@Singleton
class TimeshiftController @Inject constructor(
    private val store: TimeshiftBufferStore,
    private val prefs: AppPreferences,
) {
    companion object {
        private const val TAG = "TimeshiftController"
        private const val DEFAULT_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_BUDGET_BYTES = 10L * 1024 * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeWriter: TimeshiftWriter? = null
        private set

    data class State(
        /** A buffer session is rolling for the current live channel. */
        val buffering: Boolean = false,
        /** Playback is on the buffer (paused/rewound) rather than the direct stream. */
        val timeshifting: Boolean = false,
        /** Oldest rewindable wall time. */
        val tailWallMs: Long = 0,
        /** Newest buffered wall time (the live edge). */
        val headWallMs: Long = 0,
        /** When timeshifting: wall time playback entered the buffer at. */
        val baseWallMs: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Begin buffering [channelId] if Live Rewind is enabled. Called by
     * PlayerScreen when fullscreen live playback starts (and on channel
     * change, which implicitly ends the previous session).
     */
    fun onFullscreenLiveStarted(channelId: String, channelName: String) {
        scope.launch {
            if (!prefs.liveRewindEnabled.first()) return@launch
            val depthMin = prefs.liveRewindDepthMinutes.first()
            stopSessionInternal()
            val writer = store.startSession(
                channelId = channelId,
                channelName = channelName,
                depthMs = depthMin * 60_000L,
                retentionMs = DEFAULT_RETENTION_MS,
                budgetBytes = DEFAULT_BUDGET_BYTES,
            )
            activeWriter = writer
            _state.value = State(
                buffering = true,
                tailWallMs = writer.sessionStartMs,
                headWallMs = writer.sessionStartMs,
            )
            Log.i(TAG, "buffering started for $channelName")
        }
    }

    /**
     * Stop buffering (leaving fullscreen live: channel close, minimize,
     * multiview, PiP handoff). Buffered data stays on disk until the
     * retention reaper ages it out.
     */
    fun onFullscreenLiveStopped() {
        stopSessionInternal()
        _state.value = State()
    }

    /** Playback switched onto the buffer at [atWallMs]. */
    fun onEnterTimeshift(atWallMs: Long) {
        val w = activeWriter ?: return
        _state.value = _state.value.copy(
            timeshifting = true,
            baseWallMs = atWallMs,
            tailWallMs = w.tailWallMs,
            headWallMs = w.headWallMs,
        )
    }

    /** Playback returned to the direct live stream. */
    fun onGoLive() {
        _state.value = _state.value.copy(timeshifting = false, baseWallMs = 0)
    }

    /** Poll tick from the chrome while visible: refresh window bounds. */
    fun refreshWindow() {
        val w = activeWriter ?: return
        _state.value = _state.value.copy(tailWallMs = w.tailWallMs, headWallMs = w.headWallMs)
    }

    private fun stopSessionInternal() {
        activeWriter?.close()
        activeWriter = null
    }
}
