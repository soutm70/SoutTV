package com.aeriotv.android.core.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal TMDB v3 client, the Android port of iOS `TMDBService`
 * (Aerio Networking/VODService.swift). Used ONLY to (a) validate the user's
 * own free API key and (b) look up a poster image when the playlist provides
 * none. Public image CDN, no auth beyond the user's key.
 *
 * Opt-in + off by default + user-supplied key (see [com.aeriotv.android.core
 * .preferences.AppPreferences.programPostersTmdbEnabled]); the no-bundled-keys
 * rule means there is no default/embedded key anywhere.
 *
 * Credential shapes: TMDB accepts the classic v3 API key (sent as an `api_key`
 * query param) OR the newer v4 read-access token (a JWT sent as a Bearer
 * header). The user may paste either, so we detect the JWT shape and route auth
 * accordingly -- a pasted v4 token sent as api_key would silently 401.
 *
 * SECURITY: a v3 key rides in the request URL's query string, so this client
 * deliberately does NOT install request logging (which would print the URL and
 * leak the key). The only breadcrumb logged is the resolved poster path -- never
 * the key.
 */
@Singleton
class TMDBService @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        // NB: no installSanitizedLogging() here -- see SECURITY note above.
    }

    /**
     * title (lowercased) -> poster path, or "" for a confirmed miss (so a title
     * TMDB has nothing for is not re-queried). Also keyed by "id:movie|tv:<id>"
     * for the exact-id path. Thread-safe; shared across the app.
     */
    private val cache = ConcurrentHashMap<String, String>()

    /** v4 read-access token = a JWT: starts with "eyJ" and has exactly 2 dots. */
    private fun isBearerToken(key: String): Boolean =
        key.startsWith("eyJ") && key.count { it == '.' } == 2

    private suspend fun getJsonOrNull(path: String, query: String, key: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bearer = isBearerToken(key)
                val params = buildList {
                    if (query.isNotEmpty()) add(query)
                    if (!bearer) add("api_key=${key.encodeURLParameter()}")
                }
                val url = buildString {
                    append("https://api.themoviedb.org/3")
                    append(path)
                    if (params.isNotEmpty()) {
                        append('?')
                        append(params.joinToString("&"))
                    }
                }
                val resp = client.get(url) {
                    if (bearer) header("Authorization", "Bearer $key")
                }
                if (resp.status == HttpStatusCode.OK) resp.bodyAsText() else null
            }.getOrNull()
        }

    /** Validate a credential by hitting `/configuration`. 200 = valid. */
    suspend fun validateKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false
        return getJsonOrNull("/configuration", "", key) != null
    }

    private fun imageUrl(path: String, size: String): String =
        "https://image.tmdb.org/t/p/$size$path"

    /**
     * Poster image URL for a program/VOD title via `/search/multi`
     * (include_adult=false). Returns an image.tmdb.org URL or null. Cached by
     * lowercased title (misses cached too).
     */
    suspend fun posterUrlForTitle(title: String, rawKey: String, size: String = "w500"): String? {
        val key = rawKey.trim()
        val cacheKey = title.trim().lowercase()
        if (key.isEmpty() || cacheKey.isEmpty()) return null
        cache[cacheKey]?.let { return if (it.isEmpty()) null else imageUrl(it, size) }
        val body = getJsonOrNull(
            "/search/multi",
            "query=${title.encodeURLParameter()}&include_adult=false",
            key,
        )
        val path = body?.let { parseSearchPosterPath(it) }
        cache[cacheKey] = path ?: ""
        Log.d(TAG, "search '$title' -> ${path ?: "no match"}")
        return path?.let { imageUrl(it, size) }
    }

    /** Poster by exact TMDB id (no fuzzy match) -- for VOD items with a tmdb_id. */
    suspend fun posterUrlForId(tmdbId: String, isMovie: Boolean, rawKey: String, size: String = "w500"): String? {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val cacheKey = "id:${if (isMovie) "movie" else "tv"}:$id"
        cache[cacheKey]?.let { return if (it.isEmpty()) null else imageUrl(it, size) }
        val body = getJsonOrNull("/${if (isMovie) "movie" else "tv"}/$id", "", key)
        val path = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["poster_path"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        cache[cacheKey] = path ?: ""
        Log.d(TAG, "id $id (${if (isMovie) "movie" else "tv"}) -> ${path ?: "no match"}")
        return path?.let { imageUrl(it, size) }
    }

    /** Prefer a movie/tv hit with a poster; else any hit with a poster. */
    private fun parseSearchPosterPath(body: String): String? = runCatching {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        fun posterOf(o: kotlinx.serialization.json.JsonElement): String? =
            o.jsonObject["poster_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun mediaOf(o: kotlinx.serialization.json.JsonElement): String? =
            o.jsonObject["media_type"]?.jsonPrimitive?.contentOrNull
        results.firstOrNull { posterOf(it) != null && (mediaOf(it) == "movie" || mediaOf(it) == "tv") }
            ?.let { return posterOf(it) }
        results.firstOrNull { posterOf(it) != null }?.let { return posterOf(it) }
        null
    }.getOrNull()

    private companion object {
        const val TAG = "TMDBService"
    }
}
