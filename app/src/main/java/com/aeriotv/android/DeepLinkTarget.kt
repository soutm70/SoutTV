package com.aeriotv.android

/**
 * Resolved deep-link destination parsed from an `aeriotv://` Intent
 * (audit task #47). MainActivity captures the URI on launch / new-intent
 * and surfaces a [DeepLinkTarget] to [AerioTVNavHost]; the NavHost
 * navigates after the active playlist is ready and invokes the
 * consumed-callback to clear it.
 */
sealed interface DeepLinkTarget {
    /** Launch fullscreen player for [channelId] (matches M3UChannel.id). */
    data class Channel(val channelId: String) : DeepLinkTarget

    /** Launch movie detail screen for [movieUuid]. */
    data class Vod(val movieUuid: String) : DeepLinkTarget
}
