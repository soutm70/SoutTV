package com.aeriotv.android.core.network

import com.aeriotv.android.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Dispatcharr REST client for Phase 4a. Mirrors iOS DispatcharrAPI
 * (Aerio/Networking/StreamingAPIs.swift) but only the endpoints needed for the
 * channel list: groups + channels + version. EPG, VOD, recordings, and the
 * user/pass JWT flow land in later phases.
 *
 * Auth header strategy (iOS lines 778-829): for API-key mode send BOTH
 *   `X-API-Key: <key>` AND `Authorization: ApiKey <key>`
 * to maximise compatibility with locked-down reverse proxies (the Freyguy1975
 * Synology case in v1.6.22). Bearer / auto-detect lands in Phase 4b alongside
 * the username-and-password JWT flow.
 *
 * Response shapes can be either a flat array OR a paginated wrapper
 *   `{count, next, previous, results: [...]}`
 * depending on whether the `page` query param is present. We don't request
 * pagination, so flat arrays are the common case; the helper handles both.
 */
@Singleton
class DispatcharrClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * Bare OkHttp client used only by [resolveVODStreamUrl]. Disables both
     * HTTP and HTTPS redirect-following at the engine level so the `Location`
     * header on Dispatcharr's 301 is readable to us. Ktor's wrapper currently
     * hangs when both `followRedirects = false` and the HttpRedirect plugin
     * are configured, so we drop down to OkHttp directly for this one call.
     */
    private val noRedirectOkHttp: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * POST /api/accounts/token/ - exchanges admin username + password for a JWT pair.
     * Mirrors iOS DispatcharrAPI.login (DispatcharrDirectConnect.swift lines 319-378).
     * The returned access token has ~30 min TTL; refresh token ~24 h.
     *
     * Throws [DispatcharrError.InvalidCredentials] on 401/403 (the user typed the
     * wrong password — message carries the Dashboard-vs-XC distinction so the
     * UX shows the actionable copy iOS line 296 spells out),
     * [DispatcharrError.UnexpectedResponse] on a 200 OK whose body isn't JWT
     * shaped (catches the SPA-shell case where the URL points at a non-Dispatcharr
     * host that 200s with HTML), and [DispatcharrError.Transport] on every other
     * network or HTTP failure.
     */
    suspend fun login(baseUrl: String, username: String, password: String): JwtPair {
        val response: HttpResponse = try {
            client.post("${baseUrl.trimEnd('/')}/api/accounts/token/") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header("User-Agent", dispatcharrUserAgent)
                setBody(LoginRequest(username = username, password = password))
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Login transport error: ${t.message ?: t::class.simpleName}",
            )
        }
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.InvalidCredentials(
                "Invalid username or password. AerioTV uses your Dispatcharr " +
                    "Dashboard password (System -> Users -> Account tab), " +
                    "not your Dispatcharr XC password.",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Login transport error: HTTP $code")
        }
        return try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Server returned an unexpected response shape during login. " +
                    "Verify the URL points at a Dispatcharr 0.23.0 or newer instance.",
            )
        }
    }

    /**
     * POST /api/accounts/token/refresh/ - exchange a refresh token for a fresh
     * access token. Mirrors iOS DispatcharrAPI.refreshAccessToken
     * (DispatcharrDirectConnect.swift lines 384-414). The refresh token is NOT
     * rotated by the server; only a new access token is emitted, so the caller
     * keeps reusing the existing refresh until it itself expires (24h+ idle).
     *
     * Throws [DispatcharrError.RefreshExpired] on 401/403 (refresh token stale —
     * caller should fall back to a fresh login from saved credentials),
     * [DispatcharrError.Transport] on any other failure.
     */
    suspend fun refreshAccessToken(baseUrl: String, refresh: String): String {
        val response: HttpResponse = try {
            client.post("${baseUrl.trimEnd('/')}/api/accounts/token/refresh/") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header("User-Agent", dispatcharrUserAgent)
                setBody(RefreshRequest(refresh = refresh))
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Refresh transport error: ${t.message ?: t::class.simpleName}",
            )
        }
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.RefreshExpired(
                "Refresh token expired. AerioTV will re-authenticate from saved credentials.",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Refresh transport error: HTTP $code")
        }
        val body: RefreshResponse = try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Refresh returned an unexpected response shape.",
            )
        }
        return body.access
    }

    /**
     * GET /api/accounts/users/me/ with Bearer access token. Used after [login]
     * to extract the user's `api_key` so subsequent calls can run via the
     * stable X-API-Key path. Mirrors iOS DispatcharrAPI.fetchCurrentUser
     * (DispatcharrDirectConnect.swift line 421-435).
     */
    suspend fun fetchCurrentUserApiKey(baseUrl: String, accessToken: String): String {
        val response: HttpResponse = try {
            client.get("${baseUrl.trimEnd('/')}/api/accounts/users/me/") {
                accept(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", dispatcharrUserAgent)
            }
        } catch (t: Throwable) {
            throw DispatcharrError.Transport(
                "Couldn't read user profile: ${t.message ?: t::class.simpleName}",
            )
        }
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Couldn't read user profile: HTTP ${response.status.value}")
        }
        val me: MeResponse = try {
            response.body()
        } catch (e: SerializationException) {
            throw DispatcharrError.UnexpectedResponse(
                "Server returned an unexpected user profile shape. Verify the URL " +
                    "points at a Dispatcharr 0.23.0 or newer instance.",
            )
        }
        return me.apiKey.takeIf { it.isNotBlank() }
            ?: throw DispatcharrError.UnexpectedResponse("Server did not return an api_key for this user")
    }

    /**
     * Default User-Agent for every Dispatcharr API call. Mirrors iOS
     * DeviceInfo.defaultUserAgent format so the Dispatcharr admin Stats
     * panel can attribute traffic to AerioTV alongside the iOS app:
     *
     *     AerioTV/<versionName> (Android; <Build.MODEL>)
     *
     * Example: `AerioTV/0.1.0 (Android; Pixel 8 Pro)`. Device nickname
     * customisation lands when the Android Appearance / Device-name UI
     * ships (iOS deviceNickname pref equivalent).
     */
    private val dispatcharrUserAgent: String by lazy {
        "AerioTV/${BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
    }

    /**
     * GET /api/core/version/ - cheapest endpoint to verify connectivity + auth.
     * Returns the server version on success; surfaces HTTP status in the exception
     * message on failure so the UI can show a useful diagnostic.
     */
    suspend fun verifyConnection(baseUrl: String, apiKey: String): VersionResponse {
        val url = "${baseUrl.trimEnd('/')}/api/core/version/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Connection check failed: HTTP ${response.status.value} ${response.status.description}")
        }
        return response.body()
    }

    /**
     * GET /api/channels/channels/ - all channels for the API-key's user.
     * Per iOS comment (line 1303): omitting the `page` query param disables
     * pagination on Dispatcharr's ChannelViewSet, returning a flat JSON array.
     */
    suspend fun listChannels(baseUrl: String, apiKey: String): List<DispatcharrChannel> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/channels/", apiKey)

    /** GET /api/channels/groups/ - channel group names and IDs. */
    suspend fun listGroups(baseUrl: String, apiKey: String): List<DispatcharrGroup> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/groups/", apiKey)

    /**
     * GET /api/epg/grid/ - bulk EPG window covering roughly -1h to +24h. iOS uses
     * this as the universal EPG source for Dispatcharr-backed playlists; the
     * `/output/epg` XMLTV path is gated by Dispatcharr 0.23+ LAN-only policy and
     * mostly redundant when this endpoint is available (EPGGuideView.swift:641-660).
     *
     * Response shape:
     *   {"data": [{id, start_time (ISO 8601 UTC), end_time, title, sub_title,
     *              description, tvg_id, season, episode, is_new, is_live, ...}, ...]}
     *
     * `<category>` is intentionally stripped from this bulk endpoint for perf;
     * categories are lazy-loaded per programme via /api/epg/programs/<id>/ when the
     * user opens ProgramInfoView (Phase 6+ in the Android port).
     */
    suspend fun getEpgGrid(baseUrl: String, apiKey: String): List<DispatcharrEpgEntry> {
        val url = "${baseUrl.trimEnd('/')}/api/epg/grid/"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("EPG grid failed: HTTP ${response.status.value} ${response.status.description}")
        }
        val wrapper: EpgGridResponse = response.body()
        return wrapper.data
    }

    private suspend inline fun <reified T> fetchListOrResults(
        url: String,
        apiKey: String,
    ): List<T> {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("HTTP ${response.status.value} ${response.status.description} from $url")
        }
        val raw: JsonElement = response.body()
        val array: JsonArray = when {
            raw is JsonArray -> raw
            raw is JsonObject && raw["results"] is JsonArray -> raw["results"]!!.jsonArray
            else -> throw DispatcharrError.UnexpectedResponse("Unexpected response shape from $url: ${raw::class.simpleName}")
        }
        return array.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    /**
     * Promote any 401/403 from an api_key-authenticated call to
     * [DispatcharrError.Unauthorized] so the AuthBroker's retry helper can
     * recognise the case where an admin rotated the user's api_key and
     * trigger silent rebootstrap. Used by every applyAuth() call site.
     */
    private fun unauthorizedCheck(response: HttpResponse, url: String) {
        val code = response.status.value
        if (code == 401 || code == 403) {
            throw DispatcharrError.Unauthorized(
                "Dispatcharr rejected the api_key for $url (HTTP $code). " +
                    "Admin probably rotated it.",
            )
        }
    }

    private inline fun <reified T> serializer(): kotlinx.serialization.KSerializer<T> =
        kotlinx.serialization.serializer()

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(apiKey: String) {
        accept(ContentType.Application.Json)
        header("X-API-Key", apiKey)
        header("Authorization", "ApiKey $apiKey")
        header("User-Agent", dispatcharrUserAgent)
    }

    /**
     * Returns the canonical playback URL for a Dispatcharr channel via the
     * proxy. iOS uses `/proxy/ts/stream/<uuid>` (line 2128). UUID is preferred
     * over numeric id because Dispatcharr can apply failover on the UUID path.
     */
    fun streamUrl(baseUrl: String, channelUuid: String): String =
        "${baseUrl.trimEnd('/')}/proxy/ts/stream/$channelUuid"

    /**
     * POST /api/channels/recordings/ — schedules a server-side DVR recording.
     * Mirrors iOS DispatcharrAPI.createRecording (StreamingAPIs.swift:2334).
     *
     * The iOS implementation supports an `applyServerOffsets` mode where the
     * caller embeds a `program` dict and lets Dispatcharr re-apply its own
     * pre/post-roll defaults. Phase 9a always passes the pre-rolled times
     * directly so the math is obvious; Phase 9b can revisit when local
     * recordings need parity.
     */
    suspend fun createRecording(
        baseUrl: String,
        apiKey: String,
        channelId: Int,
        startIso: String,
        endIso: String,
        title: String,
        description: String,
        comskip: Boolean,
    ): DispatcharrRecording {
        val customProps = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("description", JsonPrimitive(description))
            if (comskip) put("comskip", JsonPrimitive(true))
        }
        val body = buildJsonObject {
            put("channel", JsonPrimitive(channelId))
            put("start_time", JsonPrimitive(startIso))
            put("end_time", JsonPrimitive(endIso))
            put("custom_properties", customProps)
        }
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/"
        val response: HttpResponse = client.post(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Recording create failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
        return response.body()
    }

    /**
     * GET /api/channels/recordings/ — returns every recording the active user
     * can see. Client filters by status (scheduled / recording / completed /
     * failed / stopped) for the DVR tab filter chips.
     */
    suspend fun listRecordings(baseUrl: String, apiKey: String): List<DispatcharrRecording> =
        fetchListOrResults("${baseUrl.trimEnd('/')}/api/channels/recordings/", apiKey)

    /**
     * PATCH /api/channels/recordings/{id}/ — partial-update a scheduled DVR
     * row. Used by the DVR-tab edit sheet to bump pre-roll / post-roll on an
     * already-scheduled recording without canceling and re-creating it (which
     * would lose the row's id and any custom_properties Dispatcharr added).
     *
     * Currently we only mutate start_time, end_time, and the title/description
     * embedded in custom_properties. The Dispatcharr REST ViewSet accepts
     * either a full PUT or partial PATCH; PATCH is safer because we don't
     * have to round-trip every field the server filled in.
     */
    suspend fun updateRecording(
        baseUrl: String,
        apiKey: String,
        recordingId: Int,
        startIso: String,
        endIso: String,
        title: String,
        description: String,
    ): DispatcharrRecording {
        val customProps = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("description", JsonPrimitive(description))
        }
        val body = buildJsonObject {
            put("start_time", JsonPrimitive(startIso))
            put("end_time", JsonPrimitive(endIso))
            put("custom_properties", customProps)
        }
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/"
        val response: HttpResponse = client.patch(url) {
            applyAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Recording update failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
        return response.body()
    }

    /**
     * GET /api/vod/movies/?page_size=100 — first page of VOD movies. iOS
     * walks all pages via fetchAllPages; the Android first cut shows page 1
     * and a "Load more" affordance lands when the user request comes.
     */
    suspend fun getVODMoviesFirstPage(baseUrl: String, apiKey: String): VODMoviesPage {
        val url = "${baseUrl.trimEnd('/')}/api/vod/movies/?page_size=100"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("VOD movies fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODMoviesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODMovie>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODMovie>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODMoviesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected /api/vod/movies/ shape: ${raw::class.simpleName}")
        }
    }

    /**
     * GET /api/vod/series/?page_size=100 — first page of VOD series. Mirrors
     * iOS DispatcharrAPI.getVODSeries (StreamingAPIs.swift:1727), pagination
     * support deferred until the user hits the bottom of the grid.
     */
    suspend fun getVODSeriesFirstPage(baseUrl: String, apiKey: String): VODSeriesPage {
        val url = "${baseUrl.trimEnd('/')}/api/vod/series/?page_size=100"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("VOD series fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODSeriesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODSeries>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODSeries>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODSeriesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected /api/vod/series/ shape: ${raw::class.simpleName}")
        }
    }

    /**
     * GET /api/vod/series/<id>/episodes/?page=N&page_size=100. Mirrors iOS
     * DispatcharrAPI.fetchEpisodesPage (StreamingAPIs.swift:2086). Phase
     * 10c-2 fetches page 1 only; long-running shows (One Piece etc.) will
     * paginate properly in a later cut.
     */
    suspend fun getSeriesEpisodesFirstPage(
        baseUrl: String,
        apiKey: String,
        seriesId: Int,
    ): VODEpisodesPage {
        val url = "${baseUrl.trimEnd('/')}/api/vod/series/$seriesId/episodes/?page=1&page_size=100"
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport("Series episodes fetch failed: HTTP ${response.status.value}")
        }
        val raw: JsonElement = response.body()
        return when {
            raw is JsonArray -> VODEpisodesPage(
                count = raw.size,
                next = null,
                results = raw.map { json.decodeFromJsonElement(serializer<DispatcharrVODEpisode>(), it) },
            )
            raw is JsonObject -> {
                val results = (raw["results"] as? JsonArray)?.map {
                    json.decodeFromJsonElement(serializer<DispatcharrVODEpisode>(), it)
                } ?: emptyList()
                val count = (raw["count"]?.toString()?.toIntOrNull()) ?: results.size
                val next = raw["next"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                VODEpisodesPage(count = count, next = next, results = results)
            }
            else -> throw IllegalStateException("Unexpected episodes response shape: ${raw::class.simpleName}")
        }
    }

    /**
     * Resolves the redirect-bound proxy URL to the session-bound playback URL
     * for an episode. Same mechanism as [resolveVODStreamUrl] — Dispatcharr
     * emits a 301 to a one-time `/proxy/vod/episode/<uuid>/vod_<session>`
     * path that libmpv on Android can't follow itself.
     */
    suspend fun resolveVODEpisodeStreamUrl(
        baseUrl: String,
        apiKey: String,
        episodeUuid: String,
        streamId: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val base = "${baseUrl.trimEnd('/')}/proxy/vod/episode/$episodeUuid"
        val entry = if (streamId != null) "$base?stream_id=$streamId" else base
        val request = Request.Builder()
            .url(entry)
            .header("X-API-Key", apiKey)
            .header("Authorization", "ApiKey $apiKey")
            .header("Accept", "*/*")
            .build()
        noRedirectOkHttp.newCall(request).execute().use { response ->
            val code = response.code
            if (code in 300..399) {
                val location = response.header("Location") ?: return@use entry
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    val originRoot = Regex("(https?://[^/]+)").find(baseUrl)?.value
                        ?: baseUrl.trimEnd('/')
                    originRoot + location
                }
            } else {
                entry
            }
        }
    }

    /**
     * Returns the unresolved Dispatcharr VOD entry URL for a movie. Mirrors
     * iOS DispatcharrAPI.proxyMovieURL (StreamingAPIs.swift:2105).
     *
     * The server emits a 301 from this URL to a session-bound path
     * (`/proxy/vod/movie/<uuid>/vod_<session>`). Callers that intend to hand
     * the URL to libmpv must first resolve the redirect via
     * [resolveVODStreamUrl] - libmpv on Android does not re-attach custom
     * HTTP headers on a 301 hop, so playback fails before the session URL
     * is reached.
     */
    fun vodMovieUrl(baseUrl: String, movieUuid: String, streamId: Int? = null): String {
        val base = "${baseUrl.trimEnd('/')}/proxy/vod/movie/$movieUuid"
        return if (streamId != null) "$base?stream_id=$streamId" else base
    }

    /**
     * Hits Dispatcharr's VOD entry URL with redirects disabled and returns the
     * resolved session URL from the `Location` header. The session URL doesn't
     * require any further auth headers - it's a one-time playback handle the
     * server emits per request. Passing it to mpv works on the first try.
     *
     * Falls back to the entry URL if the response is unexpectedly non-3xx
     * (e.g. older Dispatcharr builds that serve direct content from the entry
     * path); mpv can still try that URL itself.
     */
    suspend fun resolveVODStreamUrl(
        baseUrl: String,
        apiKey: String,
        movieUuid: String,
        streamId: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val entry = vodMovieUrl(baseUrl, movieUuid, streamId)
        val request = Request.Builder()
            .url(entry)
            .header("X-API-Key", apiKey)
            .header("Authorization", "ApiKey $apiKey")
            .header("Accept", "*/*")
            .build()
        noRedirectOkHttp.newCall(request).execute().use { response ->
            val code = response.code
            if (code in 300..399) {
                val location = response.header("Location") ?: return@use entry
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    val originRoot = Regex("(https?://[^/]+)").find(baseUrl)?.value
                        ?: baseUrl.trimEnd('/')
                    originRoot + location
                }
            } else {
                entry
            }
        }
    }

    /** DELETE /api/channels/recordings/{id}/ — cancels a scheduled recording or removes a completed file. */
    suspend fun deleteRecording(baseUrl: String, apiKey: String, recordingId: Int) {
        val url = "${baseUrl.trimEnd('/')}/api/channels/recordings/$recordingId/"
        val response: HttpResponse = client.delete(url) { applyAuth(apiKey) }
        unauthorizedCheck(response, url)
        if (!response.status.isSuccess()) {
            throw DispatcharrError.Transport(
                "Recording delete failed: HTTP ${response.status.value} ${response.status.description}",
            )
        }
    }

    /**
     * Logo URL for a channel that has a logoID. Dispatcharr serves through
     * `/api/channels/logos/<id>/cache/`. AllowAny on the server, no auth header
     * required (matches Coil's anonymous fetch).
     */
    fun logoUrl(baseUrl: String, logoId: Int): String =
        "${baseUrl.trimEnd('/')}/api/channels/logos/$logoId/cache/"
}

