package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per local recording file. Populated by [LocalRecordingService] on
 * stream completion (or cancellation). Mirrors iOS Recording (Models.swift:680)
 * but only the local-destination fields — server-side recordings stay on
 * Dispatcharr and are queried fresh via the API.
 */
@Entity(tableName = "local_recording")
data class LocalRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelName: String,
    val title: String,
    val filePath: String,
    val startedAt: Long,
    val endedAt: Long,
    val byteSize: Long,
    val status: String, // "completed" | "stopped" | "failed"
)
