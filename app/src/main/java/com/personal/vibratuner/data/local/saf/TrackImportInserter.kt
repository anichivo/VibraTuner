package com.personal.vibratuner.data.local.saf

import com.personal.vibratuner.data.local.db.TrackDao
import com.personal.vibratuner.data.local.db.TrackEntity

/** Итог вставки: сколько добавлено и сколько пропущено как уже существующие. */
data class TrackInsertResult(
    val added: Int,
    val skipped: Int
)

/**
 * Вставка нового трека(ов) с единственной проверкой «уже есть».
 *
 * Интерфейс нужен, чтобы автосканирование папки можно было проверить
 * JVM-тестом с фейком (Room в юнит-тестах недоступен).
 */
interface NewTrackInserter {
    /** Вставляет отсутствующие треки; `null` — вставлять нечего. */
    suspend fun insertNew(tracks: List<TrackEntity>): TrackInsertResult?
}

/**
 * ЕДИНАЯ точка вставки треков для ОБЕИХ SAF-ветвей импорта и автосканирования
 * сохранённой папки.
 *
 * Перед вставкой канонический ключ файла ([TrackIdentity]) сверяется с БД и с
 * дублями внутри пачки, поэтому tree-URI и документ-URI одного файла не дают
 * второй строки. Существующие строки не изменяются и не удаляются —
 * автосканирование только ДОБАВЛЯЕТ новые файлы.
 */
class TrackImportInserter(private val trackDao: TrackDao) : NewTrackInserter {

    override suspend fun insertNew(tracks: List<TrackEntity>): TrackInsertResult? {
        if (tracks.isEmpty()) return null
        val existingKeys = TrackIdentity.keysOf(trackDao.getAllTracks())
        val newTracks = TrackIdentity.selectNew(candidates = tracks, existingKeys = existingKeys)
        val insertedIds = trackDao.insertTracks(newTracks)
        val added = insertedIds.count { it != -1L }
        // «Страховка» unique-индекса тоже может дать -1 — считаем это пропуском.
        return TrackInsertResult(added = added, skipped = tracks.size - added)
    }
}
