package com.personal.vibratuner.ui.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.download.DownloadableTrack
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.download.PlaylistDownloadState
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import com.personal.vibratuner.data.local.db.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние экрана одного плейлиста (этап 2).
 *
 * Читает через [com.personal.vibratuner.data.repository.PlaylistRepository] и Room,
 * но отдаёт наружу только [PlaylistScreenState]; воспроизведение запускается
 * ЕДИНСТВЕННЫМ существующим путём — метод [SoundPlaybackService.playQueue],
 * который использует тот же координатор и тот же плеер, что и обычная музыка.
 */
class PlaylistViewModel(
    private val app: VibraTunerApplication,
    private val playlistId: Long
) : ViewModel() {

    private val playlists = app.playlistRepository
    private val trackDao = app.database.trackDao()

    /** Библиотека для длительности и метаданных; порядок очереди задаёт сам плейлист. */
    private var libraryTracks: List<TrackEntity> = emptyList()

    /**
     * Длительности треков каталога из кеша манифеста (URL -> мс). Нужны, чтобы
     * строка плейлиста-копии каталога показывала длительность ДАЖЕ у ещё не
     * скачанного https-трека: в `tracks` его нет, а `duration` в манифесте есть.
     * Сеть НЕ трогается — только кеш; нет кеша/ошибка -> пустая карта.
     */
    private val catalogDurations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Длительности УЖЕ СКАЧАННЫХ файлов (URL -> мс, Stage 3) — источник для
     * треков каталога, скачанных только в составе плейлиста: самостоятельной
     * строки `tracks` у них нет, а `duration` в манифесте может отсутствовать.
     * Читается в фоне из самого файла, кешируется менеджером загрузок.
     */
    private val localDurations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Известные длительности https-треков: кеш манифеста + прочитанные из
     * скачанного файла (файл точнее — его значение перекрывает манифест).
     */
    private val knownDurations: Flow<Map<String, Long>> =
        combine(catalogDurations, localDurations) { catalog, local -> catalog + local }

    /**
     * Состояние экрана. Подписка живёт, пока экран видим
     * (`WhileSubscribed`): после закрытия экрана запросы к БД прекращаются.
     */
    val state: StateFlow<PlaylistScreenState> = combine(
        playlists.playlistsWithCounts(),
        playlists.tracksOf(playlistId),
        playlists.favoriteUris(),
        trackDao.getAllTracksFlow(),
        knownDurations
    ) { summaries, tracks, favoriteUris, library, durationsByUri ->
        libraryTracks = library
        val libraryByUri = library.associateBy { it.uri }
        val summary = summaries.firstOrNull { it.id == playlistId }
        PlaylistScreenState(
            title = summary?.name?.takeIf { it.isNotBlank() },
            isSystem = summary?.isSystem == true ||
                playlistId == PlaylistEntity.FAVORITES_PLAYLIST_ID,
            readOnly = false,
            isLoading = false,
            isMissing = summary == null,
            tracks = tracks.map { track ->
                PlaylistTrackUi(
                    trackUri = track.trackUri,
                    title = track.title,
                    artist = track.artist,
                    isFavorite = track.trackUri in favoriteUris,
                    // Длительность у локального плейлиста: сначала библиотека по
                    // тому же URI (импортированный трек), затем известные
                    // метаданные https-трека (манифест каталога, а для уже
                    // скачанного — длительность самого файла). 0 — неизвестна.
                    durationMs = libraryByUri[track.trackUri]?.durationMs
                        ?.takeIf { it > 0L }
                        ?: durationsByUri[track.trackUri]
                        ?: 0L
                )
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlaylistScreenState()
    )

    /** Очередь плейлиста — через существующий сервис (второго пути нет). */
    private val queuePlayer = PlaylistQueuePlayer(app, TAG)

    /**
     * Состояние РУЧНОЙ загрузки этого плейлиста: ключ задания — `playlists.id`,
     * тот же, что у индикатора в сетке «Моей коллекции». `null` — загрузка
     * не запускалась (или отменена/завершена и состояние снято).
     */
    val downloadState: StateFlow<PlaylistDownloadState?> =
        app.trackDownloads.states
            .map { it[PlaylistDownloadKeys.libraryPlaylist(playlistId)] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    init {
        queuePlayer.bind()
        loadCatalogDurations()
        resolveDownloadedDurations()
    }

    /**
     * Читает длительности каталога из кеша манифеста в фоне. Это существующие
     * метаданные (`AuthorTrack.durationMs`), схема Room не меняется. Любая
     * ошибка даёт пустую карту: строки без длительности просто не покажут её.
     */
    private fun loadCatalogDurations() {
        viewModelScope.launch {
            try {
                val cached = app.authorManifest.loadCached().orEmpty()
                catalogDurations.value = cached
                    .flatMap { it.tracks }
                    .mapNotNull { track ->
                        track.durationMs.takeIf { it > 0L }?.let { track.url to it }
                    }
                    .toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось прочитать длительности каталога из кеша: ${e.message}")
            }
        }
    }

    /**
     * Stage 3: дочитывает в фоне длительности уже скачанных файлов плейлиста.
     * Не блокирует UI; результат приходит отдельным состоянием и пересобирает
     * [state]. Перечитывается и после изменения набора файлов
     * ([com.personal.vibratuner.data.download.TrackDownloadManager.fileGeneration]),
     * поэтому скачанный на этом же экране трек сразу получает длительность.
     * Файла нет/ошибка — значение просто не добавляется, повторов нет.
     */
    private fun resolveDownloadedDurations() {
        viewModelScope.launch {
            try {
                combine(
                    playlists.tracksOf(playlistId),
                    app.trackDownloads.fileGeneration
                ) { tracks, _ -> tracks }.collect { tracks ->
                    val missing = tracks
                        .map { it.trackUri }
                        .filter { PlaylistDownloadKeys.isDownloadable(it) }
                        .filter { localDurations.value[it] == null }
                    if (missing.isEmpty()) return@collect
                    val resolved = app.trackDownloads.resolveLocalDurations(missing)
                    if (resolved.isEmpty()) return@collect
                    localDurations.update { it + resolved }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Длительности скачанных треков не прочитаны: ${e.message}")
            }
        }
    }

    /**
     * «Скачать»: догружает только отсутствующие треки (уже скачанные менеджер
     * пропускает по наличию файла). Загрузка живёт в IO-скоупе менеджера,
     * поэтому закрытие экрана её не прерывает.
     */
    fun downloadPlaylist() {
        val uris = state.value.tracks.map { it.trackUri }
        if (uris.isEmpty()) {
            Log.i(TAG, "Плейлист id=$playlistId пуст: скачивать нечего")
            return
        }
        // Вызов не блокирует главный поток: сама загрузка живёт в IO-скоупе
        // менеджера. Порядок «Скачать» -> «Отменить» сохраняется, потому что
        // обе команды попадают в очередь главного диспетчера по порядку.
        viewModelScope.launch {
            try {
                app.trackDownloads.startPlaylistDownload(
                    PlaylistDownloadKeys.libraryPlaylist(playlistId),
                    uris
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось начать скачивание плейлиста id=$playlistId: ${e.message}")
            }
        }
    }

    /**
     * «Отменить загрузку»: задание останавливается, недописанный файл текущего
     * трека удаляется менеджером, уже скачанные треки остаются. Отмена не
     * отменяется вместе с ViewModel — иначе прогресс мог бы «зависнуть».
     */
    fun cancelDownload() {
        viewModelScope.launch {
            // Отмена не должна отменяться вместе с ViewModel: иначе задание
            // могло бы продолжить запись, а прогресс остался бы в UI.
            withContext(NonCancellable) {
                try {
                    app.trackDownloads.cancelPlaylist(
                        PlaylistDownloadKeys.libraryPlaylist(playlistId)
                    )
                    Log.i(TAG, "Скачивание плейлиста id=$playlistId отменено")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Не удалось отменить скачивание плейлиста id=$playlistId: ${e.message}"
                    )
                }
            }
        }
    }

    /** Тап по строке: весь плейлист в сохранённом порядке, начиная с выбранного трека. */
    fun onTrackClick(track: PlaylistTrackUi) {
        startPlayback(startUri = track.trackUri)
    }

    /**
     * Stage 2: «Скачать» ОДИН трек из плейлиста каталога (в том числе копии в
     * «Моей коллекции»). Тот же менеджер и то же хранилище, что у загрузки
     * плейлиста: файл адресуется по URL и не дублируется, плейлист в
     * «Мою коллекцию» не добавляется, загрузка плейлиста не запускается.
     * После успеха менеджер сохраняет самостоятельную строку библиотеки.
     */
    fun downloadTrack(track: PlaylistTrackUi) {
        if (!PlaylistDownloadKeys.isDownloadable(track.trackUri)) {
            Log.i(TAG, "Трек не скачивается (не https): ${track.trackUri}")
            return
        }
        app.trackDownloads.downloadTrack(
            DownloadableTrack(
                url = track.trackUri,
                title = track.title,
                artist = track.artist?.takeIf { it.isNotBlank() },
                durationMs = track.durationMs
            )
        )
    }

    /**
     * Part 3, задача 1: «Удалить из памяти» ОДНОГО трека. Удаляется только
     * скачанный приложением файл; трек остаётся в плейлисте и библиотеке, его
     * признак «скачан» становится false (состояние выводится из файла, схемы
     * Room это не касается). Живой сервис сам остановит трек, если он играет —
     * второго пути воспроизведения нет.
     */
    fun deleteDownloadedTrack(track: PlaylistTrackUi) {
        if (!PlaylistDownloadKeys.isDownloadable(track.trackUri)) return
        viewModelScope.launch {
            try {
                app.trackDownloads.deleteDownloadedTracks(listOf(track.trackUri))
                Log.i(TAG, "Скачанный файл трека «${track.title}» удалён из памяти")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось удалить файл трека «${track.title}»: ${e.message}")
            }
        }
    }

    /**
     * Part 3, задача 2: «Удалить из памяти» для ВСЕГО плейлиста. Удаляются
     * только скачанные приложением файлы (https); импортированные файлы
     * пользователя не трогаются, связи и треки плейлиста остаются, кнопка
     * возвращается в «Скачать» после обновления `fileGeneration` менеджера.
     */
    fun deleteDownloadedPlaylist() {
        val uris = state.value.tracks.map { it.trackUri }
        if (!PlaylistDownloadKeys.hasDownloadableTracks(uris)) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    // Незавершённая загрузка не должна вернуть удалённый файл.
                    app.trackDownloads.cancelPlaylist(
                        PlaylistDownloadKeys.libraryPlaylist(playlistId)
                    )
                    app.trackDownloads.deleteDownloadedTracks(uris)
                    Log.i(
                        TAG,
                        "Скачанные файлы плейлиста id=$playlistId удалены: проверено ${uris.size}"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Не удалось удалить скачанные файлы плейлиста id=$playlistId: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Переименование плейлиста. Меняется ТОЛЬКО `playlists.name`: `catalogId`,
     * треки, `alarms.playlistId` и скачанные файлы привязаны к стабильному id
     * плейлиста и остаются прежними. Валидацию (trim, непустое, <= 50 символов)
     * повторяет репозиторий; системный плейлист он не даст переименовать.
     */
    fun renamePlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!playlists.renamePlaylist(playlistId, name)) {
                Log.w(TAG, "Плейлист id=$playlistId не переименован")
            }
        }
    }

    /** ♥ в строке: «Любимое» наполняется и очищается этой же кнопкой. */
    fun onFavoriteToggle(track: PlaylistTrackUi) {
        viewModelScope.launch(Dispatchers.IO) {
            playlists.toggleFavorite(
                PlaylistTrackInput(
                    trackUri = track.trackUri,
                    title = track.title,
                    artist = track.artist?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /**
     * «Удалить из плейлиста»: убирается ТОЛЬКО связь, без подтверждения и без
     * файлов на диске (для «Любимого» это та же связь, что снимает ♥).
     * Если удаляется активный трек, воспроизведение не трогаем: очередь сервиса —
     * снимок, поэтому паузы/зависания не возникает; случай пишется в лог.
     */
    fun onRemoveTrack(track: PlaylistTrackUi, activeTrackUri: String? = null) {
        if (activeTrackUri != null && activeTrackUri == track.trackUri) {
            Log.i(
                TAG,
                "Удаляется активный трек из плейлиста id=$playlistId: " +
                    "очередь сервиса — снимок, воспроизведение не прерывается"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!playlists.removeTrackFromPlaylist(playlistId, track.trackUri)) {
                Log.w(TAG, "Трек для удаления не найден в плейлисте id=$playlistId")
            }
        }
    }

    /**
     * Упорядоченный список треков для координатора. Треки плейлиста — снимки:
     * если трека уже нет в библиотеке, он всё равно пробуется по сохранённому URI
     * (файл мог остаться доступным); недоступный файл пропустит сам сервис с логом.
     */
    private fun buildPlayableQueue(): List<TrackEntity> {
        val libraryByUri = libraryTracks.associateBy { it.uri }
        return state.value.tracks.mapIndexed { index, track ->
            libraryByUri[track.trackUri] ?: TrackEntity(
                // Отрицательные id — синтетические строки плейлиста, в БД их нет;
                // так они никогда не совпадут с id библиотеки в UI других экранов.
                id = -1L - index,
                uri = track.trackUri,
                title = track.title,
                artist = track.artist.orEmpty(),
                // Длительность из UI-снимка (библиотека или кеш манифеста):
                // мини-плеер и MediaMetadata получают её сразу, без ожидания плеера.
                durationMs = track.durationMs
            )
        }
    }

    private fun startPlayback(startUri: String) {
        val queue = buildPlayableQueue()
        if (queue.isEmpty()) {
            Log.i(TAG, "Плейлист id=$playlistId пуст: воспроизведение не запущено")
            return
        }
        val startIndex = queue.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)
        Log.i(
            TAG,
            "Запуск плейлиста id=$playlistId: треков ${queue.size}, индекс старта $startIndex"
        )
        queuePlayer.playQueue(queue, startIndex)
    }

    override fun onCleared() {
        queuePlayer.unbind()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlaylistViewModel"
    }
}
