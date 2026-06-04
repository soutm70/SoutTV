package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging

/**
 * Install Ktor request/response logging on a [HttpClient], mirroring iOS's
 * NWHTTP console stream. Debug builds log at INFO (method + URL + status +
 * timing -- no headers or bodies, so no credential or large-body spam);
 * release logs nothing. Every line is still run through [LogSanitizer] before
 * it reaches logcat, so an Xtream path/query credential or an api_key sitting
 * in a URL can never leak. Read with `adb logcat -s AerioNet`.
 */
fun HttpClientConfig<*>.installSanitizedLogging() {
    install(Logging) {
        level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        logger = object : Logger {
            override fun log(message: String) {
                if (BuildConfig.DEBUG) Log.d("AerioNet", LogSanitizer.redact(message))
            }
        }
    }
}
