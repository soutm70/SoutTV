package com.aeriotv.android.feature.watchprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Persistence facade for the VOD player. Reads + writes the watch-progress
 * Room table. Mirrors iOS NowPlayingManager.currentWatchProgress (the iOS
 * port pushes progress via SyncManager too; the Android port keeps the local
 * write path here and defers cloud-sync to Phase 12).
 */
@HiltViewModel
class WatchProgressViewModel @Inject constructor(
    private val dao: WatchProgressDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    fun observe(videoId: String): Flow<WatchProgressEntity?> = dao.observe(videoId)

    /**
     * Most-recently-updated rows. Caller filters by videoId-set to match
     * the current Movies / Series cache. iOS calls this "Continue Watching"
     * (project_aeriotv_ios_architecture.md section D); the "5 min from end =
     * completed" heuristic that hides finished items is applied at the UI
     * site, not in the DAO query.
     */
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressEntity>> = dao.observeRecent(limit)

    suspend fun get(videoId: String): WatchProgressEntity? = dao.getOnce(videoId)

    /** Upserts the current playback position. Called every ~5s from the player. */
    fun save(
        videoId: String,
        title: String,
        posterUrl: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        viewModelScope.launch {
            dao.upsert(
                WatchProgressEntity(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    playlistId = playlistDao.firstActive()?.id,
                ),
            )
        }
    }

    fun delete(videoId: String) {
        viewModelScope.launch { dao.delete(videoId) }
    }
}
