package com.personal.vibratuner.data.download

import com.personal.vibratuner.data.local.db.TrackDao
import com.personal.vibratuner.data.local.db.TrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ленивое дозаполнение длительности УЖЕ скачанных треков (Stage 3).
 *
 * Раньше длительность скачанного трека бралась только из необязательного поля
 * `duration` манифеста каталога. Если его не было (или трек скачан старой
 * версией), в `tracks.durationMs` навсегда оставался 0, и строка «Моей
 * коллекции» не показывала длительность. Теперь недостающая длительность
 * читается из самого локального файла (`MediaMetadataRetriever`) и
 * сохраняется, поэтому повторно скачивать трек не нужно.
 *
 * Гарантии:
 * - трогаются только строки без длительности, которые могут быть скачанными:
 *   `source = DOWNLOADED` либо https-URI (так выглядят треки, скачанные до
 *   появления колонки `source`);
 * - ОДИН проход за жизнь процесса (флаг + [Mutex]): бесконечного цикла нет;
 * - фоновый вызов: Room и файлы трогаются только из IO-скоупа приложения;
 * - любой сбой (файл, чтение, БД) только логируется и пропускается: один
 *   сбойный трек не мешает остальным, падения и зависания нет;
 * - схема Room НЕ меняется: `durationMs` существовала и раньше, миграции нет.
 */
class DownloadedDurationBackfill(
    private val trackDao: TrackDao,
    /** Длительность локального файла по https-URL; 0 — файла нет/неизвестна. */
    private val durationMsOf: (String) -> Long,
    /** Лог: передаётся снаружи, чтобы класс не зависел от `android.util.Log` в тестах. */
    private val logger: (String, Throwable?) -> Unit
) {

    private val mutex = Mutex()

    /** Проход уже выполнялся в этом процессе (в том числе неудачно). */
    private var completed = false

    /**
     * Один проход по скачанным трекам с неизвестной длительностью.
     *
     * @return сколько строк обновлено (для логов/тестов).
     */
    suspend fun runOnce(): Int = mutex.withLock {
        if (completed) return@withLock 0
        // Флаг ставится ДО работы: параллельный/повторный вызов не запускает
        // второй проход и не может уйти в цикл повторов.
        completed = true
        val tracks = try {
            trackDao.getTracksWithoutDuration(TrackSource.DOWNLOADED.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("Дозаполнение длительностей: скачанные треки не прочитаны: ${e.message}", e)
            return@withLock 0
        }
        if (tracks.isEmpty()) return@withLock 0

        var updated = 0
        tracks.forEach { track ->
            try {
                val durationMs = durationMsOf(track.uri)
                if (durationMs > 0L) {
                    updated += trackDao.updateDurationIfMissing(track.uri, durationMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger("Длительность не дозаполнена (${track.uri}): ${e.message}", null)
            }
        }
        logger("Дозаполнение длительностей: проверено ${tracks.size}, обновлено $updated", null)
        updated
    }
}
