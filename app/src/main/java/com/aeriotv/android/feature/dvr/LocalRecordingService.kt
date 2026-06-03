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
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import com.aeriotv.android.core.preferences.AppPreferences
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
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var playlistDao: com.aeriotv.android.core.data.db.dao.PlaylistDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var currentChannelName: String = ""

    /**
     * Partial wake lock held while a local recording is in flight, gated by
     * the "Keep device awake during recording" DVR setting (default ON, iOS
     * parity). PARTIAL_WAKE_LOCK keeps the CPU running with the screen off so
     * the download read-loop isn't paused by doze; it does NOT keep the
     * screen on. Released in [releaseWakeLock] from the recording coroutine's
     * finally + onDestroy so it can't leak past the recording.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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
            ACTION_DOWNLOAD -> {
                val fileUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
                val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: title
                val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                currentChannelName = channelName
                startDownload(fileUrl, title, apiKey)
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
            // Acquire the wake lock before the read-loop if the user left the
            // "Keep device awake during recording" toggle on. Bounded to
            // duration + 1 min so a hung job can never hold the CPU awake
            // forever; the finally below releases it on the normal path.
            if (runCatching { appPreferences.dvrKeepAwakeOnce() }.getOrDefault(true)) {
                acquireWakeLock(durationMs + 60_000L)
            }

            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val fileName = "$ts-$safeTitle.ts"

            val customUri = runCatching { appPreferences.dvrCustomFolderUriOnce() }.getOrDefault("")
            val sink: RecordingSink = openSink(customUri, fileName)
                ?: run {
                    Log.w(TAG, "Custom folder unwritable - falling back to app default")
                    openSink("", fileName)
                } ?: run {
                    Log.w(TAG, "App default folder unwritable - aborting")
                    stopSelf()
                    return@launch
                }
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + durationMs
            Log.i(TAG, "Recording -> ${sink.displayPath} until ${Date(deadline)}")
            var status = "failed"
            var bytesWritten = 0L
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
                        sink.output.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive && System.currentTimeMillis() < deadline) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                bytesWritten += read
                            }
                            output.flush()
                        }
                    }
                }
                status = if (System.currentTimeMillis() >= deadline) "completed" else "stopped"
                Log.i(TAG, "Recording $status: $bytesWritten bytes")
            }.onFailure { t ->
                Log.w(TAG, "Recording aborted", t)
            }
            // Persist the row so the DVR tab can surface it. Skip persistence
            // if no bytes were written (file would mislead the user).
            val finalBytes = sink.resolveBytes() ?: bytesWritten
            if (finalBytes > 0L) {
                runCatching {
                    localRecordingDao.insert(
                        LocalRecordingEntity(
                            channelName = currentChannelName.ifBlank { title },
                            title = title,
                            filePath = sink.displayPath,
                            startedAt = startedAt,
                            endedAt = System.currentTimeMillis(),
                            byteSize = finalBytes,
                            status = status,
                            playlistId = playlistDao.firstActive()?.id,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Couldn't persist local recording row", it) }
            }
            releaseWakeLock()
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
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Save to Device (audit #43): download a finalized Dispatcharr server
     * recording's /file/ URL to local storage and persist a
     * [LocalRecordingEntity] so it appears in the DVR tab as a Local copy,
     * playable offline. Unlike [startRecording] this reads to EOF (the whole
     * finite file) rather than stopping at a duration deadline. Mirrors iOS
     * downloadDispatcharrRecording (RecordingCoordinator.swift): fetch the
     * playback URL with the source's auth headers, write it to the recordings
     * directory, record the byte size.
     */
    private fun startDownload(fileUrl: String, title: String, apiKey: String) {
        if (fileUrl.isBlank()) {
            stopSelf()
            return
        }
        ensureNotificationChannel()
        startForegroundCompat(buildNotification(title, "Saving to device…"))

        recordingJob = scope.launch {
            // A multi-GB recording can take minutes; hold the CPU awake (gated
            // by the same DVR keep-awake toggle) so doze doesn't stall the
            // read loop. Generous 6h cap as a leak backstop.
            if (runCatching { appPreferences.dvrKeepAwakeOnce() }.getOrDefault(true)) {
                acquireWakeLock(6 * 60 * 60 * 1000L)
            }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val fileName = "$ts-$safeTitle.ts"
            val customUri = runCatching { appPreferences.dvrCustomFolderUriOnce() }.getOrDefault("")
            val sink: RecordingSink = openSink(customUri, fileName)
                ?: openSink("", fileName)
                ?: run {
                    Log.w(TAG, "No writable folder for download - aborting")
                    stopSelf()
                    return@launch
                }
            val startedAt = System.currentTimeMillis()
            Log.i(TAG, "Save to Device -> ${sink.displayPath}")
            var status = "failed"
            var bytesWritten = 0L
            runCatching {
                val request = Request.Builder()
                    .url(fileUrl)
                    .header("X-API-Key", apiKey)
                    .header("Authorization", "ApiKey $apiKey")
                    .build()
                okHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} fetching recording file")
                    }
                    val body = response.body ?: throw IllegalStateException("empty body")
                    body.byteStream().use { input ->
                        sink.output.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break // EOF: whole file pulled
                                output.write(buffer, 0, read)
                                bytesWritten += read
                            }
                            output.flush()
                        }
                    }
                }
                status = if (isActive) "completed" else "stopped"
                Log.i(TAG, "Save to Device $status: $bytesWritten bytes")
            }.onFailure { t ->
                Log.w(TAG, "Save to Device aborted", t)
            }
            val finalBytes = sink.resolveBytes() ?: bytesWritten
            val ok = status == "completed" && finalBytes > 0L
            if (ok) {
                runCatching {
                    localRecordingDao.insert(
                        LocalRecordingEntity(
                            channelName = currentChannelName.ifBlank { title },
                            title = title,
                            filePath = sink.displayPath,
                            startedAt = startedAt,
                            endedAt = System.currentTimeMillis(),
                            byteSize = finalBytes,
                            status = "completed",
                            playlistId = playlistDao.firstActive()?.id,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Couldn't persist downloaded recording", it) }
            }
            releaseWakeLock()
            postDownloadResult(title, ok)
            stopSelf()
        }
    }

    /**
     * Persisted (non-ongoing) notification so the user learns a Save to Device
     * finished after the foreground notification is torn down. Distinct id so
     * it survives stopForeground/stopSelf.
     */
    private fun postDownloadResult(title: String, success: Boolean) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (success) "Saved to device" else "Save to device failed")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        mgr.notify(DOWNLOAD_DONE_NOTIF_ID, notif)
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "AerioTV:LocalRecording",
        ).apply {
            setReferenceCounted(false)
            // Bounded timeout as a safety net -- the finally/stopRecording
            // paths release explicitly, but if the process is killed
            // mid-recording the OS reclaims the lock at the timeout instead
            // of holding the CPU awake indefinitely.
            acquire(timeoutMs)
        }
        Log.i(TAG, "WakeLock acquired (timeout=${timeoutMs}ms)")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching { wl.release() }
                    .onFailure { Log.w(TAG, "WakeLock release failed", it) }
            }
        }
        wakeLock = null
    }

    /**
     * Output sink + a way to query final byte size after close. SAF
     * [DocumentFile] doesn't expose a `File.length()` equivalent without a
     * fresh query, so we delegate the size fetch to the sink so the caller
     * doesn't have to special-case the two storage backends.
     */
    private class RecordingSink(
        val output: java.io.OutputStream,
        val displayPath: String,
        val resolveBytes: () -> Long?,
    )

    /**
     * Resolve the output sink. Empty [customUri] picks the legacy
     * external-files Recordings/ directory; a non-empty URI is treated as a
     * SAF tree URI and we create a new file under it via [DocumentFile].
     * Returns null when the target isn't writable so the caller can fall
     * back to the default location or abort.
     */
    private fun openSink(customUri: String, fileName: String): RecordingSink? {
        if (customUri.isBlank()) {
            return runCatching {
                val outDir = File(getExternalFilesDir(null), "Recordings").apply { mkdirs() }
                val outFile = File(outDir, fileName)
                RecordingSink(
                    output = outFile.outputStream(),
                    displayPath = outFile.absolutePath,
                    resolveBytes = { if (outFile.exists()) outFile.length() else null },
                )
            }.getOrNull()
        }
        return runCatching {
            val treeUri = Uri.parse(customUri)
            val treeRoot = DocumentFile.fromTreeUri(this, treeUri)
                ?: return@runCatching null
            if (!treeRoot.canWrite()) return@runCatching null
            val doc = treeRoot.createFile("video/mp2t", fileName)
                ?: return@runCatching null
            val out = contentResolver.openOutputStream(doc.uri)
                ?: return@runCatching null
            RecordingSink(
                output = out,
                displayPath = doc.uri.toString(),
                resolveBytes = { runCatching { doc.length().takeIf { it > 0 } }.getOrNull() },
            )
        }.getOrNull()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
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
        private const val DOWNLOAD_DONE_NOTIF_ID = 0xAD
        private const val CHANNEL_ID = "aeriotv_local_recording"

        const val ACTION_START = "com.aeriotv.android.RECORDING_START"
        const val ACTION_STOP = "com.aeriotv.android.RECORDING_STOP"
        const val ACTION_DOWNLOAD = "com.aeriotv.android.RECORDING_DOWNLOAD"
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

        /**
         * Save to Device: download a finalized server recording to local
         * storage as a foreground service so it survives backgrounding. The
         * [fileUrl] is the recording's /file/ playback URL; [apiKey]
         * authenticates the fetch (the endpoint is auth-gated on some
         * Dispatcharr deployments).
         */
        fun download(
            context: Context,
            fileUrl: String,
            title: String,
            channelName: String,
            apiKey: String,
        ) {
            val intent = Intent(context, LocalRecordingService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_STREAM_URL, fileUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_API_KEY, apiKey)
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
