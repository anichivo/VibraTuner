package com.personal.vibratuner.data.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/** Запись, вставленная в MediaStore с `IS_PENDING = 1` и ждущая записи байтов. */
data class PendingDownload(
    val uri: Uri,
    val fileName: String
)

/**
 * Публичные скачанные треки: **`Download/VibraTuner/`** (часть 2 раунда правок).
 *
 * - Используется `MediaStore.Downloads` с `RELATIVE_PATH = "Download/VibraTuner/"`.
 *   Каталог создаётся системой при первой вставке — отдельное `mkdirs` не нужно.
 * - Разрешения на хранилище НЕ запрашиваются: приложение пишет только свои
 *   файлы в общую папку `Download` (scope storage, API 29+; minSdk 31).
 * - Сначала строка вставляется с `IS_PENDING = 1`, затем в неё пишутся байты и
 *   только после успешной записи `IS_PENDING` снимается. Недописанная запись
 *   удаляется — другие приложения не видят «полуфайл».
 * - Имя файла — детерминированная функция URL ([TrackDownloadStore.fileNameFor]),
 *   поэтому признак «скачан» — существование строки/файла, без поля в Room.
 * - Все ошибки MediaStore логируются и не бросаются наружу: недоступный файл
 *   просто означает «играем из сети/пропускаем».
 */
class MediaStoreDownloadStore(private val context: Context) : DownloadedFileLayer {

    private val cacheLock = Any()

    /** fileName -> content Uri; `null` — проверено, файла нет. */
    private val uriCache = HashMap<String, Uri?>()

    /** Существующий опубликованный файл для https-URL (или `null`). */
    fun find(url: String): Uri? {
        val fileName = TrackDownloadStore.fileNameFor(url) ?: return null
        synchronized(cacheLock) {
            if (uriCache.containsKey(fileName)) return uriCache[fileName]
        }
        val rows = queryRows(fileName)
        val found = rows
            ?.firstOrNull { !it.isPending && it.size > 0L }
            ?.uri
        synchronized(cacheLock) {
            // Не перетираем результат publish(), если он успел записать Uri.
            val known = uriCache[fileName]
            if (known != null) return known
            // Сбой запроса не означает «файла нет»: не кешируем ложное отсутствие.
            if (rows == null) return null
            uriCache[fileName] = found
            return found
        }
    }

