package com.aeriotv.android.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aeriotv.android.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 MediaSessionService replacing the hand-rolled
 * [PlaybackService] (~280 lines of MediaSessionCompat + manual
 * NotificationCompat.MediaStyle + MediaButtonReceiver glue).
 *
 * Media3 handles all of this automatically:
 *   - Notification with current MediaItem.mediaMetadata (title /
 *     artist / artworkUri) -- no per-channel intent extras needed.
 *   - Lock-screen artwork.
 *   - Play / pause / skip / stop transport controls in shade +
 *     QS "now playing" tile + lock screen.
 *   - Bluetooth AVRCP headset buttons routed straight to the player.
 *   - Android Auto / Wear / Cast surfaces wired through the session.
 *   - Activity launcher intent on tap (handled by setSessionActivity).
 *   - Foreground service lifecycle: the service stays foreground
 *     for as long as the player is in a non-IDLE / non-STOPPED state
 *     and there's an active playback. We don't need to manage
 *     startForegroundCompat / stopForeground ourselves.
 *
 * The MediaSession wraps the SAME ExoPlayer instance that's
 * mounted in PersistentExoWindow, so foreground playback in
 * PlayerScreen and background notification + lock-screen controls
 * are reading / driving identical state. No two-player coordination
 * problem.
 *
 * Mini-player resume flow: tapping the notification routes to
 * MainActivity (SINGLE_TOP) which restores the existing player and
 * its surface binding. Same contract as the libmpv path.
 *
 * The legacy PlaybackService.startBackground / stop entry points
 * are kept as no-ops + replaced by direct intent calls on this
 * class so the callers in PlayerScreen / Navigation don't need to
 * change. Task #67 deletes the legacy file when libmpv goes.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AerioMediaPlaybackService : MediaSessionService() {

    @Inject lateinit var exoHolder: AerioExoPlayerHolder

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = exoHolder.acquireOrCreate(this)

        // Tap-to-resume PendingIntent. SINGLE_TOP so the running
        // PlayerScreen is restored via onNewIntent rather than
        // rebuilt -- matches the legacy PlaybackService behaviour
        // that protected against the "back to launcher, back to
        // app, fresh activity" recreation cycle.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val launchPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(launchPi)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop the service when the task is swiped away from
        // Recents -- otherwise we leak the foreground notification.
        // The user dismissed the app; honour it.
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            // Do NOT release the player here -- it's owned by
            // AerioExoPlayerHolder and may still be in use by the
            // foreground PlayerScreen.
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** Start the service as foreground. Caller is typically
         *  PlayerScreen's phone-Back path when promoting to a
         *  background-audio mini. Replaces the legacy
         *  PlaybackService.startBackground call. */
        fun startBackground(context: android.content.Context) {
            val intent = Intent(context, AerioMediaPlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the service. Caller is typically PlayerScreen's
         *  X-close or the mini-player Dismiss path. */
        fun stop(context: android.content.Context) {
            val intent = Intent(context, AerioMediaPlaybackService::class.java)
            context.stopService(intent)
        }
    }
}
