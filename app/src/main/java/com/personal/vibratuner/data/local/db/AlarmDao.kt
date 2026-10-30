package com.personal.vibratuner.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun getAllAlarmsFlow(): Flow<List<AlarmEntity>>

    /**
     * «Сколько будильников используют каждый плейлист»: playlistId -> alarmCount
     * для будильников с источником PLAYLIST. Нужно предупреждению при удалении
     * плейлиста; внешнего ключа на playlists нет, ссылка остаётся и после удаления.
     */
    @Query(
        "SELECT playlistId AS playlistId, COUNT(*) AS alarmCount FROM alarms " +
            "WHERE sourceType = 'PLAYLIST' AND playlistId IS NOT NULL GROUP BY playlistId"
    )
    fun getPlaylistAlarmUsageFlow(): Flow<List<PlaylistAlarmUsage>>

    @Query("SELECT * FROM alarms WHERE isEnabled = 1 ORDER BY hour ASC, minute ASC")
    suspend fun getEnabledAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarmById(id: Long): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)
}
