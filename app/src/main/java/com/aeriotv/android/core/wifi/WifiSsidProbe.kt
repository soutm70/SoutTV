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
 * Permission model:
 *   - API 26-28: ACCESS_WIFI_STATE alone is enough.
 *   - API 29+ (including 33+): ACCESS_FINE_LOCATION required at runtime AND
 *     location services globally enabled, or the connected SSID is redacted to
 *     "<unknown ssid>". NEARBY_WIFI_DEVICES does NOT help here -- that
 *     permission gates Wi-Fi scanning / peer APIs, not reading the SSID of the
 *     network you're already connected to.
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

    /** Whether the OS permission needed to read the connected SSID is granted. */
    fun hasPermission(context: Context): Boolean {
        // API 28 and below: only ACCESS_WIFI_STATE (normal-protection, granted
        // at install) is needed to read the SSID.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        // API 29+ (incl. 33+): the connected SSID is only readable with
        // ACCESS_FINE_LOCATION. NEARBY_WIFI_DEVICES does not unlock it.
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** The permission to request based on SDK level. Null when no runtime
     * permission is needed (pre-Q). */
    fun requiredPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            null
        }
}
