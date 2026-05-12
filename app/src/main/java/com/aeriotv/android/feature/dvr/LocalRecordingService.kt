package com.aeriotv.android.feature.dvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Foreground service that downloads a Dispatcharr proxy stream to local
 * storage for a fixed duration. Mirrors iOS LocalRecordingSession behaviour
 * scoped down to "Record from Now" (iOS doesn't support scheduled local
 * recordings either — Android matches that limit).
 *
 * Lifecycle:
 *   1. PlayerScreen/RecordProgramSheet starts the service via [startRecording].
 *   2. onStartCommand promotes the service to foreground with the ongoing
 *      "Recording <title>" notification.
 *   3. A coroutine on the IO dispatcher reads bytes from the proxy URL and
 *      writes them to a file under getExternalFilesDir("Recordings").
 *   4. The coroutine stops at the user-chosen end time OR if the user taps
 *      the notification's Stop action.
 *   5. onDestroy clears the notification + cancels the supervisor scope.
 *
 * Phase 9b-1 surfaces the recording as a notification only; persistence to
 * Room + display in the DVR tab follow in 9b-2. Storage quota + custom
 * output dir follow in 9b-3 / DVR Settings.
 */
@AndroidEntryPoint
class LocalRecordingService : Service() {

    @Inject lateinit var localRecordingDao: LocalRecordingDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var currentChannelName: String = ""

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
                val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: title
                val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 60 * 60 * 1000L)
                currentChannelName = channelName
                startRecording(streamUrl, title, apiKey, durationMs)
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(streamUrl: String, title: String, apiKey: String, durationMs: Long) {
        if (streamUrl.isBlank()) {
            stopSelf()
            return
        }
        ensureNotificationChannel()
        val notif = buildNotification(title, "Recording…")
        startForegroundCompat(notif)

        recordingJob = scope.launch {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val outDir = File(getExternalFilesDir(null), "Recordings").apply { mkdirs() }
            val outFile = File(outDir, "$ts-$safeTitle.ts")
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + durationMs
            Log.i(TAG, "Recording -> $outFile until ${Date(deadline)}")
            var status = "failed"
            runCatching {
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("X-API-Key", apiKey)
                    .header("Authorization", "ApiKey $apiKey")
                    .build()
                okHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} fetching $streamUrl")
                    }
                    val body = response.body ?: throw IllegalStateException("empty body")
                    body.byteStream().use { input ->
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive && System.currentTimeMillis() < deadline) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                }
                status = if (System.currentTimeMillis() >= deadline) "completed" else "stopped"
                Log.i(TAG, "Recording $status: ${outFile.length()} bytes")
            }.onFailure { t ->
                Log.w(TAG, "Recording aborted", t)
            }
            // Persist the row so the DVR tab can surface it. Skip persistence
            // if no bytes were written (file would mislead the user).
            if (outFile.exists() && outFile.length() > 0L) {
                runCatching {
                    localRecordingDao.insert(
                        LocalRecordingEntity(
                            channelName = currentChannelName.ifBlank { title },
                            title = title,
                            filePath = outFile.absolutePath,
                            startedAt = startedAt,
                            endedAt = System.currentTimeMillis(),
                            byteSize = outFile.length(),
                            status = status,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Couldn't persist local recording row", it) }
            }
            stopSelf()
        }

        // Also schedule a hard stop just in case the read loop hangs.
        scope.launch {
            delay(durationMs + 5_000L)
            if (recordingJob?.isActive == true) {
                Log.w(TAG, "Recording exceeded duration + 5s grace - force-stopping")
                stopRecording()
            }
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

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

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local recordings",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while AerioTV is recording a channel to local storage."
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LocalRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
    }

    companion object {
        private const val TAG = "LocalRecordingService"
        private const val NOTIF_ID = 0xAE
        private const val CHANNEL_ID = "aeriotv_local_recording"

        const val ACTION_START = "com.aeriotv.android.RECORDING_START"
        const val ACTION_STOP = "com.aeriotv.android.RECORDING_STOP"
        const val EXTRA_STREAM_URL = "streamUrl"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_DURATION_MS = "durationMs"

        fun start(
            context: Context,
            streamUrl: String,
            title: String,
            channelName: String,
            apiKey: String,
            durationMs: Long,
        ) {
            val intent = Intent(context, LocalRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocalRecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
