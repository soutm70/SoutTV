package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity

@Dao
interface EpgProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpgProgrammeEntity>)

    @Query("SELECT * FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: String): List<EpgProgrammeEntity>

    /**
     * Time-windowed variant of [forPlaylist] (iOS GuideStore parity --
     * EPGGuideView.swift `loadFromCache` predicate). Returns only programmes
     * whose airing window overlaps [fromMillis]..[toMillis], so cold launch
     * paint is ~5-10% the size of the full cache instead of all 58K+ rows.
     *
     * Overlap predicate: `endMillis > fromMillis AND startMillis < toMillis`.
     * This is symmetric in start/end (programmes that started before the
     * window AND end inside it are kept; ones that start inside the window
     * AND end after it are kept; ones fully inside are kept).
     *
     * The `endMillis > :fromMillis` clause hits the existing index on
     * `endMillis` (declared on the entity), so the scan stays cheap even on
     * a 200K-row table.
     */
    @Query(
        "SELECT * FROM epg_programme WHERE playlistId = :playlistId " +
            "AND endMillis > :fromMillis AND startMillis < :toMillis"
    )
    suspend fun forPlaylistInWindow(
        playlistId: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<EpgProgrammeEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Prune programmes that have already ended (hygiene across all sources). */
    @Query("DELETE FROM epg_programme WHERE endMillis < :before")
    suspend fun deleteEndedBefore(before: Long)

    /**
     * Replace the whole cached guide for one source in a single transaction so a
     * reader never sees a half-written batch. Mirrors iOS GuideStore.saveToCache.
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: String, rows: List<EpgProgrammeEntity>) {
        deleteForPlaylist(playlistId)
        insertAll(rows)
    }
}
