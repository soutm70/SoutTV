package com.aeriotv.android.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
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
        // Satisfy the API 31+ foreground-service-start deadline IMMEDIATELY.
        //
        // The system requires startForeground() within 5s of
        // startForegroundService() or it kills the process with
        // ForegroundServiceDidNotStartInTimeException. Media3's
        // MediaNotificationManager only calls startForeground() once the
        // player transitions to STATE_READY + isPlaying, which on the
        // Streamer with a 4K HEVC live stream consistently takes longer
        // than 5s (verified crash log: aerio crashed at 14:43:44.564
        // after startForegroundService at 14:43:14.549, almost exactly
        // 30s of the player buffering ch38 before the system gave up).
        //
        // Push a minimal placeholder notification right at onCreate so
        // the deadline is satisfied unconditionally. Media3's media-
        // style notification will replace this the moment the player
        // first reports a playback state, so the user only ever sees
        // the proper "Now playing" row.
        ensureChannel()
        startForegroundCompat(buildPlaceholderNotification())

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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification while AerioTV plays audio in the background."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /** Bare-bones notification used only to satisfy the 5s
     *  startForeground deadline. The real MediaStyle notification is
     *  pushed by Media3's MediaNotificationManager once the player
     *  reports its first playback state. */
    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AerioTV")
            .setContentText("Starting playback...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "aeriotv_background_playback"
        private const val NOTIF_ID = 0xAF

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
