package com.personal.vibratuner.data.download

import kotlinx.coroutines.delay

/**
 * Один слой хранения скачанных приложением файлов: публичная папка
 * `Download/VibraTuner/` (MediaStore) или устаревшие приватные файлы
 * (`filesDir/downloads`).
 *
 * Методы МОГУТ бросать исключения: их обрабатывает [DownloadedFilesCleaner],
 * чтобы сбой одного файла или одного слоя не остановил остальные. Поэтому
 * реализации НЕ должны прятать ошибку и выдавать её за успех.
 */
interface DownloadedFileLayer {
    /** Удалить все записи файла для [url] (включая недописанные и копии). */
    fun delete(url: String): Boolean

    /** Есть ли ещё хоть одна запись файла для [url]. Проверка обходит кеши. */
    fun exists(url: String): Boolean
}

/**
 * Честный итог очистки: что удалено, что осталось и сколько было ошибок/повторов.
 * По нему вызывающий пишет лог; ни одно исключение наружу не выходит.
 */
data class DownloadedFilesDeletionReport(
    val attempted: Int,
    val deletedUrls: Set<String>,
    val remainingUrls: Set<String>,
    val retried: Int,
    val errors: Int
) {
    val allDeleted: Boolean
        get() = remainingUrls.isEmpty()
}

/**
 * Надёжное удаление скачанных файлов (баг «после удаления скачанного плейлиста
 * часть треков остаётся на устройстве»).
 *
 * Причина прежнего поведения: удаление было ОДНОРАЗОВОЙ best-effort попыткой.
 * `MediaStoreDownloadStore.delete()` при любой ошибке только писал лог и
 * возвращал `false`, результат никто не проверял, а список URL после удаления
 * строк `playlist_tracks` жил только в памяти. Поэтому часть файлов оставалась
 * на диске, хотя UI считал их удалёнными (кеш наличия затирался), и повторить
 * очистку было нечем.
 *
 * Правила этого класса:
 * - каждый URL обрабатывается НЕЗАВИСИМО: исключение одного файла/слоя не мешает
 *   остальным URL и второму слою того же URL;
 * - сбой не считается успехом: после прохода наличие файлов ПРОВЕРЯЕТСЯ через
 *   [DownloadedFileLayer.exists], а оставшиеся пробуются ЕЩЁ РАЗ (ровно один повтор);
 * - повторная проверка фиксирует честный итог ([DownloadedFilesDeletionReport]);
 * - ни один вызов слоя не выходит наружу исключением.
 *
 * Класс намеренно не использует Android API: логика покрыта JVM-тестами с
 * фейковым слоем файлов (один файл бросает исключение — остальные удаляются).
 */
class DownloadedFilesCleaner(
    private val layers: List<DownloadedFileLayer>,
    private val retryDelayMs: Long = RETRY_DELAY_MS
) {

    /** Удаляет файлы всех [urls]; работа одного файла не прерывает остальные. */
    suspend fun delete(urls: Collection<String>): DownloadedFilesDeletionReport {
        val targets = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (targets.isEmpty() || layers.isEmpty()) {
            return DownloadedFilesDeletionReport(
                attempted = targets.size,
                deletedUrls = emptySet(),
                remainingUrls = emptySet(),
                retried = 0,
                errors = 0
            )
        }

        var errors = 0

        // Первый проход: КАЖДЫЙ URL и каждый слой в своём try/catch.
        targets.forEach { url -> attemptDelete(url) { errors++ } }

        // Проверка результата: «вернул false» ещё не значит «файла нет».
        var remaining = targets.filter { isStillPresent(it) { errors++ } }
        var retried = 0
        if (remaining.isNotEmpty()) {
            if (retryDelayMs > 0L) delay(retryDelayMs)
            remaining.forEach { url ->
                retried++
                attemptDelete(url) { errors++ }
            }
            remaining = remaining.filter { isStillPresent(it) { errors++ } }
        }

        val remainingSet = remaining.toSet()
        return DownloadedFilesDeletionReport(
            attempted = targets.size,
            deletedUrls = targets.toSet() - remainingSet,
            remainingUrls = remainingSet,
            retried = retried,
            errors = errors
        )
    }

    /** Пробует удалить URL во ВСЕХ слоях; сбой одного слоя не отменяет другой. */
    private fun attemptDelete(url: String, onError: () -> Unit) {
        layers.forEach { layer ->
            try {
                layer.delete(url)
            } catch (e: Exception) {
                onError()
            }
        }
    }

    /** Файл остался, если его видит ХОТЬ ОДИН слой; ошибка проверки = «остался». */
    private fun isStillPresent(url: String, onError: () -> Unit): Boolean =
        layers.any { layer ->
            try {
                layer.exists(url)
            } catch (e: Exception) {
                // Не знаем, удалён ли файл: безопаснее считать, что остался,
                // и попробовать ещё раз.
                onError()
                true
            }
        }

    companion object {
        /**
         * Небольшая пауза перед повтором: MediaStore может обновлять индекс
         * не мгновенно, а удаление занятого файла — освобождаться асинхронно.
         */
        const val RETRY_DELAY_MS = 250L
    }
}