@Serializable
data class VersionResponse(
    val version: String,
    val timestamp: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class JwtPair(
    val access: String,
    val refresh: String,
)

@Serializable
data class RefreshRequest(
    val refresh: String,
)

@Serializable
data class RefreshResponse(
    val access: String,
)

@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    @SerialName("api_key")
    val apiKey: String,
)

@Serializable
data class DispatcharrGroup(
    val id: Int,
    val name: String,
)

@Serializable
data class DispatcharrChannel(
    val id: Int,
    val name: String,
    val uuid: String? = null,
    @SerialName("channel_number")
    val channelNumber: Double? = null,
    @SerialName("logo_id")
    val logoId: Int? = null,
    @SerialName("channel_group_id")
    val channelGroupId: Int? = null,
    @SerialName("tvg_id")
    val tvgId: String? = null,
    @SerialName("epg_data_id")
    val epgDataId: Int? = null,
)

@Serializable
data class EpgGridResponse(
    val data: List<DispatcharrEpgEntry>,
)

@Serializable
data class VODMoviesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODMovie>,
)

@Serializable
data class VODSeriesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODSeries>,
)

@Serializable
data class VODEpisodesPage(
    val count: Int,
    val next: String?,
    val results: List<DispatcharrVODEpisode>,
)

@Serializable
data class DispatcharrVODEpisode(
    val id: Int,
    val uuid: String = "",
    val title: String = "",
    val name: String? = null,
    @SerialName("season_number")
    val seasonNumber: Int? = null,
    @SerialName("episode_number")
    val episodeNumber: Int? = null,
    val plot: String? = null,
    val overview: String? = null,
    @SerialName("air_date")
    val airDate: String? = null,
    val rating: String? = null,
    @SerialName("duration_secs")
    val durationSecs: Int? = null,
    val streams: List<DispatcharrVODStreamOption> = emptyList(),
) {
    val displayName: String get() = title.ifBlank { name.orEmpty() }
    val effectivePlot: String? get() = plot ?: overview
    val firstStreamId: Int? get() = streams.firstOrNull()?.streamId
}

