package com.personal.vibratuner.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MicrosoundDao {

    @Query("SELECT * FROM microsounds ORDER BY id ASC")
    fun getAllMicrosoundsFlow(): Flow<List<MicrosoundEntity>>

    @Query("SELECT * FROM microsounds WHERE id = :id")
    suspend fun getMicrosoundById(id: Long): MicrosoundEntity?

    @Query("SELECT * FROM microsounds WHERE isEnabled = 1")
    suspend fun getEnabledMicrosounds(): List<MicrosoundEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMicrosound(entity: MicrosoundEntity): Long

    @Update
    suspend fun updateMicrosound(entity: MicrosoundEntity)

    @Delete
    suspend fun deleteMicrosound(entity: MicrosoundEntity)

    @Query("DELETE FROM microsounds WHERE id = :id")
    suspend fun deleteMicrosoundById(id: Long)
}
