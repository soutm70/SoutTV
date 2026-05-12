package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.network.DispatcharrWarmupCoordinator
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.player.MpvLibraryWarmup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hilt Application + WorkManager Configuration.Provider so DriveSyncWorker can
 * have its Hilt-provided dependencies (DriveSyncManager, AppPreferences) injected
 * via @HiltWorker. Without the Configuration.Provider hook WorkManager auto-init
 * runs first and constructs workers via the default reflective factory, which
 * doesn't know about Hilt.
 *
 * Also binds the Dispatcharr warmup coordinator to the process-wide lifecycle
 * so JWT refresh runs on every foreground entry, and pipes the
 * `debugLoggingEnabled` DataStore flow into DebugLogger so the file writer
 * flips on/off the moment the user toggles it in Settings -> Developer.
 */
@HiltAndroidApp
class AerioTVApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dispatcharrWarmup: DispatcharrWarmupCoordinator
    @Inject lateinit var debugLogger: DebugLogger
    @Inject lateinit var appPreferences: AppPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        dispatcharrWarmup.bind()
        // Kick off the libmpv process-wide warm-up. Internally defers
        // 800 ms behind launch + runs on a background dispatcher so it
        // never competes with the visible cold-launch sequence for the
        // JNI mutex. iOS does the equivalent via MPVLibraryWarmup.warmUp()
        // (MPVPlayerView.swift lines 39-86); on iOS this single change
        // is the headline reason channel-tap startup feels instant. The
        // first PlayerScreen composition waits up to 5 s for this to
        // complete before its real mpv.create() call, so the user-facing
        // handle pays only loadfile cost instead of the full process-wide
        // codec/protocol/hwdec/mediacodec-bridge registration too.
        MpvLibraryWarmup.start(this)
        appScope.launch {
            appPreferences.debugLoggingEnabled.collectLatest { enabled ->
                debugLogger.setEnabled(enabled)
            }
        }
    }
}