@Serializable
data class DispatcharrVODSeries(
    val id: Int,
    val uuid: String = "",
    val name: String = "",
    val title: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val logo: DispatcharrVODLogo? = null,
) {
    val displayName: String get() = name.ifBlank { title.orEmpty() }
    val posterUrl: String? get() = logo?.url
}

@Serializable
data class DispatcharrVODMovie(
    val id: Int,
    val uuid: String,
    val title: String = "",
    val name: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    @SerialName("duration_secs")
    val durationSecs: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val logo: DispatcharrVODLogo? = null,
    val streams: List<DispatcharrVODStreamOption> = emptyList(),
) {
    val displayName: String get() = title.ifBlank { name.orEmpty() }
    val posterUrl: String? get() = logo?.url
    val firstStreamId: Int? get() = streams.firstOrNull()?.streamId
}

@Serializable
data class DispatcharrVODLogo(
    val url: String? = null,
    @SerialName("cache_url")
    val cacheUrl: String? = null,
)

@Serializable
data class DispatcharrVODStreamOption(
    @SerialName("stream_id")
    val streamId: Int? = null,
    @SerialName("provider_id")
    val providerId: Int? = null,
)

/**
 * Server-reported recording shape from `/api/channels/recordings/`. Wire shape
 * matches iOS `DispatcharrAPI.Recording` (StreamingAPIs.swift:2246) — only
 * `id`, `channel`, `start_time`, `end_time`, and `custom_properties` come back
 * at the top level. **Everything else (status, title, description, file size,
 * comskip flag) lives inside `custom_properties`**, and titles emitted by the
 * server-side scheduler are nested one level deeper under
 * `custom_properties.program.{title,description}`.
 *
 * Earlier Android revisions read `status` from a hypothetical top-level field,
 * which always decoded as null — that left every server recording in the
 * `Unknown` status bucket so it never matched the default Scheduled filter.
 * Re-aligning the getters with iOS (lines 2296-2316).
 */
