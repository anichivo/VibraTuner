package com.personal.vibratuner.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Кеш PNG-иконок плейлистов каталога «От автора» (Stage 2).
 *
 * Иконки — ДАННЫЕ каталога, а не ресурсы приложения: новая иконка появляется
 * без выпуска обновления. Здесь только загрузка и хранение; рисование — Stage 3.
 *
 * Правила:
 * - файлы только в `cacheDir/playlist_icons`, имя — [PlaylistIconFiles.fileNameFor]
 *   (SHA-256 от разрешённого URL): смена иконки требует нового адреса в репозитории;
 * - [sync] вызывается ПОСЛЕ успешного обновления каталога (из ViewModel), работает
 *   в фоне на [ioDispatcher] и НИКОГДА не трогает главный поток; сервис и
 *   координатор воспроизведения этот кеш не используют;
 * - сеть — существующий `HttpURLConnection` (сторонних HTTP-библиотек в проекте
 *   нет), таймауты как у остальной сети, лимит ответа 256 КБ;
 * - принимается только настоящий PNG (сигнатура), запись — во временный файл и
 *   атомарное переименование;
 * - любая ошибка (нет сети, 404, слишком большой файл, не PNG, диск) пишется
 *   в лог и проглатывается: НИ исключений наружу, НИ ошибок в UI, НИ повторов —
 *   следующая попытка только при следующем обновлении каталога;
 * - после обновления удаляются файлы, на которые текущий каталог уже не ссылается.
 *
 * UI наблюдает [files] (`StateFlow`, ключ — разрешённый URL иконки): значение
 * меняется по мере скачивания, поэтому карточки перерисовываются сами.
 */
