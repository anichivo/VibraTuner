package com.personal.vibratuner.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Плейлист пользователя или системный плейлист «Избранное».
 *
 * У системного плейлиста имя в БД НЕ хранится (пустая строка): подпись берётся
 * из `strings.xml` по флагу [isSystem], чтобы она была локализованной.
 *
 * [catalogId] — стабильный id плейлиста каталога «От автора», из которого сделан
 * плейлист «добавлен из каталога». `null` у обычного пользовательского плейлиста
 * и у «Избранного». Уникальный индекс `index_playlists_catalogId` не даёт
 * добавить один и тот же каталог дважды (NULL-ы SQLite считает разными).
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val catalogId: String? = null
) {
    companion object {
        /** Фиксированный id системного плейлиста «Избранное». */
        const val FAVORITES_PLAYLIST_ID = 1L

        /** Имя системного плейлиста в БД пустое: отображается строка из ресурсов. */
        const val FAVORITES_NAME = ""

        /** Постоянная дата создания: «Избранное» всегда первое в списке. */
        const val FAVORITES_CREATED_AT = 0L
    }
}
