package com.aeriotv.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decides whether a playlist's LAN URL is usable by ASKING THE SERVER instead
 * of guessing from the WiFi SSID. The old SSID approach needed fine-location
 * permission, location services on, AND a per-device list of saved home
 * networks (never synced, so every fresh install silently fell back to WAN,
 * the user report behind this class). It also could not work at all on
 * Ethernet-connected TV boxes, and never matched when reaching home over VPN.
 *
 * A reachability probe has none of those problems: HEAD the LAN base URL with
 * a short timeout. ANY HTTP response (including 401/404/405) proves the server
 * is reachable at that address; only connect failures and timeouts mean WAN.
 *
 * Verdicts are cached per URL and refreshed:
 *  - on demand, the first time a URL is routed after process start,
 *  - whenever the default network changes (ConnectivityManager callback),
 *  - explicitly after a playlist edit ([refresh]).
 */
@Singleton
class LanReachability @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val probeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reprobeJob: Job? = null

    init {
        // Default-network callback: fires on WiFi<->cellular switches, VPN
        // up/down, and Ethernet plug events. Registration needs no permission.
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
            })
        }.onFailure { Log.w(TAG, "Network callback registration failed", it) }
    }

    /**
     * Cached verdict for [lanUrl], probing on first use. Suspends up to
     * [PROBE_TIMEOUT_MS] only when no verdict is cached yet (first routing
     * decision after launch or after a network change).
     */
    suspend fun isReachable(lanUrl: String): Boolean {
        val key = lanUrl.trimEnd('/')
        verdicts[key]?.let { return it }
        return refresh(key)
    }

    /** Force a fresh probe of [lanUrl] (playlist edits, network changes). */
    suspend fun refresh(lanUrl: String): Boolean {
        val key = lanUrl.trimEnd('/')
        // One probe at a time per process: concurrent first-routing calls for
        // the same URL collapse onto a single network round-trip.
        probeMutex.withLock {
            verdicts[key]?.let { cached -> if (cached) return cached }
            val reachable = probe(key)
            verdicts[key] = reachable
            Log.i(TAG, "LAN probe $key -> ${if (reachable) "reachable (using LAN)" else "unreachable (using WAN)"}")
            return reachable
        }
    }

    private fun onNetworkChanged() {
        val known = verdicts.keys.toList()
        verdicts.clear()
        if (known.isEmpty()) return
        // Small debounce: routing tables settle a beat after onAvailable.
        reprobeJob?.cancel()
        reprobeJob = scope.launch {
            delay(500)
            known.forEach { url -> runCatching { refresh(url) } }
        }
    }

    private suspend fun probe(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(baseUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            try {
                // Reaching this line at all means TCP + HTTP succeeded; the
                // status code (401, 404, 405...) is irrelevant to routing.
                conn.responseCode
                true
            } finally {
                conn.disconnect()
            }
        } catch (t: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "LanReachability"
        // A LAN server answers in single-digit milliseconds; 1.5s is a wide
        // margin that still keeps the worst-case first-route delay short.
        const val PROBE_TIMEOUT_MS = 1_500
    }
}