    /** Вставляет запись `IS_PENDING = 1`; `null` — не удалось/URL не скачивается. */
    fun insertPending(url: String): PendingDownload? {
        val fileName = TrackDownloadStore.fileNameFor(url) ?: return null
        // Недописанная запись прошлого запуска (например, процесс убили) с тем же
        // DISPLAY_NAME заставляет MediaStore выдать новому файлу имя «… (1).mp3».
        // Удаляем такие хвосты ДО вставки, чтобы имя оставалось предсказуемым.
        deletePendingRows(fileName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, TrackDownloadStore.mimeTypeFor(fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return try {
            val uri = context.contentResolver.insert(downloadsCollection(), values)
            if (uri == null) {
                Log.w(TAG, "MediaStore не создал запись для $fileName")
                null
            } else {
                PendingDownload(uri = uri, fileName = fileName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка вставки в MediaStore ($fileName): ${e.message}")
            null
        }
    }

    /** Поток для записи байтов в ожидающую запись; `null` — открыть не удалось. */
    fun openOutput(pending: PendingDownload): OutputStream? = try {
        context.contentResolver.openOutputStream(pending.uri)
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось открыть файл MediaStore (${pending.fileName}): ${e.message}")
        null
    }

    /** Публикует полностью записанный файл (`IS_PENDING = 0`). */
    fun publish(pending: PendingDownload): Boolean {
        return try {
            val updated = context.contentResolver.update(
                pending.uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            if (updated > 0) {
                synchronized(cacheLock) { uriCache[pending.fileName] = pending.uri }
                true
            } else {
                Log.w(TAG, "MediaStore не подтвердил публикацию ${pending.fileName}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка публикации в MediaStore (${pending.fileName}): ${e.message}")
            false
        }
    }

    /** Удаляет недописанную запись (вызывать при любой ошибке загрузки). */
    fun discard(pending: PendingDownload) {
        if (deleteUri(pending.uri)) {
            synchronized(cacheLock) { uriCache.remove(pending.fileName) }
            Log.i(TAG, "Удалена недописанная запись MediaStore: ${pending.fileName}")
        }
    }

    /**
     * Удаляет скачанный файл по URL (если он есть). Используется при удалении
     * плейлиста и после успешной перезаписи новой публичной копии.
     *
     * Удаляются ВСЕ записи с этим именем в `Download/VibraTuner/`, включая
     * недописанные и переименованные MediaStore копии вида `name (1).mp3`:
     * иначе после удаления плейлиста на устройстве оставались бы «случайные»
     * треки. Посторонние файлы вне папки приложения не затрагиваются.
     *
     * Кеш наличия НЕ объявляет файл отсутствующим «на веру»: после попытки он
     * просто инвалидируется, поэтому следующий [find] снова спросит MediaStore.
     * Раньше кеш затирался в `null` даже при неудаче, и оставшийся файл был
     * невидим приложению до перезапуска процесса.
     *
     * @return true, если была удалена хотя бы одна запись.
     */
    override fun delete(url: String): Boolean {
        val fileName = TrackDownloadStore.fileNameFor(url) ?: return false
        val rows = queryRows(fileName)
        if (rows.isNullOrEmpty()) {
            // Запрос не удался или записей нет — кеш в любом случае инвалидируем.
            synchronized(cacheLock) { uriCache.remove(fileName) }
            return false
        }
        var deleted = 0
        rows.forEach { row ->
            if (deleteUri(row.uri)) deleted++
        }
        synchronized(cacheLock) { uriCache.remove(fileName) }
        Log.i(
            TAG,
            "Удаление $fileName: записей ${rows.size}, удалено $deleted"
        )
        return deleted > 0
    }

    /**
     * Есть ли ещё запись файла (опубликованная или недописанная).
     * Проверка идёт напрямую в MediaStore, мимо кеша — иначе после неудачного
     * удаления она бы «подтвердила» успех по закешированному `null`.
     *
     * Сбой запроса трактуется как «файл мог остаться» (`true`): так надёжнее —
     * вызывающий повторит попытку и запишет честный итог в лог.
     */
    override fun exists(url: String): Boolean {
        val fileName = TrackDownloadStore.fileNameFor(url) ?: return false
        return queryRows(fileName)?.isNotEmpty() ?: true
    }

    /**
     * Длительность ОПУБЛИКОВАННОГО файла в миллисекундах (Stage 3): читается
     * из самого файла через системный `MediaMetadataRetriever`. Для скачанного
     * трека это единственный достоверный источник, когда необязательного поля
     * `duration` в манифесте каталога нет. Файла нет / ошибка / не прочиталось
     * -> 0: UI ничего не выдумывает. Блокирующий вызов — только с IO-потока.
     */
    fun durationMs(url: String): Long {
        val uri = find(url) ?: return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать длительность файла ($url): ${e.message}")
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка освобождения MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /** Удаляет только недописанные записи с этим именем (хвосты прошлых запусков). */
    private fun deletePendingRows(fileName: String) {
        val rows = queryRows(fileName) ?: return
        val pending = rows.filter { it.isPending }
        if (pending.isEmpty()) return
        var deleted = 0
        pending.forEach { row ->
            if (deleteUri(row.uri)) deleted++
        }
        synchronized(cacheLock) { uriCache.remove(fileName) }
        Log.i(
            TAG,
            "Удалено недописанных записей $fileName: $deleted из ${pending.size}"
        )
    }

    /** Запись MediaStore в папке приложения, имя которой совпадает с URL-хэшем. */
    private data class DownloadRow(
        val uri: Uri,
        val size: Long,
        val isPending: Boolean
    )

    /**
     * Все записи в `Download/VibraTuner/`, относящиеся к [fileName]: точное имя
     * и переименованные MediaStore копии (`stem (1).ext`). Поиск идёт по имени,
     * а папка дополнительно проверяется в Kotlin: так удаление не зависит от
     * того, вернул ли конкретный MediaStore путь с завершающим слэшем. Другие
     * папки и файлы пользователя не затрагиваются.
     *
     * `null` — запрос НЕ удался: это НЕ то же самое, что «записей нет». Иначе
     * сбой MediaStore выдавался бы за успешное удаление.
     */
    private fun queryRows(fileName: String): List<DownloadRow>? = try {
        val collection = downloadsCollection()
        val extension = fileName.substringAfterLast('.', "")
        val selection: String
        val arguments: Array<String>
        if (extension.isEmpty()) {
            selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            arguments = arrayOf(fileName)
        } else {
            val stem = fileName.removeSuffix(".$extension")
            selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? OR " +
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            arguments = arrayOf(fileName, "$stem%")
        }
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.IS_PENDING,
                MediaStore.Downloads.RELATIVE_PATH
            ),
            selection,
            arguments,
            null
        )?.use { cursor ->
            val rows = mutableListOf<DownloadRow>()
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(1)
                if (!TrackDownloadStore.isSameFileName(displayName, fileName)) continue
                if (!isOurFolder(cursor.getString(4))) continue
                rows += DownloadRow(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    size = cursor.getLong(2),
                    isPending = cursor.getInt(3) != 0
                )
            }
            rows
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось найти скачанный файл ($fileName): ${e.message}")
        null
    }

    /** Путь MediaStore принадлежит папке приложения (с завершающим слэшем или без). */
    private fun isOurFolder(relativePath: String?): Boolean {
        val actual = relativePath?.trimEnd('/') ?: return false
        return actual == RELATIVE_PATH.trimEnd('/')
    }

    private fun deleteUri(uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось удалить недописанный файл: ${e.message}")
        false
    }

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private companion object {
        const val TAG = "MediaStoreDownload"

        /** Подпапка общей папки `Download`, куда складываются треки. */
        const val FOLDER_NAME = "VibraTuner"

        /** Относительный путь в MediaStore: создаётся системой автоматически. */
        val RELATIVE_PATH: String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
    }
}
