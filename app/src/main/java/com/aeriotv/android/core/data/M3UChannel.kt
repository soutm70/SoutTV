package com.aeriotv.android.core.data

import java.util.UUID

/**
 * Mirrors iOS Aerio/Networking/PlaylistParsers.swift M3UChannel (lines 4-14).
 * Pure data class with no Room annotations yet — Phase 2b will add @Entity.
 */
data class M3UChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val groupTitle: String = "",
    val tvgID: String = "",
    val tvgName: String = "",
    val tvgLogo: String = "",
    /**
     * `tvg-chno` from the playlist, preserved verbatim (just trimmed). Mirrors
     * iOS commit d1ac87a: prior versions parsed this as Int? and dropped any
     * decimal-valued entry (Dispatcharr sub-channels like 2.1 / 11444.0).
     * Keeping the raw String lets us render the user's exact identifier and
     * still sort numerically via toDoubleOrNull() at sort sites.
     */
    val channelNumber: String? = null,
    val rawAttributes: Map<String, String> = emptyMap(),
    /**
     * Dispatcharr's primary-key integer for this channel, used when scheduling
     * server-side recordings (`/api/channels/recordings/` requires the int id).
     * Null for M3U / Xtream sources that don't carry it.
     */
    val dispatcharrChannelId: Int? = null,
)
