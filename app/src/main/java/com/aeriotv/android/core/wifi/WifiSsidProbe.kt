package com.aeriotv.android.core.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Reads the SSID of the device's currently-connected WiFi network so the LAN
 * URL switcher can compare against the user's saved home networks.
 *
 * Permission model (mirrors iOS LocationAuthorization-style gating):
 *   - API 26-28: ACCESS_WIFI_STATE alone is enough.
 *   - API 29-32: ACCESS_FINE_LOCATION required at runtime AND location
 *     services must be globally enabled. Returns null when missing.
 *   - API 33+: NEARBY_WIFI_DEVICES (declared neverForLocation) is the
 *     recommended path; we accept that grant or fall back to fine location
 *     if the user previously granted it.
 *
 * Returns a stripped SSID (no surrounding quotes) or null when unavailable.
 */
object WifiSsidProbe {

    fun currentSsid(context: Context): String? {
        if (!hasPermission(context)) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        val raw = info.ssid?.takeIf { it.isNotBlank() } ?: return null
        if (raw == "<unknown ssid>") return null
        return raw.trim('"').takeIf { it.isNotBlank() }
    }

    /** Whether the OS permission needed to read SSID is currently granted. */
    fun hasPermission(context: Context): Boolean {
        // API 28 and below: only ACCESS_WIFI_STATE which is normal-protection
        // and granted at install.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        // API 33+: NEARBY_WIFI_DEVICES OR fine location works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
            if (nearby) return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** The permission to request based on SDK level. Null when no runtime
     * permission is needed (pre-Q). */
    fun requiredPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }
}
