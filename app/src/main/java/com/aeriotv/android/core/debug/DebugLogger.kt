package com.aeriotv.android.core.debug

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * File-backed diagnostic logger. Mirrors iOS DebugLogger
 * (Aerio/Shared/DebugLogger.swift): the user toggles logging on from
 * Settings -> Developer, every `debugLog(tag, message)` call (and every
 * routed `Log.i` / `Log.w` from this app's packages) is written into a
 * plain-text file inside the app's private filesDir, the file rotates
 * automatically at 10 MB, the user can view / share / clear it from the
 * same screen.
 *
 * Why a singleton and not just `android.util.Log`: the OS logcat is
 * volatile — Android wipes it on reboot and trims aggressively while the
 * device is foregrounded. Bug reports need a persistent artifact the user
 * can attach to a GitHub issue without paired-device tooling.
 *
 * Threading: the writer runs on a single coroutine pulling from a
 * Channel<String>, so callers never block on disk I/O. The toggle is
 * stored as an AtomicBoolean to skip the queue allocation cheaply when
 * logging is disabled.
 */
@Singleton
class DebugLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabled = AtomicBoolean(false)
    // True while the own-process logcat reader is draining into the file.
    // When set, the explicit `log()` file-write is skipped: the line was
    // already echoed to android.util.Log, so the logcat stream will capture
    // it -- avoids double-writing the handful of debugLog() call sites.
    private val logcatActive = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = Channel.UNLIMITED)
    private var logcatJob: Job? = null

    init {
        scope.launch {
            for (line in queue) {
                writeLine(line)
            }
        }
        // Install a crash handler unconditionally. It only writes when logging
        // is enabled, so there's no cost for users who never turn it on -- but
        // when they do, the next crash leaves a stack trace + a logcat snapshot
        // in the persistent file that survives the process death AND a reboot,
        // which is exactly what a "the app crashes often" bug report needs.
        installCrashHandler()
    }

    /** Top-level on/off; flipped by Settings -> Developer -> Debug Logging. */
    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (value) {
            // Mark the moment logging came on so a future bug report has a
            // visible start-of-session anchor.
            log("DebugLogger", Level.INFO, "Debug logging ENABLED")
            startLogcatCapture()
        } else {
            stopLogcatCapture()
        }
    }

    enum class Level(val tag: String) { VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    fun log(tag: String, level: Level, message: String, throwable: Throwable? = null) {
        // Always echo to logcat so devices on `adb logcat` still see it
        // even when file-logging is off. This is the same dual-emit
        // behavior iOS uses (DebugLogger.swift line 56).
        when (level) {
            Level.VERBOSE -> Log.v(tag, message, throwable)
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }
        if (!enabled.get()) return
        // When the logcat stream is draining this process into the file, the
        // line we just echoed to android.util.Log is already on its way to
        // the file -- skip the explicit write to avoid a duplicate. The
        // explicit path remains the fallback for ROMs that block logcat read.
        if (logcatActive.get()) return
        // Audit task #53: scrub credentials before the line hits the
        // persistent file. The logcat echo above intentionally keeps the
        // raw text so live `adb logcat` debugging is unchanged; only the
        // bug-report file -- the surface a user might share -- is sanitized.
        val safeMessage = LogSanitizer.redact(message)
        val ts = TIMESTAMP_FMT.format(Date())
        val combined = buildString {
            append(ts).append(' ').append(level.tag).append('/').append(tag).append(": ").append(safeMessage)
            if (throwable != null) {
                appendLine()
                throwable.stackTraceToString().lines().forEach {
                    appendLine("    " + LogSanitizer.redact(it))
                }
            }
        }
        queue.trySend(combined)
    }

    fun clearLogs() {
        scope.launch {
            runCatching {
                logFile().writeText("")
                archiveFile().delete()
            }
        }
    }

    /**
     * Stream THIS process's logcat into the file while logging is enabled.
     * The app emits hundreds of `android.util.Log.i/w/e` calls (channel
     * flips, player state, EPG fetches, errors) that the 6 explicit
     * `debugLog()` sites never covered -- so the old file was nearly empty
     * even after the truncation bug. `--pid` (API 24+, minSdk is 26) scopes
     * the read to our own process, which apps are always permitted to read
     * without READ_LOGS. `-v time` gives each line a timestamp + level/tag.
     */
    private fun startLogcatCapture() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch {
            runCatching {
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "--pid=$pid"),
                )
                logcatActive.set(true)
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!enabled.get()) break
                        writeLine(LogSanitizer.redact(line))
                    }
                }
                runCatching { proc.destroy() }
            }.onFailure { t ->
                // Locked-down ROMs can deny logcat read; fall back to the
                // explicit debugLog() file path by clearing the flag.
                Log.w(TAG, "logcat capture unavailable: ${t.message}")
            }
            logcatActive.set(false)
        }
    }

    private fun stopLogcatCapture() {
        logcatActive.set(false)
        logcatJob?.cancel()
        logcatJob = null
    }

    /**
     * Capture an uncaught exception into the persistent file synchronously
     * (the async writer + logcat stream won't flush before the process dies),
     * then chain to whatever handler was installed before us so the system
     * crash dialog / process death still happens normally.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (enabled.get()) {
                    val ts = TIMESTAMP_FMT.format(Date())
                    val f = logFile()
                    val header = buildString {
                        append(ts).append(" E/").append(TAG)
                        append(": FATAL uncaught exception on thread '")
                        append(thread.name).append("'").append(System.lineSeparator())
                        throwable.stackTraceToString().lines().forEach {
                            append("    ").append(LogSanitizer.redact(it))
                                .append(System.lineSeparator())
                        }
                    }
                    // Direct synchronous append -- can't trust the coroutine
                    // queue to run during teardown.
                    runCatching { f.appendText(header) }
                    // Snapshot the in-memory logcat buffer for this process so
                    // the lines leading up to the crash are captured even if
                    // the streaming reader hadn't flushed them yet.
                    runCatching {
                        val pid = android.os.Process.myPid()
                        val dump = Runtime.getRuntime()
                            .exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
                            .inputStream.bufferedReader().readText()
                        f.appendText(
                            "---- logcat snapshot at crash (pid $pid) ----" +
                                System.lineSeparator(),
                        )
                        f.appendText(LogSanitizer.redact(dump))
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Absolute path the user-facing UI uses for the "View / Share" rows. */
    fun logFile(): File {
        val dir = File(context.filesDir, LOGS_DIR).apply { mkdirs() }
        return File(dir, LOG_FILE)
    }

    fun archiveFile(): File = File(File(context.filesDir, LOGS_DIR), ARCHIVE_FILE)

    /** Size of the live log file in bytes. Returns 0 when the file is missing. */
    fun logSizeBytes(): Long {
        val f = logFile()
        return if (f.exists()) f.length() else 0L
    }

    private fun writeLine(line: String) {
        val f = logFile()
        runCatching {
            if (f.length() > MAX_BYTES) {
                runCatching {
                    archiveFile().delete()
                    f.renameTo(archiveFile())
                }
            }
            // BUG (v0.1.6, user report "log file has a single entry"): the
            // previous body opened `f.outputStream()` -- which TRUNCATES the
            // file to zero on every call -- before appending the line, so the
            // file only ever held the most recent line. `appendText` alone
            // opens in append mode and creates the file when missing, which is
            // all we need; the writer coroutine already serialises calls so
            // there's no concurrent-append race.
            f.appendText(line + System.lineSeparator())
        }.onFailure { t ->
            Log.w(TAG, "Failed to append log line: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "DebugLogger"
        const val LOGS_DIR = "logs"
        const val LOG_FILE = "aerio_debug_logs.txt"
        const val ARCHIVE_FILE = "aerio_debug_logs_archive.txt"
        const val MAX_BYTES = 10L * 1024 * 1024  // 10 MB, matches iOS rotation threshold

        // Locale.US + literal pattern is intentional — log timestamps need to
        // be locale-independent so bug-report submitters from different
        // regions produce files that parse identically. iOS uses ISO 8601
        // for the same reason.
        val TIMESTAMP_FMT: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
