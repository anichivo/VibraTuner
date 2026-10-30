package com.personal.vibratuner.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Трек в плейлисте. [trackUri] — тот же стабильный идентификатор, что и `tracks.uri`
 * (нормализованный content:// URI); в этапе 4 допускается https-URL.
 * [title] и [artist] — снимок отображаемых метаданных на момент добавления.
 *
 * Записи упорядочены по [addedAt], затем по [id].
 * Внешний ключ объявлен по схеме, но SQLite-внешние ключи в приложении не включены,
 * поэтому удаление плейлиста вручную удаляет его строки (см. `SQLiteAppDatabase`).
 */
@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "trackUri"], unique = true)
    ]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val trackUri: String,
    val title: String,
    val artist: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
