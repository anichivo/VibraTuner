package com.personal.vibratuner.data.download

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.personal.vibratuner.core.network.ConnectionErrorClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Минимальное состояние загрузки одного плейлиста (индикатор в карусели
 * «Моей медиатеки»). Хранится только в памяти: признак «скачан» — наличие
 * файла в [TrackDownloadStore], поэтому отдельное поле БД не нужно.
 *
 * [isFailed] — загрузка завершилась неполной (ошибка сети/сервера/записи или
 * закончились попытки подряд). Такой плейлист ОБЯЗАН быть виден пользователю:
 * карусель показывает значок ошибки, тап по нему повторяет загрузку.
 */
data class PlaylistDownloadState(
    val playlistId: Long,
    val total: Int,
    val completed: Int,
    val isRunning: Boolean,
    val isFailed: Boolean = false,
    /** Байты текущего файла: для плавного прогресса внутри одного трека. */
    val currentBytes: Long = 0L,
    val currentTotalBytes: Long = 0L
) {
    /** Все треки этого плейлиста уже лежат на устройстве. */
    val isComplete: Boolean
        get() = total > 0 && completed >= total && !isRunning && !isFailed

    /**
     * Общий прогресс плейлиста 0..1: завершённые треки + доля текущего файла.
     * Если сервер не сообщил размер (chunked), текущий файл не добавляет долю,
     * и полоса движется по мере завершения треков.
     */
    val progressFraction: Float
        get() {
            if (total <= 0) return 0f
            val currentFraction = if (currentTotalBytes > 0L) {
                (currentBytes.toDouble() / currentTotalBytes.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            } else {
                0f
            }
            return ((completed + currentFraction) / total.toFloat()).coerceIn(0f, 1f)
        }

    /** Процент для подписи на карточке; 0..100. */
    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

/**
 * Один трек каталога, который пользователь скачивает отдельно (Stage 2).
 * [url] — https-адрес потока (он же стабильный URI будущей строки библиотеки),
 * [title]/[artist]/[durationMs] — метаданные из манифеста для записи в `tracks`.
 */
data class DownloadableTrack(
    val url: String,
    val title: String,
    val artist: String? = null,
    val durationMs: Long = 0L
)

/**
 * Состояние отдельной загрузки одного трека (ключ — https-URL). Живёт в
 * приложении-менеджере, поэтому индикатор в строке переживает рекомпозицию,
 * уход с экрана и повторный вход. `null` состояния — трек не качается.
 */
data class SingleTrackDownloadState(
    val url: String,
    val isRunning: Boolean = false,
    val isDone: Boolean = false,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    /** Доля текущего файла 0..1; неизвестный размер прогресс не выдумывает. */
    val progressFraction: Float
        get() {
            if (!isRunning) return if (isDone) 1f else 0f
            if (totalBytes <= 0L) return 0f
            return (bytes.toDouble() / totalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    /** Процент для существующей подписи «Скачивается… N%»; 0..100. */
    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

/** Одноразовое событие отдельной загрузки трека: UI показывает короткий Snackbar. */
sealed interface TrackDownloadEvent {
    /** Сеть/сервер/диск подвели: индикатор уже снят, случай записан в лог. */
    data class Failed(val url: String) : TrackDownloadEvent

    /**
     * Загрузка встроенного онлайн-контента (отдельного трека каталога или
     * плейлиста) прервалась именно из-за сети или HTTP-ответа сервера
     * ([com.personal.vibratuner.core.network.ConnectionErrorClassifier]).
     * UI показывает одно короткое сообщение «Ошибка соединения…»; локальные
     * файлы и не-сетевые сбои это событие НИКОГДА не порождают.
     */
    data object ConnectionFailed : TrackDownloadEvent
}

/**
 * Скачивание треков плейлистов (часть 3 правок; с части 2 того раунда —
 * в общую папку `Download/VibraTuner/`).
 *
 * Скачивание ТОЛЬКО РУЧНОЕ (этап «каталог»): кнопка «Скачать» на экране
 * плейлиста — локального или каталога «От автора» (его можно скачать, не
 * добавляя в «Мою коллекцию»). Добавление плейлиста в библиотеку больше НЕ
 * запускает загрузку, автоматической догрузки после перезапуска тоже нет.
 *
 * - Работает в собственном IO-скоупе приложения: не блокирует UI и воспроизведение,
 *   переживает закрытие экрана «Каталог».
 * - Новые файлы адресуются по URL ([MediaStoreDownloadStore]): повторный запуск
 *   не качает уже скачанное, поэтому «Скачать» после отмены догружает только
 *   отсутствующие треки.
 * - Ошибка одного трека (нет сети, 404, закончилось место) логируется, трек
 *   пропускается; после [MAX_CONSECUTIVE_FAILURES] ошибок подряд загрузка
 *   плейлиста прекращается, остальные треки не ломаются, падений нет.
 * - Неполная загрузка публикует [PlaylistDownloadState.isFailed]: UI показывает
 *   ошибку и кнопку повтора, а не «тихий» успех (см. `LibraryPlaylistsGrid`).
 * - Отмена удаляет недописанный файл текущего трека и СОХРАНЯЕТ уже скачанные;
 *   состояние задания снимается, поэтому плейлист снова «не скачан».
 * - Воспроизведение файлов — существующим путём сервиса/координатора
 *   ([com.personal.vibratuner.service.SoundPlaybackService.buildMediaItem]);
 *   этот класс только кладёт файлы и сообщает, какой Uri играть.
 *
 * Stage 2: тем же путём ([downloadOne], [MediaStoreDownloadStore]) качается
 * ОДИН трек каталога. Файл адресуется по URL, поэтому повторной загрузки нет,
 * а загрузка плейлиста позже переиспользует уже скачанный файл. После успеха
 * вызывается [onTrackDownloaded] — приложение сохраняет самостоятельную строку
 * библиотеки с происхождением DOWNLOADED. Состояния одиночных загрузок
 * ([trackStates]) и счётчик изменений файлов ([fileGeneration]) живут здесь,
 * поэтому индикатор в строке переживает рекомпозицию и повторный вход на экран.
 */
class TrackDownloadManager(
    private val store: TrackDownloadStore,
    private val publicStore: MediaStoreDownloadStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Сохранение самостоятельного скачанного трека в библиотеке (Stage 2).
     * Вызывается ТОЛЬКО после успешной записи файла; ошибка БД логируется и не
     * отменяет уже скачанный файл. `null` — режим без библиотеки (тесты).
     */
    private val onTrackDownloaded: (suspend (DownloadableTrack) -> Unit)? = null,
    /**
     * Part 3: файлы скачанных треков удалены пользователем. Приложение передаёт
     * сюда безопасную остановку играющего трека через сервис/координатор.
     * Вызывается ТОЛЬКО после удаления файлов; `null` — режим без сервиса.
     */
    private val onDownloadedFilesDeleted: ((Set<String>) -> Unit)? = null
) {

    private val _states = MutableStateFlow<Map<Long, PlaylistDownloadState>>(emptyMap())

    /** Состояния загрузки по id плейлиста (для индикатора в UI). */
    val states: StateFlow<Map<Long, PlaylistDownloadState>> = _states.asStateFlow()

    private val _trackStates = MutableStateFlow<Map<String, SingleTrackDownloadState>>(emptyMap())

    /** Состояния одиночных загрузок по https-URL (для строки трека и меню). */
    val trackStates: StateFlow<Map<String, SingleTrackDownloadState>> = _trackStates.asStateFlow()

    private val _fileGeneration = MutableStateFlow(0L)

    /**
     * Счётчик изменений скачанных файлов: увеличивается после успешной загрузки
     * и удаления файлов. UI перечитывает признак «уже на устройстве», не
     * подписываясь на каждый байт прогресса.
     */
    val fileGeneration: StateFlow<Long> = _fileGeneration.asStateFlow()

    private val _events = MutableSharedFlow<TrackDownloadEvent>(extraBufferCapacity = 4)

    /** Одноразовые события одиночных загрузок (ошибка -> короткий Snackbar). */
    val events: SharedFlow<TrackDownloadEvent> = _events.asSharedFlow()

    /** Активные задания: один плейлист (ключ) — одно задание. */
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Активные одиночные загрузки: один URL — одно задание (без дублей). */
    private val singleTrackJobs = ConcurrentHashMap<String, Job>()

    /** Файлы качаются строго по одному: без дублей одного URL из разных плейлистов. */
    private val downloadMutex = Mutex()

    /**
     * Единая надёжная очистка файлов: каждый трек независимо, проверка
     * результата и один повтор. Слои — публичная `Download/VibraTuner/`
     * (MediaStore) и прежние приватные файлы в `filesDir/downloads`.
     */
    private val fileCleaner = DownloadedFilesCleaner(listOf(publicStore, store))

    /**
     * Длительности локальных файлов по https-URL (Stage 3). Чтение
     * `MediaMetadataRetriever` дорогое, а результат для файла не меняется,
     * поэтому значение запоминается; при удалении файла запись снимается.
     */
    private val durationCache = ConcurrentHashMap<String, Long>()

    /**
     * Uri для воспроизведения локальной копии: сначала публичный
     * `Download/VibraTuner/` (content Uri), затем файл прошлой версии
     * (`filesDir/downloads`). `null` — локальной копии нет, играем из сети.
     */
    fun playbackUri(url: String): Uri? =
        publicStore.find(url) ?: store.existingFile(url)?.let { Uri.fromFile(it) }

    /** Уже скачан ли файл трека: публичная копия или устаревший приватный файл. */
    fun isFileDownloaded(url: String): Boolean =
        publicStore.find(url) != null || store.existingFile(url) != null

    /**
     * Какие из [urls] уже лежат на устройстве. Читает MediaStore, поэтому
     * вызывать с IO-диспетчера (результат кешируется в [MediaStoreDownloadStore]).
     */
    suspend fun downloadedUris(urls: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            urls.filterTo(mutableSetOf()) { isFileDownloaded(it) }
        }

    /**
     * Длительность УЖЕ СКАЧАННОГО файла по https-URL, мс (Stage 3). Читается
     * из самого локального файла (публичного `Download/VibraTuner/` или
     * устаревшего приватного) через `MediaMetadataRetriever` и кешируется:
     * это дорогой вызов, а длительность файла не меняется. 0 — файла нет или
     * длительность не прочиталась: значение не выдумывается.
     *
     * Блокирующий вызов: вызывать ТОЛЬКО с IO-потока
     * ([resolveLocalDurations] делает это сам).
     */
    fun localDurationMs(url: String): Long {
        val key = url.trim()
        durationCache[key]?.let { return it }
        val fromPublic = publicStore.durationMs(key)
        val duration = if (fromPublic > 0L) {
            fromPublic
        } else {
            store.existingFile(key)?.let { readFileDurationMs(it) } ?: 0L
        }
        if (duration > 0L) durationCache[key] = duration
        return duration
    }

    /**
     * Длительности скачанных файлов для списка URL (Stage 3): карта
     * «URL -> мс» только для тех, у кого файл есть и длительность прочиталась.
     * Выполняется в IO, ошибка одного файла не мешает остальным, отсутствующие
     * файлы просто не попадают в карту. Нужно строкам плейлиста: у трека,
     * скачанного только в составе плейлиста, нет строки `tracks` с длительностью.
     */
    suspend fun resolveLocalDurations(urls: Collection<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val result = HashMap<String, Long>()
            urls.map { it.trim() }
                .distinct()
                .filter { TrackDownloadStore.fileNameFor(it) != null }
                .forEach { url ->
                    try {
                        val duration = localDurationMs(url)
                        if (duration > 0L) result[url] = duration
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Длительность файла не прочитана ($url): ${e.message}")
                    }
                }
            result
        }

    /** Длительность приватного (устаревшего) файла; 0 при любой ошибке. */
    private fun readFileDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать длительность файла ${file.name}: ${e.message}")
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка освобождения MediaMetadataRetriever: ${e.message}")
            }
        }
    }

    /**
     * Скачивает ОДИН трек каталога в существующую папку `Download/VibraTuner/`
     * и (при успехе) сохраняет его самостоятельной строкой библиотеки.
     *
     * - независимо от плейлиста: ничего не добавляет в «Мою коллекцию» и не
     *   запускает загрузку плейлиста;
     * - уже скачанный файл не дублируется ([downloadOne] возвращает true сразу);
     * - прогресс публикуется в [trackStates], поэтому индикатор в строке не
     *   зависит от жизненного цикла экрана;
     * - при ошибке состояние снимается, пишется лог и уходит
     *   [TrackDownloadEvent.Failed] — индикатор не застревает.
     */
    fun downloadTrack(track: DownloadableTrack) {
        val url = track.url.trim()
        if (TrackDownloadStore.fileNameFor(url) == null) {
            Log.w(TAG, "Трек не скачивается (не https-адрес): $url")
            return
        }
        if (singleTrackJobs[url]?.isActive == true) {
            Log.i(TAG, "Трек уже скачивается, повтор не запускается: $url")
            return
        }
        // Индикатор появляется сразу, ещё до обращения к сети.
        _trackStates.update { it + (url to SingleTrackDownloadState(url = url, isRunning = true)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Сбой соединения отличается от прочих ошибок: для него есть
                // отдельное короткое сообщение (см. ConnectionErrorClassifier).
                var connectionError = false
                val downloaded = downloadOne(
                    url = url,
                    onProgress = { bytes, totalBytes ->
                        val running = SingleTrackDownloadState(
                            url = url,
                            isRunning = true,
                            bytes = bytes,
                            totalBytes = totalBytes
                        )
                        _trackStates.update { states -> states + (url to running) }
                    },
                    onConnectionError = { connectionError = true }
                )
                if (!downloaded) {
                    Log.w(TAG, "Трек не скачан: $url")
                    _trackStates.update { it - url }
                    _events.tryEmit(
                        if (connectionError) {
                            TrackDownloadEvent.ConnectionFailed
                        } else {
                            TrackDownloadEvent.Failed(url)
                        }
                    )
                    return@launch
                }
                // Stage 3: длительность берём из САМОГО файла — необязательное
                // поле `duration` манифеста может отсутствовать, и тогда строка
                // библиотеки оставалась без длительности навсегда. Значение из
                // файла достовернее манифеста; если файл не читается — прежнее.
                val fileDurationMs = withContext(Dispatchers.IO) { localDurationMs(url) }
                persistStandaloneTrack(
                    track.copy(
                        url = url,
                        durationMs = if (fileDurationMs > 0L) fileDurationMs else track.durationMs
                    )
                )
                _trackStates.update { it + (url to SingleTrackDownloadState(url = url, isDone = true)) }
                Log.i(TAG, "Трек скачан и сохранён в библиотеке: $url")
            } catch (e: CancellationException) {
                _trackStates.update { it - url }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка загрузки трека ($url): ${e.message}")
                _trackStates.update { it - url }
                _events.tryEmit(TrackDownloadEvent.Failed(url))
            } finally {
                singleTrackJobs.remove(url)
            }
        }
        singleTrackJobs[url] = job
        job.start()
    }

    /** Сохраняет скачанный трек в библиотеке; сбой БД не отменяет файл. */
    private suspend fun persistStandaloneTrack(track: DownloadableTrack) {
        val persist = onTrackDownloaded ?: return
        try {
            persist(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Скачанный трек не сохранён в библиотеке (${track.url}): ${e.message}",
                e
            )
        }
    }

    /**
     * Скачивает отсутствующие треки плейлиста (ручное действие «Скачать»).
     * Уже скачанные пропускаются, предыдущее задание этого плейлиста сначала
     * ДОЖИДАЕТСЯ (cancelAndJoin): иначе старое задание могло бы опубликовать
     * файл уже после удаления плейлиста, и на устройстве оставался бы
     * «случайный» трек.
     *
     * [playlistId] — ключ задания: `playlists.id` у плейлиста библиотеки или
     * отрицательный ключ каталога из [PlaylistDownloadKeys]. Автоматических
     * загрузок больше НЕТ: скачивание запускается только кнопкой на экране
     * плейлиста.
     *
     * Всё, включая проверку файлов, выполняется в IO-скоупе: вызывать можно
     * из главного потока, UI не блокируется.
     */
    suspend fun startPlaylistDownload(playlistId: Long, trackUris: List<String>) {
        jobs.remove(playlistId)?.cancelAndJoin()
        // Новая попытка (в том числе «Повторить» после ошибки): старый статус
        // «ошибка/готово» снимается, пока идёт проверка файлов.
        _states.update { it - playlistId }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            prepareAndDownload(playlistId, trackUris)
        }
        jobs[playlistId] = job
        job.start()
    }

    /**
     * Отменяет загрузку плейлиста и забывает его состояние (кнопка «Отменить
     * загрузку», удаление плейлиста). Дожидается остановки задания, чтобы
     * скачанные ранее треки остались, недописанный файл текущего трека удалился
     * (это делает `downloadOne` в своём `finally`), а прогресс не «завис» в UI.
     */
    suspend fun cancelPlaylist(playlistId: Long) {
        jobs.remove(playlistId)?.cancelAndJoin()
        _states.update { it - playlistId }
    }

    /**
     * Удаляет скачанные файлы перечисленных треков (общие треки сюда не попадают):
     * и публичные в `Download/VibraTuner/`, и устаревшие приватные.
     *
     * Каждый файл удаляется НЕЗАВИСИМО от остальных; после прохода наличие
     * проверяется, а оставшиеся пробуются ещё раз (см. [DownloadedFilesCleaner]).
     * Сбой одного файла не мешает остальным, а честный итог пишется в лог.
     *
     * Работа НЕ отменяется вместе с ViewModel: удаление данных должно доходить
     * до конца, иначе часть файлов оставалась бы на устройстве.
     */
    suspend fun deleteDownloadedFiles(trackUris: Collection<String>) {
        if (trackUris.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            val report = fileCleaner.delete(trackUris)
            Log.i(
                TAG,
                "Очистка файлов плейлиста: проверено ${report.attempted}, " +
                    "удалено ${report.deletedUrls.size}, повторено ${report.retried}, " +
                    "осталось ${report.remainingUrls.size}, ошибок ${report.errors}"
            )
            report.remainingUrls.forEach { url ->
                Log.w(TAG, "Файл не удалён после повтора: $url")
            }
            // Stage 3: длительность удалённого файла больше не актуальна.
            trackUris.forEach { url -> durationCache.remove(url.trim()) }
            // Признак «уже на устройстве» у оставшихся треков перечитывается.
            _fileGeneration.update { it + 1L }
        }
    }

    /**
     * ЕДИНАЯ точка удаления скачанных приложением файлов (Part 3, задачи 1–2):
     * отдельного трека из меню «⋮» и всех треков плейлиста по кнопке
     * «Удалить из памяти». Удаляет ТОЛЬКО файлы приложения (https-URL в
     * `Download/VibraTuner/` или старый приватный файл); `content://` треки
     * пользователя не затрагиваются ([TrackDownloadStore.fileNameFor] для них
     * возвращает null).
     *
     * Признак «скачан» в проекте НЕ хранится в Room: он выводится из наличия
     * файла, поэтому «сброс состояния» — это удаление файла + [fileGeneration],
     * по которому UI немедленно перечитывает состояние. Дополнительно снимаются
     * состояния одиночных загрузок ([trackStates], иначе пункт остался бы
     * «Скачано»), незавершённые задания этих URL отменяются, а завершённые
     * статусы плейлистов ([states]) снимаются — их прогресс больше не актуален.
     *
     * Сам трек остаётся в БД/плейлистах (задание): он просто снова играет из
     * сети и может быть скачан заново.
     *
     * Работа НЕ отменяется вместе с ViewModel: удаление должно дойти до конца.
     */
    suspend fun deleteDownloadedTracks(trackUris: Collection<String>) =
        withContext(NonCancellable + Dispatchers.IO) {
            val urls = trackUris
                .map { it.trim() }
                .filter { TrackDownloadStore.fileNameFor(it) != null }
                .distinct()
            if (urls.isEmpty()) {
                Log.i(TAG, "Удаление из памяти: нет скачиваемых https-треков, файлы не трогаю")
                return@withContext
            }
            // Недописанный файл нельзя «воскресить» после удаления: сначала
            // останавливаем активные одиночные загрузки этих URL.
            urls.forEach { url ->
                singleTrackJobs[url]?.cancelAndJoin()
            }
            deleteDownloadedFiles(urls)
            _trackStates.update { states -> states - urls.toSet() }
            // Завершённые статусы плейлистов содержат устаревшее «скачано»:
            // идущие загрузки не трогаем, остальные UI пересчитает по файлам.
            _states.update { states -> states.filterValues { !it.isComplete } }
            try {
                onDownloadedFilesDeleted?.invoke(urls.toSet())
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось сообщить сервису об удалении файлов: ${e.message}")
            }
            Log.i(TAG, "Удаление из памяти: проверено ${urls.size} https-треков")
        }

    /** Подготовка (в IO) и загрузка; задание всегда убирается из [jobs]. */
    private suspend fun prepareAndDownload(playlistId: Long, trackUris: List<String>) {
        var downloadable = emptyList<String>()
        var alreadyDownloaded = 0
        try {
            downloadable = trackUris
                .map { it.trim() }
                .filter { TrackDownloadStore.fileNameFor(it) != null }
                .distinct()
            if (downloadable.isEmpty()) return

            alreadyDownloaded = downloadable.count { publicStore.find(it) != null }
            updateState(
                playlistId = playlistId,
                total = downloadable.size,
                completed = alreadyDownloaded,
                isRunning = alreadyDownloaded < downloadable.size
            )
            if (alreadyDownloaded >= downloadable.size) return

            downloadMissing(playlistId, downloadable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ошибка подготовки (чтение диска, неожиданный сбой) тоже обязана
            // быть видимой: без этого пользователь видел бы «Добавлено» без файлов.
            Log.e(TAG, "Загрузка плейлиста id=$playlistId прервана: ${e.message}", e)
            if (downloadable.isNotEmpty()) {
                updateState(
                    playlistId = playlistId,
                    total = downloadable.size,
                    completed = alreadyDownloaded,
                    isRunning = false,
                    isFailed = true
                )
            }
        } finally {
            val self = coroutineContext[Job]
            if (self != null) jobs.remove(playlistId, self)
        }
    }

    private suspend fun downloadMissing(playlistId: Long, downloadable: List<String>) {
        var completed = downloadable.count { publicStore.find(it) != null }
        var consecutiveFailures = 0
        // Хотя бы один трек не скачался ИЗ-ЗА СЕТИ: по итогу задания уйдёт
        // одно событие с сообщением «Ошибка соединения…».
        var connectionFailure = false
        try {
            for (url in downloadable) {
                coroutineContext.ensureActive()
                if (publicStore.find(url) != null) continue
                // Прогресс внутри текущего файла: полоса показывает и завершённые
                // треки, и долю байтов текущего, поэтому она движется в реальном
                // времени, а не только после каждого трека.
                val downloaded = downloadOne(
                    url = url,
                    onProgress = { bytes, totalBytes ->
                        updateState(
                            playlistId = playlistId,
                            total = downloadable.size,
                            completed = completed,
                            isRunning = true,
                            currentBytes = bytes,
                            currentTotalBytes = totalBytes
                        )
                    },
                    onConnectionError = { connectionFailure = true }
                )
                if (downloaded) {
                    completed++
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                updateState(playlistId, downloadable.size, completed, isRunning = true)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w(
                        TAG,
                        "Загрузка плейлиста id=$playlistId остановлена: " +
                            "$consecutiveFailures ошибок подряд"
                    )
                    break
                }
            }
        } finally {
            // Итог считаем по фактическим файлам: часть треков могла быть скачана
            // параллельно/раньше, а счётчик внутри цикла — только подсказка.
            // Отмена (удаление плейлиста или новая попытка) статус не публикует:
            // состояние уже снято/перезаписано вызывающей стороной.
            if (currentCoroutineContext().isActive) {
                val done = downloadable.count { publicStore.find(it) != null }
                val failed = done < downloadable.size
                updateState(
                    playlistId,
                    downloadable.size,
                    done,
                    isRunning = false,
                    isFailed = failed
                )
                if (!failed) {
                    Log.i(TAG, "Плейлист id=$playlistId скачан: $done треков")
                } else {
                    Log.w(
                        TAG,
                        "Плейлист id=$playlistId скачан не полностью: " +
                            "$done из ${downloadable.size} треков" +
                            (if (connectionFailure) " (есть сбой соединения)" else "")
                    )
                    if (connectionFailure) {
                        // Одно событие на всю серию сбоев задания: UI показывает
                        // одно короткое сообщение, а не по сообщению на трек.
                        _events.tryEmit(TrackDownloadEvent.ConnectionFailed)
                    }
                }
            }
        }
    }

    private fun updateState(
        playlistId: Long,
        total: Int,
        completed: Int,
        isRunning: Boolean,
        isFailed: Boolean = false,
        currentBytes: Long = 0L,
        currentTotalBytes: Long = 0L
    ) {
        val state = PlaylistDownloadState(
            playlistId = playlistId,
            total = total,
            completed = completed,
            isRunning = isRunning,
            isFailed = isFailed,
            currentBytes = currentBytes,
            currentTotalBytes = currentTotalBytes
        )
        _states.update { current -> current + (playlistId to state) }
    }

    /**
     * Один трек: запись сразу в публичную `Download/VibraTuner/` через MediaStore.
     * Сначала вставляется строка `IS_PENDING = 1`, затем в неё пишутся байты и
     * только после проверки длины запись публикуется. При любой ошибке (сеть,
     * HTTP-код, неверная длина, HTML вместо аудио, диск) запись удаляется, пишется
     * лог и возвращается `false`: остальные треки плейлиста продолжают качаться,
     * исключение наружу не выходит.
     *
     * [onProgress] вызывается во время записи (не чаще [PROGRESS_UPDATE_INTERVAL_MS]):
     * UI получает плавный прогресс текущего файла, а не только счётчик треков.
     * Копирование проверяет отмену корутины, поэтому `cancelAndJoin` при удалении
     * плейлиста не ждёт окончания многомегабайтного файла.
     */
    private suspend fun downloadOne(
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        /**
         * Сбой вызван сетью или HTTP-ответом сервера (а не диском/типом файла):
         * вызывается один раз на такую ошибку и только она даёт пользователю
         * сообщение «Ошибка соединения…».
         */
        onConnectionError: () -> Unit = {}
    ): Boolean = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            if (publicStore.find(url) != null) return@withContext true
            val pending = publicStore.insertPending(url) ?: return@withContext false
            var published = false
            var connection: HttpURLConnection? = null
            try {
                val opened = URI(url).toURL().openConnection()
                if (opened !is HttpURLConnection) {
                    Log.w(TAG, "Трек не скачан ($url): соединение не HTTP")
                    return@withContext false
                }
                connection = opened
                opened.connectTimeout = CONNECT_TIMEOUT_MS
                opened.readTimeout = READ_TIMEOUT_MS
                opened.instanceFollowRedirects = true
                opened.requestMethod = "GET"
                opened.setRequestProperty("Accept", "audio/*,*/*")
                opened.connect()

                val responseCode = opened.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Трек не скачан ($url): HTTP $responseCode")
                    if (ConnectionErrorClassifier.isConnectionError(httpStatusCode = responseCode)) {
                        onConnectionError()
                    }
                    return@withContext false
                }
                val contentType = opened.contentType
                if (!isAcceptableContentType(contentType)) {
                    Log.w(TAG, "Трек не скачан ($url): вместо аудио пришёл $contentType")
                    return@withContext false
                }
                val expectedBytes = opened.contentLengthLong
                // contentLengthLong = -1 для chunked-ответа: долю текущего файла
                // в этом случае не показываем, полоса движется по трекам.
                val totalBytes = expectedBytes.coerceAtLeast(0L)
                val output = publicStore.openOutput(pending)
                if (output == null) {
                    Log.w(
                        TAG,
                        "Трек не скачан ($url): не удалось открыть файл в Download/VibraTuner"
                    )
                    return@withContext false
                }
                val written = opened.inputStream.use { input ->
                    output.use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var totalWritten = 0L
                        var lastReportedAt = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            totalWritten += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReportedAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                                lastReportedAt = now
                                onProgress(totalWritten, totalBytes)
                            }
                        }
                        onProgress(totalWritten, totalBytes)
                        totalWritten
                    }
                }
                if (written <= 0L) {
                    Log.w(TAG, "Трек не скачан ($url): пустой файл")
                    return@withContext false
                }
                if (expectedBytes > 0L && written != expectedBytes) {
                    Log.w(TAG, "Трек не скачан ($url): $written из $expectedBytes байт")
                    return@withContext false
                }
                if (!publicStore.publish(pending)) {
                    Log.w(TAG, "Не удалось опубликовать скачанный трек ($url)")
                    return@withContext false
                }
                published = true
                // Файл появился: UI перечитывает признак «уже на устройстве»
                // (одиночная загрузка и загрузка плейлиста идут одним путём).
                _fileGeneration.update { it + 1L }
                // Публичная копия готова: приватный файл прошлой версии больше
                // не нужен (воспроизведение всё равно предпочитает публичный).
                if (store.delete(url)) {
                    Log.i(TAG, "Удалён прежний приватный файл: $url")
                }
                Log.i(TAG, "Трек скачан в Download/VibraTuner: $url ($written байт)")
                true
            } catch (e: CancellationException) {
                // Отмена (удаление плейлиста/новый запуск) НИКОГДА не маскируется
                // под ошибку загрузки: cancelAndJoin должен дождаться выхода.
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Трек не скачан ($url): ${e.message}")
                if (ConnectionErrorClassifier.isConnectionError(cause = e)) onConnectionError()
                false
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка загрузки ($url): ${e.message}")
                if (ConnectionErrorClassifier.isConnectionError(cause = e)) onConnectionError()
                false
            } finally {
                if (!published) publicStore.discard(pending)
                try {
                    connection?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось закрыть соединение: ${e.message}")
                }
            }
        }
    }

    /** HTML/JSON с кодом 200 — это не аудио, сохранять такое нельзя. */
    private fun isAcceptableContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        val lower = contentType.lowercase()
        return !lower.startsWith("text/") &&
            !lower.contains("html") &&
            !lower.contains("json")
    }

    private companion object {
        const val TAG = "TrackDownloadManager"

        /** Таймауты как у остальной сети проекта (этап 4). */
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /** После стольких ошибок подряд загрузка плейлиста прекращается. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** Буфер копирования: достаточно крупный, чтобы не спамить UI. */
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        /** Не чаще этого интервала публикуем байтовый прогресс текущего файла. */
        const val PROGRESS_UPDATE_INTERVAL_MS = 200L
    }
}