@Serializable
data class DispatcharrRecording(
    val id: Int,
    val channel: Int? = null,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("task_id")
    val taskId: String? = null,
    @SerialName("custom_properties")
    val customProperties: JsonObject? = null,
) {
    /** Recording status (`scheduled`, `recording`, `in_progress`, `completed`,
     *  `stopped`, `failed`, ...). iOS reads this from custom_properties.status
     *  (line 2297); the top-level field doesn't exist on the wire. */
    val status: String?
        get() = customProperties?.stringField("status")

    /** Display title. Server-scheduled rows nest the program metadata under
     *  `custom_properties.program`; AerioTV's own createRecording call sets
     *  it as a flat key (`custom_properties.title`). Try the iOS-style nested
     *  path first, fall back to flat — matches StreamingAPIs.swift line 2308-2314. */
    val title: String
        get() {
            val program = customProperties?.objectField("program")
            return program?.stringField("title")
                ?: customProperties?.stringField("title")
                ?: ""
        }

    val description: String
        get() {
            val program = customProperties?.objectField("program")
            return program?.stringField("description")
                ?: customProperties?.stringField("description")
                ?: ""
        }

    val comskip: Boolean
        get() = customProperties?.boolField("comskip") ?: false

    val filePath: String?
        get() = customProperties?.stringField("file_path")

    val fileName: String?
        get() = customProperties?.stringField("file_name")

    /** Best-effort file-size lookup. Older Dispatcharr builds occasionally
     *  surface this as a flat key on the row; the new pipeline keeps it
     *  inside custom_properties. Try both. */
    val fileSize: Long?
        get() = customProperties?.longField("file_size")
            ?: customProperties?.longField("file_size_bytes")
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolField(name: String): Boolean? {
    val prim = this[name] as? JsonPrimitive ?: return null
    return prim.booleanOrNull ?: prim.content.toBooleanStrictOrNull()
}

private fun JsonObject.longField(name: String): Long? {
    val prim = this[name] as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.content.toLongOrNull()
}

private fun JsonObject.objectField(name: String): JsonObject? =
    this[name] as? JsonObject

@Serializable
data class DispatcharrEpgEntry(
    // NB: `id` is intentionally omitted. Real EPG entries carry a numeric `id`,
    // but Dispatcharr's "Dummy EPG" channels emit string ids like
    // `"dummy-custom-16444-21"`, which kotlinx-serialization can't coerce into
    // a single Kotlin numeric type. We don't need the id until per-programme
    // category lazy-load (Phase 6+), at which point we can switch to JsonElement
    // or split the model into typed/untyped variants.
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    val title: String = "",
    @SerialName("sub_title")
    val subTitle: String? = null,
    val description: String = "",
    @SerialName("tvg_id")
    val tvgId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("is_new")
    val isNew: Boolean = false,
    @SerialName("is_live")
    val isLive: Boolean = false,
    @SerialName("is_premiere")
    val isPremiere: Boolean = false,
    @SerialName("is_finale")
    val isFinale: Boolean = false,
)
