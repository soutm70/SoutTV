package com.aeriotv.android.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
     * GET /api/core/version/ - cheapest endpoint to verify connectivity + auth.
     * Returns the server version on success; surfaces HTTP status in the exception
     * message on failure so the UI can show a useful diagnostic.
     */
    suspend fun verifyConnection(baseUrl: String, apiKey: String): VersionResponse {
        val response: HttpResponse = client.get("${baseUrl.trimEnd('/')}/api/core/version/") {
            applyAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Connection check failed: HTTP ${response.status.value} ${response.status.description}")
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

    private suspend inline fun <reified T> fetchListOrResults(
        url: String,
        apiKey: String,
    ): List<T> {
        val response: HttpResponse = client.get(url) { applyAuth(apiKey) }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} ${response.status.description} from $url")
        }
        val raw: JsonElement = response.body()
        val array: JsonArray = when {
            raw is JsonArray -> raw
            raw is JsonObject && raw["results"] is JsonArray -> raw["results"]!!.jsonArray
            else -> throw IllegalStateException("Unexpected response shape from $url: ${raw::class.simpleName}")
        }
        return array.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    private inline fun <reified T> serializer(): kotlinx.serialization.KSerializer<T> =
        kotlinx.serialization.serializer()

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(apiKey: String) {
        accept(ContentType.Application.Json)
        header("X-API-Key", apiKey)
        header("Authorization", "ApiKey $apiKey")
    }

    /**
     * Returns the canonical playback URL for a Dispatcharr channel via the
     * proxy. iOS uses `/proxy/ts/stream/<uuid>` (line 2128). UUID is preferred
     * over numeric id because Dispatcharr can apply failover on the UUID path.
     */
    fun streamUrl(baseUrl: String, channelUuid: String): String =
        "${baseUrl.trimEnd('/')}/proxy/ts/stream/$channelUuid"

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
