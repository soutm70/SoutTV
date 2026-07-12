package com.aeriotv.android.core.network

import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches M3U/M3U8 playlists over HTTP(S). Returns raw bytes so the parser can
 * handle UTF-8 / ISO-8859-1 encoding fallback (iOS PlaylistParsers.swift:96-97).
 *
 * Phase 2 keeps this minimal. Phase 3 will add: Dispatcharr/XC custom headers,
 * User-Agent rotation per server, conditional GET via ETag, retry/backoff.
 */
@Singleton
class PlaylistFetcher @Inject constructor() {

    private val client = HttpClient(OkHttp) {
        installSanitizedLogging()
        engine {
            // OkHttp engine config can be expanded later for proxies, interceptors.
        }
        install(HttpTimeout) {
            // Mirror the iOS URLSession model (StreamingAPIs.swift): a generous
            // TOTAL budget for a large XMLTV EPG / M3U payload (10K+ channel
            // servers run to tens of MB) with a short INACTIVITY timeout so a
            // dead host still fails fast. A 60s TOTAL cap truncated big EPG / M3U
            // downloads mid-stream the same way it did the VOD library.
            requestTimeoutMillis = 300_000  // iOS timeoutIntervalForResource = 300
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000    // idle between packets
        }
    }

    suspend fun fetchBytes(
        url: String,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val response: HttpResponse = client.get(url) {
            if (userAgent != null) header("User-Agent", userAgent)
            for ((k, v) in extraHeaders) header(k, v)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "HTTP ${response.status.value} ${response.status.description} from ${LogSanitizer.redactUrl(url)}",
            )
        }
        return response.readRawBytes()
    }

    /** GH #26: stream a large body straight to [dest] in constant memory.
     *  [fetchBytes] materializes the whole payload as ONE allocation, and a
     *  full XC-panel M3U (or a provider XMLTV guide) runs 100-200MB -- the
     *  exact 155MB allocation that OOM'd a 256MB-heap phone while adding a
     *  playlist. The caller owns (and deletes) the file. */
    suspend fun fetchToFile(
        url: String,
        dest: File,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): File = client.prepareGet(url) {
        if (userAgent != null) header("User-Agent", userAgent)
        for ((k, v) in extraHeaders) header(k, v)
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "HTTP ${response.status.value} ${response.status.description} from ${LogSanitizer.redactUrl(url)}",
            )
        }
        response.bodyAsChannel().toInputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        dest
    }
}
