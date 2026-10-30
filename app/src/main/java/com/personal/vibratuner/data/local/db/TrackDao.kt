package com.personal.vibratuner.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY dateAdded ASC")
    fun getAllTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY dateAdded ASC")
    suspend fun getAllTracks(): List<TrackEntity>

    /** Один трек по id — для восстановления последней сессии (Part 2). */
    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: Long): TrackEntity?

    /**
     * URI треков указанного происхождения ([TrackSource.value]). Нужен удалению
     * плейлиста (Stage 2): файлы треков, скачанных по одному, удалять нельзя.
     */
    @Query("SELECT uri FROM tracks WHERE source = :source")
    suspend fun getUrisBySource(source: String): List<String>

    /**
     * Строки с НЕИЗВЕСТНОЙ длительностью, которые могут быть скачанными
     * файлами (Stage 3): указанное происхождение ИЛИ https-URI. Второе условие
     * нужно строкам, скачанным до появления колонки `source` (миграция 11 -> 12
     * пометила их IMPORTED): файл у них на устройстве есть, а длительности нет.
     * Только чтение: схема БД, версия и миграции не меняются.
     */
    @Query("SELECT * FROM tracks WHERE durationMs <= 0 AND (source = :source OR uri LIKE 'https://%')")
    suspend fun getTracksWithoutDuration(source: String): List<TrackEntity>

    /**
     * Заполняет длительность ТОЛЬКО если она ещё неизвестна: повторный вызов
     * ничего не перезаписывает, поэтому дозаполнение можно запускать безопасно.
     * Схема БД не меняется — колонка `durationMs` существовала и раньше.
     */
    @Query("UPDATE tracks SET durationMs = :durationMs WHERE uri = :uri AND durationMs <= 0")
    suspend fun updateDurationIfMissing(uri: String, durationMs: Long): Int

    /**
     * Вставляет треки, игнорируя дубли по уникальному URI.
     * Возвращает id вставленных строк; для пропущенных дублей возвращается -1.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<TrackEntity>): List<Long>

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tracks")
    suspend fun clearAll()
}
