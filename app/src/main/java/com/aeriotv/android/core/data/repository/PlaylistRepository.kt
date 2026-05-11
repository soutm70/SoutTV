package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.PlaylistFetcher
import com.aeriotv.android.core.parser.M3UParser
import com.aeriotv.android.core.parser.XMLTVParser
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for playlist persistence + fetch + parse.
 * Mirrors how iOS Aerio handles playlists: row stored in SwiftData, channels
 * re-parsed from source on every refresh (NOT cached individually).
 *
 * Derives the M3U URL, EPG URL, and HTTP headers from the playlist's
 * [PlaylistEntity.sourceType]:
 *  - M3uUrl: use [PlaylistEntity.urlString] directly + optional [PlaylistEntity.epgUrl].
 *  - DispatcharrApiKey: M3U = `${urlString}/output/m3u`, EPG = `${urlString}/output/epg`,
 *    headers = `{X-API-Key: ${apiKey}, Accept: application/json}` (Phase 4a).
 *  - DispatcharrUserPass: TODO Phase 4b (JWT flow).
 *  - XtreamCodes: TODO Phase 4c (player_api.php enumeration -> get.php m3u_plus).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val fetcher: PlaylistFetcher,
    private val dispatcharrClient: DispatcharrClient,
) {

    /** Inputs for creating or updating a playlist row. */
    data class SaveRequest(
        val sourceType: SourceType,
        val name: String?,
        val url: String,
        val epgUrl: String? = null,
        val apiKey: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    suspend fun activePlaylist(): PlaylistEntity? = dao.firstActive()

    suspend fun loadAndPersist(
        request: SaveRequest,
        existingId: String? = null,
    ): Result<Pair<PlaylistEntity, List<M3UChannel>>> = runCatching {
        val normalisedBase = request.url.trimEnd('/')
        val sourceType = request.sourceType
        if (!sourceType.isImplemented) {
            throw UnsupportedOperationException(
                "${sourceType.displayName} support lands in a later phase",
            )
        }

        val channels = fetchChannelsFor(
            sourceType = sourceType,
            base = normalisedBase,
            userEpgUrl = request.epgUrl,
            apiKey = request.apiKey,
        )

        val entity = PlaylistEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = request.name?.takeIf { it.isNotBlank() } ?: deriveName(normalisedBase),
            urlString = normalisedBase,
            epgUrl = request.epgUrl?.takeIf { it.isNotBlank() },
            sourceType = sourceType.name,
            apiKey = request.apiKey?.takeIf { it.isNotBlank() },
            username = request.username?.takeIf { it.isNotBlank() },
            password = request.password?.takeIf { it.isNotBlank() },
            channelCount = channels.size,
            lastRefreshedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        entity to channels
    }

    /**
     * Re-fetch channels for an existing playlist row, updating channelCount and
     * lastRefreshedAt without changing identity fields.
     */
    suspend fun refresh(playlist: PlaylistEntity): Result<List<M3UChannel>> = runCatching {
        val sourceType = playlist.resolvedSourceType()
        val channels = fetchChannelsFor(
            sourceType = sourceType,
            base = playlist.urlString,
            userEpgUrl = playlist.epgUrl,
            apiKey = playlist.apiKey,
        )
        dao.update(
            playlist.copy(
                channelCount = channels.size,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        channels
    }

    suspend fun loadEpg(playlist: PlaylistEntity): Result<List<EPGProgramme>> = runCatching {
        val sourceType = playlist.resolvedSourceType()
        // M3U: hit the user-supplied XMLTV URL.
        // Dispatcharr: EPG via /api/epg/grid/ has a different JSON shape — that
        // path lands in Phase 4a-ii. Returning empty is the graceful fallback;
        // channel-row now-playing badges will simply not appear.
        if (sourceType != SourceType.M3uUrl) return@runCatching emptyList()
        val epgUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: return@runCatching emptyList()
        val bytes = fetcher.fetchBytes(epgUrl)
        val programmes = XMLTVParser.parseBytes(bytes)
        dao.update(playlist.copy(lastEpgRefreshedAt = System.currentTimeMillis()))
        programmes
    }

    suspend fun clear() = dao.clear()

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Source" }

    private suspend fun fetchChannelsFor(
        sourceType: SourceType,
        base: String,
        userEpgUrl: String?,
        apiKey: String?,
    ): List<M3UChannel> = when (sourceType) {
        SourceType.M3uUrl -> {
            val bytes = fetcher.fetchBytes(base)
            M3UParser.parseBytes(bytes)
        }
        SourceType.DispatcharrApiKey -> {
            val key = apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Dispatcharr API key is required")
            val groups = dispatcharrClient.listGroups(base, key)
                .associate { it.id to it.name }
            val channels = dispatcharrClient.listChannels(base, key)
            channels
                .filter { !it.uuid.isNullOrBlank() }
                .sortedWith(compareBy(
                    { it.channelNumber ?: Double.MAX_VALUE },
                    { it.name.lowercase() },
                ))
                .map { ch ->
                    M3UChannel(
                        name = ch.name,
                        url = dispatcharrClient.streamUrl(base, ch.uuid!!),
                        groupTitle = ch.channelGroupId?.let { groups[it] }.orEmpty(),
                        tvgID = ch.tvgId.orEmpty(),
                        tvgName = ch.name,
                        tvgLogo = ch.logoId?.let { dispatcharrClient.logoUrl(base, it) }.orEmpty(),
                        channelNumber = ch.channelNumber?.toInt(),
                    )
                }
        }
        SourceType.DispatcharrUserPass,
        SourceType.XtreamCodes -> error("$sourceType is not implemented yet")
    }
}

private fun PlaylistEntity.resolvedSourceType(): SourceType =
    SourceType.entries.firstOrNull { it.name == sourceType } ?: SourceType.M3uUrl
