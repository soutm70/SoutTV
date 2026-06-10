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
 * Detail metadata TMDB can backfill when the Dispatcharr server provides
 * none (bare playlists with no provider-info). All fields pre-joined for
 * direct display: [genres] and [castTop] (first 6 names) are ", "-joined,
 * [director] is the movie crew's Director entries (or `created_by` for tv),
 * [year] is the 4-char release_date / first_air_date prefix, [voteAverage]
 * is pre-formatted "%.1f" with 0-votes dropped.
 */
data class TmdbDetails(
    val overview: String?,
    val genres: String?,
    val castTop: String?,
    val director: String?,
    val year: String?,
    val voteAverage: String?,
    val posterPath: String?,
)

/**
 * Minimal TMDB v3 client, the Android port of iOS `TMDBService`
 * (Aerio Networking/VODService.swift). Used ONLY to (a) validate the user's
 * own free API key, (b) look up a poster image when the playlist provides
 * none, and (c) backfill VOD detail metadata (plot / genre / cast / director
 * / year / rating) the server left blank. Public image CDN, no auth beyond
 * the user's key.
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

    /** "details:movie|tv:<id>" -> parsed details. Successes only; a failed
     *  request (offline, bad key, 404) stays retryable. */
    private val detailsCache = ConcurrentHashMap<String, TmdbDetails>()

    /** Drop every cached lookup, including misses recorded under an old key.
     *  Called when the user saves a new key so prior 401-era state can't
     *  outlive the credential that produced it. */
    fun clearCache() {
        cache.clear()
        detailsCache.clear()
    }

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
        // Cache only parsed 200 responses; a failed request (offline, bad key)
        // must stay retryable rather than being poisoned as a confirmed miss.
        if (body != null) cache[cacheKey] = path ?: ""
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
        // Same rule as posterUrlForTitle: never cache a failed request.
        if (body != null) cache[cacheKey] = path ?: ""
        Log.d(TAG, "id $id (${if (isMovie) "movie" else "tv"}) -> ${path ?: "no match"}")
        return path?.let { imageUrl(it, size) }
    }

    /**
     * Full detail metadata by exact TMDB id, `credits` appended so cast and
     * crew ride along in one request. Cached per id; only a parsed 200 is
     * cached (same rule as the poster lookups).
     */
    suspend fun detailsForId(tmdbId: String, isMovie: Boolean, rawKey: String): TmdbDetails? {
        val key = rawKey.trim()
        val id = tmdbId.trim()
        if (key.isEmpty() || id.isEmpty()) return null
        val kind = if (isMovie) "movie" else "tv"
        val cacheKey = "details:$kind:$id"
        detailsCache[cacheKey]?.let { return it }
        val body = getJsonOrNull("/$kind/$id", "append_to_response=credits", key)
        val details = body?.let { parseDetails(it, isMovie) }
        if (details != null) detailsCache[cacheKey] = details
        Log.d(TAG, "details $id ($kind) -> ${if (details != null) "ok" else "no match"}")
        return details
    }

    /**
     * Detail metadata when no tmdb_id is known: resolve an id via the same
     * `/search/multi` path (and trim+lowercase cache key normalization) the
     * poster fallback uses, then fetch details by id. The resolved id is
     * cached in [cache] under a "details-id:" prefix ("" = confirmed miss);
     * failed requests are never cached.
     */
    suspend fun detailsForTitle(title: String, isMovie: Boolean, rawKey: String): TmdbDetails? {
        val key = rawKey.trim()
        val kind = if (isMovie) "movie" else "tv"
        val normalizedTitle = title.trim().lowercase()
        if (key.isEmpty() || normalizedTitle.isEmpty()) return null
        val cacheKey = "details-id:$kind:$normalizedTitle"
        val id = when (val cached = cache[cacheKey]) {
            null -> {
                val body = getJsonOrNull(
                    "/search/multi",
                    "query=${title.encodeURLParameter()}&include_adult=false",
                    key,
                )
                val found = body?.let { parseSearchId(it, isMovie) }
                if (body != null) cache[cacheKey] = found ?: ""
                Log.d(TAG, "details search '$title' -> ${found ?: "no match"}")
                found ?: return null
            }
            "" -> return null
            else -> cached
        }
        return detailsForId(id, isMovie, key)
    }

    /** First search hit of the requested media_type. Unlike the poster path
     *  there is NO cross-type fallback: a tv id fed to /movie/{id} 404s. */
    private fun parseSearchId(body: String, isMovie: Boolean): String? = runCatching {
        val want = if (isMovie) "movie" else "tv"
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        results.firstOrNull {
            it.jsonObject["media_type"]?.jsonPrimitive?.contentOrNull == want
        }?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseDetails(body: String, isMovie: Boolean): TmdbDetails? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        fun field(name: String): String? =
            obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun names(elements: List<kotlinx.serialization.json.JsonElement>?, limit: Int = Int.MAX_VALUE): String? =
            elements
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() } }
                ?.take(limit)
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        val credits = obj["credits"]?.jsonObject
        TmdbDetails(
            overview = field("overview"),
            genres = names(obj["genres"]?.jsonArray),
            castTop = names(credits?.get("cast")?.jsonArray, limit = 6),
            director = if (isMovie) {
                names(
                    credits?.get("crew")?.jsonArray?.filter {
                        it.jsonObject["job"]?.jsonPrimitive?.contentOrNull == "Director"
                    },
                )
            } else {
                names(obj["created_by"]?.jsonArray)
            },
            year = field(if (isMovie) "release_date" else "first_air_date")?.take(4),
            voteAverage = obj["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { String.format("%.1f", it) },
            posterPath = field("poster_path"),
        )
    }.getOrNull()

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
