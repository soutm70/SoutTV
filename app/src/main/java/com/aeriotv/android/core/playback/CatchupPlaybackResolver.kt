package com.aeriotv.android.core.playback

import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.XtreamCodesApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "replay this past programme on this channel" into a playable timeshift
 * URL for the active source (task #133). Two paths:
 *
 * - Dispatcharr Direct Connect: the /timeshift/ endpoint authenticates with the
 *   Django username + the user's XC output password (no ApiKey support), both
 *   readable by the authenticated user from /api/accounts/users/me/ -- fetched
 *   once per server and memoized. The base host is derived from the channel's
 *   live stream URL so catch-up follows the same LAN/WAN host the live stream
 *   was rebuilt for. Dispatcharr advertises server_info.timezone = UTC, so the
 *   start renders in UTC, and the endpoint's one-time 301 (session_id) is
 *   pre-resolved so player seeks stay on one server session.
 *
 * - Xtream Codes provider: path-embedded playlist credentials against the
 *   panel base, with the start rendered in the panel's server_info.timezone
 *   (fetched once per server and memoized; the classic wrong-hour bug lives
 *   here). Raw panels answer directly -- no redirect resolve needed.
 */
@Singleton
class CatchupPlaybackResolver @Inject constructor(
    private val dispatcharrClient: DispatcharrClient,
    private val xtreamApi: XtreamCodesApi,
) {

    /** XC output creds per Dispatcharr base URL, memoized for the process. */
    private val xcCredsCache = ConcurrentHashMap<String, Pair<String, String>>()

    /** Panel timezone per XC base URL, memoized for the process. */
    private val panelTzCache = ConcurrentHashMap<String, String>()

    sealed class Failure(message: String) : Exception(message) {
        class NotCatchup :
            Failure("This channel has no catch-up archive.")

        class MissingXcPassword : Failure(
            "Catch-up needs an XC password on your Dispatcharr user. " +
                "Ask an admin to set one under Users in Dispatcharr.",
        )

        class Unsupported :
            Failure("Catch-up is available on Dispatcharr and Xtream Codes sources.")
    }

    suspend fun resolve(
        playlist: PlaylistEntity,
        channel: M3UChannel,
        startMillis: Long,
        endMillis: Long,
    ): Result<String> = runCatching {
        val streamId = channel.catchupStreamId
            ?.takeIf { channel.catchupDays > 0 }
            ?: throw Failure.NotCatchup()
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
        when (sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                // Follow the live stream's host (LAN/WAN-aware) rather than the
                // saved base, mirroring how playback URLs are rebuilt.
                val base = CatchupUrlBuilder.dispatcharrBaseFromStreamUrl(channel.url)
                    ?: playlist.urlString.trimEnd('/')
                val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() }
                    ?: throw Failure.Unsupported()
                val creds = xcCredsCache[base]
                    ?: dispatcharrClient.fetchXcCredentials(base, apiKey)
                        ?.also { xcCredsCache[base] = it }
                    ?: throw Failure.MissingXcPassword()
                // Hand ExoPlayer the raw timeshift URL and let ITS first
                // request follow the 301 that appends ?session_id=. We must
                // NOT pre-resolve that redirect ourselves: Dispatcharr ties
                // the session's serving generator to the request that created
                // it, so a throwaway probe that opens then closes SPENDS the
                // session and the player's subsequent open 404s (verified on
                // device). ExoPlayer's DefaultHttpDataSource follows the 301
                // in-band and keeps its own session alive for the playback.
                CatchupUrlBuilder.build(
                    CatchupUrlBuilder.Context(
                        baseUrl = base,
                        username = creds.first,
                        password = creds.second,
                        streamId = streamId,
                        panelTimeZoneId = "UTC",
                    ),
                    startMillis = startMillis,
                    endMillis = endMillis,
                )
            }
            SourceType.XtreamCodes -> {
                val base = playlist.urlString.trimEnd('/')
                val user = playlist.username?.takeIf { it.isNotBlank() }
                    ?: throw Failure.Unsupported()
                val pass = playlist.password.orEmpty()
                val tz = panelTzCache[base]
                    ?: xtreamApi.getServerTimezone(base, user, pass)
                        ?.also { panelTzCache[base] = it }
                    ?: "UTC"
                CatchupUrlBuilder.build(
                    CatchupUrlBuilder.Context(
                        baseUrl = base,
                        username = user,
                        password = pass,
                        streamId = streamId,
                        panelTimeZoneId = tz,
                    ),
                    startMillis = startMillis,
                    endMillis = endMillis,
                )
            }
            else -> throw Failure.Unsupported()
        }
    }
}
