package com.personal.vibratuner.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Происхождение трека в библиотеке (Stage 2). В БД хранится строкой в
 * `tracks.source`:
 * - [IMPORTED] — трек добавлен пользователем из файлов/папки на устройстве
 *   (все строки, созданные до версии БД 12, получают это значение миграцией);
 * - [DOWNLOADED] — трек скачан из онлайн-каталога «От автора» ПО ОДНОМУ,
 *   поэтому является самостоятельной строкой библиотеки. Треки, скачанные
 *   только в составе плейлиста, в `tracks` НЕ попадают (см. STATE.md).
 */
enum class TrackSource(val value: String) {
    IMPORTED("IMPORTED"),
    DOWNLOADED("DOWNLOADED");

    companion object {
        /** Неизвестное/пустое значение трактуется как обычный добавленный файл. */
        fun fromValue(value: String?): TrackSource =
            entries.firstOrNull { it.value == value } ?: IMPORTED
    }
}

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["uri"], unique = true)]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
    val dateAdded: Long = System.currentTimeMillis(),
    /**
     * Происхождение трека ([TrackSource]): старые строки и импорт с устройства —
     * IMPORTED, скачанные по одному из каталога — DOWNLOADED.
     */
    @ColumnInfo(defaultValue = "'IMPORTED'")
    val source: String = TrackSource.IMPORTED.value
)
