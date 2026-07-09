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

    /**
     * EPG-scope search for the global Search surface (parity task #41 / iOS
     * SearchView EPG scope). Matches title OR description, case-insensitive
     * (Room LIKE is case-insensitive for ASCII), time-windowed to now-forward
     * (endMillis > :nowMillis) so already-ended programmes don't clutter
     * results. Ordered by start time so the soonest airing surfaces first.
     * Caller (PlaylistRepository.searchEpg) injects '%'||q||'%' wildcards.
     */
    @Query(
        "SELECT * FROM epg_programme WHERE playlistId = :playlistId " +
            "AND endMillis > :nowMillis " +
            "AND (title LIKE :like OR description LIKE :like) " +
            "ORDER BY startMillis ASC LIMIT :limit"
    )
    suspend fun searchInWindow(
        playlistId: String,
        like: String,
        nowMillis: Long,
        limit: Int = 60,
    ): List<EpgProgrammeEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Prune one source's programmes that ended before its retention cutoff
     *  (catch-up task #135: retention is per playlist now, so the old blanket
     *  cross-source ended-1h-ago delete is gone). */
    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId AND endMillis < :before")
    suspend fun deleteEndedBeforeForPlaylist(playlistId: String, before: Long)

    /** Delete only the rows the incoming feed's window covers (everything
     *  ending after the feed's earliest start); [mergeForPlaylist]'s helper. */
    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId AND endMillis > :fromMillis")
    suspend fun deleteCoveredWindow(playlistId: String, fromMillis: Long)

    /**
     * Merge a fresh feed into one source's cached guide in a single
     * transaction so a reader never sees a half-written batch. Unlike the old
     * delete-all-and-insert (iOS GuideStore.saveToCache mirror), this KEEPS
     * history rows the new feed no longer covers: only rows overlapping the
     * feed's own window (ending after its earliest start) are replaced.
     * That is what lets already-aired programmes accumulate for catch-up
     * (task #135); upstream feeds usually carry little or no past data, so a
     * full replace erased the browsable history on every refresh. No
     * duplicate risk: any old row the feed still contains ends inside the
     * deleted window, so it is replaced, never doubled.
     */
    @Transaction
    suspend fun mergeForPlaylist(playlistId: String, rows: List<EpgProgrammeEntity>) {
        val feedStart = rows.minOfOrNull { it.startMillis } ?: return
        deleteCoveredWindow(playlistId, feedStart)
        insertAll(rows)
    }
}
