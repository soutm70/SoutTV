package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Disk-cached EPG programme. Mirrors iOS `GuideStore`'s SwiftData `EPGProgram`
 * cache: the last-fetched guide is persisted with a [fetchedAt] timestamp so a
 * relaunch can populate now-playing + the guide grid INSTANTLY from cache while
 * a background refresh runs (instead of staring at blank cards for the 10-40s a
 * cold network fetch + parse of 7000+ programmes takes).
 *
 * Rows are scoped by [playlistId] (a [PlaylistEntity.id] UUID string) so each
 * source keeps its own cache; [channelId] is the programme's tvg-id (the same
 * key `PlaylistViewModel.epgByChannel` groups on). The cache is a pure
 * derived-data store, so it is safe to drop / rebuild at any time.
 */
@Entity(
    tableName = "epg_programme",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "channelId"]),
        Index(value = ["endMillis"]),
        // Task #137: one row per (source, channel, start slot). With
        // OnConflictStrategy.REPLACE on insertAll, a fresh feed's copy of an
        // already-cached programme replaces it in place instead of
        // duplicating, which lets the history merge keep past rows without a
        // window-delete that erased recently-ended shows the feed no longer
        // carries (the merged-count regression: 257 -> 185 across one
        // refresh).
        Index(value = ["playlistId", "channelId", "startMillis"], unique = true),
    ],
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val channelId: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val category: String,
    val dispatcharrProgramId: Int?,
    /** Wall-clock millis when this row's batch was fetched (freshness check). */
    val fetchedAt: Long,
)
