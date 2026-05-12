package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalRecordingEntity): Long

    @Query("SELECT * FROM local_recording ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<LocalRecordingEntity>>

    @Query("DELETE FROM local_recording WHERE id = :id")
    suspend fun delete(id: Long)
}