class PlaylistIconCache(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _files = MutableStateFlow<Map<String, File>>(emptyMap())

    /** Готовые файлы иконок: разрешённый https-URL -> файл в кеше. */
    val files: StateFlow<Map<String, File>> = _files.asStateFlow()

    /** Сериализует обновления кеша: два параллельных sync не мешают друг другу. */
    private val mutex = Mutex()

    /**
     * Приводит кеш в соответствие со ссылками текущего каталога.
     *
     * @param references разрешённые адреса иконок (`AuthorManifestPlaylist.icon`).
     * @param allowDownloads `true` — сразу после успешного обновления каталога:
     *   недостающие иконки скачиваются. `false` — каталог показан из кеша:
     *   используются только уже скачанные файлы, сеть не трогается (без
     *   повторных запросов в офлайне).
     *
     * Никогда не бросает исключений (кроме отмены корутины).
     */
    suspend fun sync(references: Collection<String>, allowDownloads: Boolean = true) {
        withContext(ioDispatcher) {
            mutex.withLock { runSync(references, allowDownloads) }
        }
    }

    private fun runSync(references: Collection<String>, allowDownloads: Boolean) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.w(TAG, "Кеш иконок каталога недоступен: ${directory.absolutePath}")
                return
            }

            val targets = LinkedHashMap<String, File>()
            var skipped = 0
            references.forEach { reference ->
                val file = PlaylistIconFiles.fileFor(directory, reference)
                if (file == null) {
                    skipped++
                } else {
                    targets[reference] = file
                }
            }
            if (skipped > 0) {
                Log.w(TAG, "Иконки каталога: пропущено не-https ссылок: $skipped")
            }

            val available = LinkedHashMap<String, File>()
            targets.forEach { (reference, file) ->
                if (isUsableCachedFile(file)) {
                    available[reference] = file
                } else if (file.exists()) {
                    val deleted = file.delete()
                    Log.w(TAG, "Удалён повреждённый файл иконки: ${file.name} (deleted=$deleted)")
                }
            }
            _files.value = available.toMap()

            var downloaded = 0
            var failed = 0
            if (allowDownloads) {
                targets.forEach { (reference, file) ->
                    if (available.containsKey(reference)) return@forEach
                    if (downloadOne(reference, file)) {
                        downloaded++
                        _files.update { current -> current + (reference to file) }
                    } else {
                        failed++
                    }
                }
            }

            val keep = targets.values.mapTo(HashSet()) { file -> file.name }
            val removed = directory.listFiles()?.count { entity ->
                entity.isFile && entity.name !in keep && entity.delete()
            } ?: 0

            Log.i(
                TAG,
                "Иконки каталога: ссылок ${targets.size}, готово ${_files.value.size}, " +
                    "скачано $downloaded, ошибок $failed, удалено $removed, " +
                    "режим ${if (allowDownloads) "сеть" else "только кеш"}"
            )
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Иконки каталога не обновлены: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Готовый файл: непустой, в пределах лимита и начинается с сигнатуры PNG. */
    private fun isUsableCachedFile(file: File): Boolean =
        file.isFile &&
            PlaylistIconFiles.isWithinSizeLimit(file.length()) &&
            PlaylistIconFiles.isPngFile(file)

    /**
     * Одна иконка: скачивание в [target] через временный файл и rename.
     * Возвращает `true` только если файл реально записан и проверен как PNG.
     */
    private fun downloadOne(url: String, target: File): Boolean {
        val temp = File(target.parentFile, target.name + PlaylistIconFiles.TEMP_SUFFIX)
        var connection: HttpURLConnection? = null
        try {
            val opened = try {
                URI(url).toURL().openConnection()
            } catch (e: Exception) {
                Log.w(TAG, "Иконка каталога не скачана ($url): ${e.message}")
                return false
            }
            if (opened !is HttpURLConnection) {
                Log.w(TAG, "Иконка каталога не скачана ($url): соединение не HTTP")
                return false
            }
            connection = opened
            opened.connectTimeout = CONNECT_TIMEOUT_MS
            opened.readTimeout = READ_TIMEOUT_MS
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("Accept", "image/png")
            opened.connect()

            val code = opened.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Иконка каталога не скачана ($url): HTTP $code")
                return false
            }
            val declaredBytes = opened.contentLengthLong
            if (declaredBytes > PlaylistIconFiles.MAX_ICON_BYTES) {
                Log.w(
                    TAG,
                    "Иконка каталога не скачана ($url): размер $declaredBytes байт " +
                        "больше лимита ${PlaylistIconFiles.MAX_ICON_BYTES}"
                )
                return false
            }

            val written = opened.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > PlaylistIconFiles.MAX_ICON_BYTES) {
                            Log.w(
                                TAG,
                                "Иконка каталога не скачана ($url): больше лимита " +
                                    "${PlaylistIconFiles.MAX_ICON_BYTES} байт"
                            )
                            return false
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    total
                }
            }
            if (written <= 0L) {
                Log.w(TAG, "Иконка каталога не скачана ($url): пустой ответ")
                return false
            }
            if (!PlaylistIconFiles.isPngFile(temp)) {
                Log.w(TAG, "Иконка каталога не скачана ($url): файл не PNG")
                return false
            }
            if (!publish(temp, target)) {
                Log.w(TAG, "Иконка каталога не сохранена ($url): не удалось переименовать файл")
                return false
            }
            Log.i(TAG, "Иконка каталога скачана: $url ($written байт)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Иконка каталога не скачана ($url): ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось закрыть соединение: ${e.message}")
            }
            if (temp.exists()) temp.delete()
        }
    }

    /** Атомарная замена: временный файл рядом, затем rename поверх целевого. */
    private fun publish(temp: File, target: File): Boolean {
        if (temp.renameTo(target)) return true
        // На некоторых файловых системах rename поверх существующего не работает.
        return target.exists() && target.delete() && temp.renameTo(target)
    }

    private companion object {
        const val TAG = "PlaylistIconCache"

        /** Таймауты как у остальной сети проекта. */
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val BUFFER_SIZE = 8 * 1024
    }
}
