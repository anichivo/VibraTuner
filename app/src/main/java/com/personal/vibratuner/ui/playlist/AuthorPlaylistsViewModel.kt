package com.personal.vibratuner.ui.playlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.core.config.AppConfig
import com.personal.vibratuner.data.download.DownloadableTrack
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.download.PlaylistDownloadState
import com.personal.vibratuner.data.local.db.CatalogPlaylistInput
import com.personal.vibratuner.data.local.db.PlaylistNameValidator
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.remote.AuthorManifestLoadResult
import com.personal.vibratuner.data.remote.AuthorManifestPlaylist
import com.personal.vibratuner.data.remote.AuthorManifestRepository
import com.personal.vibratuner.data.repository.CatalogCopyRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Состояние каталога «От автора» вкладки «Каталог» (этап 4, вкладка части 2).
 *
 * Загрузка — один раз при первом открытии вкладки в сессии приложения
 * ([loadIfNeeded]) плюс вручную кнопкой «обновить» ([refresh]); автоматических
 * повторов нет. Сеть и кеш — [AuthorManifestRepository].
 *
 * Воспроизведение — только через существующий [SoundPlaybackService.playQueue]
 * (помощник [PlaylistQueuePlayer]): тех же треков в БД нет, поэтому очередь
 * строится из манифеста синтетическими [TrackEntity] с https-адресами.
 */
class AuthorPlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication
    private val repository = AuthorManifestRepository(
        manifestUrl = AppConfig.AUTHOR_MANIFEST_URL,
        cacheFile = File(app.filesDir, AuthorManifestRepository.CACHE_FILE_NAME)
    )
    private val queuePlayer = PlaylistQueuePlayer(app, TAG)

    private val _state = MutableStateFlow(AuthorPlaylistsUiState())
    val state: StateFlow<AuthorPlaylistsUiState> = _state.asStateFlow()

    /**
     * Stage 2: готовые PNG-иконки каталога (ключ — разрешённый URL из
     * `AuthorManifestPlaylist.icon`). Обновляется после загрузки каталога,
     * поэтому карточки перерисовываются сами, как только иконка скачалась.
     */
    val iconFiles: StateFlow<Map<String, File>> = app.playlistIcons.files

    /**
     * Stage 3: адреса иконок каталога по стабильному `catalogId` плейлиста.
     * Нужны карточкам «Моей коллекции»: копия каталога связана с манифестом
     * только через `playlists.catalogId`, отдельной колонки в Room нет.
     * Заполняется из загруженного манифеста (сеть или кеш).
     */
    private val _catalogIconUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val catalogIconUrls: StateFlow<Map<String, String>> = _catalogIconUrls.asStateFlow()

    private val _copyEvent = MutableStateFlow<AuthorCopyEvent?>(null)

    /** Результат «Добавить к себе»: ровно одно событие для Snackbar. */
    val copyEvent: StateFlow<AuthorCopyEvent?> = _copyEvent.asStateFlow()

    /**
     * Stage 3: длительности уже скачанных файлов каталога (URL -> мс). Нужны
     * строкам read-only плейлиста «От автора»: необязательного поля `duration`
     * в манифесте может не быть, а скачанный файл знает свою длительность.
     * Читается в фоне только для открытого плейлиста и кешируется менеджером.
     */
    private val _localDurations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val localDurations: StateFlow<Map<String, Long>> = _localDurations.asStateFlow()

    /**
     * Дочитывает в фоне длительности скачанных файлов открытого плейлиста.
     * Не блокирует UI; ошибки только логируются, отсутствующие файлы просто
     * не попадают в карту (длительность тогда не показывается).
     */
    fun resolveDurations(urls: Collection<String>) {
        val missing = urls
            .filter { PlaylistDownloadKeys.isDownloadable(it) }
            .filter { _localDurations.value[it] == null }
            .distinct()
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val resolved = app.trackDownloads.resolveLocalDurations(missing)
                if (resolved.isNotEmpty()) {
                    _localDurations.update { it + resolved }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Длительности скачанных файлов каталога не прочитаны: ${e.message}")
            }
        }
    }

    /**
     * id плейлистов каталога, уже добавленных в библиотеку.
     *
     * ЕДИНСТВЕННЫЙ источник — фактическое наличие плейлиста с этим стабильным
     * `playlists.catalogId` ([PlaylistRepository.observeAddedCatalogIds]):
     * удалили плейлист из библиотеки — Flow сразу убирает id и каталог снова
     * показывает «Добавить к себе». Эвристики по совпадению треков/имени больше
     * нет: именно она давала ложное «Добавлено» без плейлиста в библиотеке.
     * У копий, созданных до версии БД 10 (`catalogId = NULL`), признак
     * восстанавливается ремонтом при загрузке каталога ([repairLegacyCatalogCopies]).
     */
    val addedPlaylistIds: StateFlow<Set<String>> =
        app.playlistRepository.observeAddedCatalogIds()
            .stateIn(
                scope = viewModelScope,
                // Данные библиотеки читаются сразу: к открытию экрана «От автора»
                // признак «добавлен» уже готов и баннер не мелькает.
                started = SharingStarted.Eagerly,
                initialValue = emptySet()
            )

    /**
     * `catalogId -> id локальной копии`: у добавленного плейлиста скачивание
     * идёт под тем же ключом, что и в сетке «Моей коллекции», поэтому прогресс
     * виден в обоих местах, а второй параллельной загрузки не появляется.
     */
    val catalogLocalIds: StateFlow<Map<String, Long>> =
        app.playlistRepository.observeLibraryPlaylists()
            .map { summaries ->
                summaries.mapNotNull { summary ->
                    summary.catalogId?.let { it to summary.id }
                }.toMap()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )

    /**
     * Состояния скачивания (ключ — [PlaylistDownloadKeys]): экран «От автора»
     * показывает по ним «Скачивается…»/«Отменить загрузку». Загрузка — ТОЛЬКО
     * ручная кнопкой «Скачать», добавление в библиотеку её не запускает.
     */
    val downloadStates: StateFlow<Map<Long, PlaylistDownloadState>> =
        app.trackDownloads.states

    private var copyEventCounter = 0L
    private var loadRequested = false
    private var cachedIconsRequested = false

    init {
        queuePlayer.bind()
    }

    /** Первое открытие вкладки: одна попытка за сессию, дальше — только вручную. */
    fun loadIfNeeded() {
        if (loadRequested) return
        loadRequested = true
        load()
    }

    /**
     * Stage 3: адреса иконок копий каталога из СОХРАНЁННОГО манифеста, без сети.
     * Вызывается при открытии «Моей коллекции»: уже скачанные PNG показываются
     * сразу после холодного старта/в офлайне. Сеть не трогается — следующее
     * обновление каталога по-прежнему только по кнопке «обновить».
     */
    fun loadCachedIconsIfNeeded() {
        if (cachedIconsRequested) return
        cachedIconsRequested = true
        viewModelScope.launch {
            try {
                // Свежий каталог важнее: не перетираем его устаревшим кешем.
                if (_state.value.hasLoaded) return@launch
                val playlists = repository.loadCached() ?: return@launch
                if (_state.value.hasLoaded) return@launch
                publishCatalogIcons(playlists)
                // Только уже скачанные файлы: в офлайне запросов нет.
                app.playlistIcons.sync(
                    references = playlists.mapNotNull { playlist -> playlist.icon },
                    allowDownloads = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Кеш иконок каталога не прочитан: ${e.message}")
            }
        }
    }

    /** Кнопка «обновить» и «Повторить». */
    fun refresh() {
        loadRequested = true
        load()
    }

    private fun load() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val result = repository.load()
            // Самовосстановление выполняется ДО публикации каталога: старая копия
            // получает catalogId раньше, чем пользователь увидит карточку, поэтому
            // «Добавлено» не мигает и второй раз добавить нельзя.
            if (result is AuthorManifestLoadResult.Data) {
                repairLegacyCatalogCopies(result.playlists)
            }
            _state.update { current ->
                when (result) {
                    is AuthorManifestLoadResult.Data -> current.copy(
                        isLoading = false,
                        hasLoaded = true,
                        playlists = result.playlists,
                        loadFailed = false,
                        cacheHint = result.fromCache
                    )

                    is AuthorManifestLoadResult.Failed -> current.copy(
                        isLoading = false,
                        hasLoaded = false,
                        playlists = emptyList(),
                        loadFailed = true,
                        cacheHint = false
                    )
                }
            }
            if (result is AuthorManifestLoadResult.Data) {
                // Stage 3: адреса иконок для карточек копий обновляются ДО
                // скачивания файлов, поэтому после появления PNG карточка
                // перерисовывается без повторного разбора манифеста.
                publishCatalogIcons(result.playlists)
                syncCatalogIcons(result.playlists, allowDownloads = !result.fromCache)
            }
        }
    }

    /** catalogId -> разрешённый https-адрес иконки; плейлисты без `icon` пропускаются. */
    private fun publishCatalogIcons(playlists: List<AuthorManifestPlaylist>) {
        _catalogIconUrls.value = playlists
            .mapNotNull { playlist ->
                playlist.icon?.let { icon -> playlist.id to icon }
            }
            .toMap()
    }

    /**
     * Stage 2: скачивание иконок каталога СРАЗУ после успешного обновления
     * (сеть, не кеш). Сеть и диск — внутри `PlaylistIconCache` на IO-диспетчере,
     * главный поток не блокируется; ошибки иконок не влияют на каталог.
     */
    private suspend fun syncCatalogIcons(
        playlists: List<AuthorManifestPlaylist>,
        allowDownloads: Boolean
    ) {
        try {
            app.playlistIcons.sync(
                references = playlists.mapNotNull { playlist -> playlist.icon },
                allowDownloads = allowDownloads
            )
        } catch (e: CancellationException) {
            // Отмена (ViewModel очищена) — не ошибка: выходим молча.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обновить иконки каталога: ${e.message}")
        }
    }

    /**
     * Ремонт данных уже затронутых пользователей: если плейлист каталога был
     * добавлен до версии БД 10 (`playlists.catalogId = NULL`), ему записывается
     * стабильный id из манифеста. Только `UPDATE` пустого `catalogId`; существующие
     * плейлисты, треки и id не меняются, ничего не удаляется.
     */
    private suspend fun repairLegacyCatalogCopies(authorPlaylists: List<AuthorManifestPlaylist>) {
        try {
            val copies = app.playlistRepository.getCatalogCopyCandidates()
            if (copies.isEmpty()) return
            val assignments = CatalogCopyRepair.assignments(authorPlaylists, copies)
            assignments.forEach { assignment ->
                val restored = app.playlistRepository.assignCatalogId(
                    playlistId = assignment.playlistId,
                    catalogId = assignment.catalogId
                )
                if (restored) {
                    Log.i(
                        TAG,
                        "Самовосстановление каталога: плейлисту id=${assignment.playlistId} " +
                            "присвоен catalogId=${assignment.catalogId}"
                    )
                }
            }
            if (assignments.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Самовосстановление каталога: проверено копий ${copies.size}, " +
                        "восстановлено ${assignments.size}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось выполнить самовосстановление каталога: ${e.message}")
        }
    }

    /**
     * «Добавить к себе»: `addCatalogPlaylist` сохраняет стабильный id каталога
     * (`catalogId`) вместе с именем и треками одной транзакцией; повторное
     * добавление того же каталога данных не меняет. Новый локальный плейлист
     * сразу появляется в карусели «Моей медиатеки» через Flow репозитория.
     */
    fun addPlaylistToLibrary(playlist: AuthorManifestPlaylist) {
        viewModelScope.launch {
            // Ошибка БД/диска не должна ронять ни процесс, ни UI: результат
            // ОБЯЗАН дойти до пользователя (Snackbar) в любом случае.
            val success = try {
                if (PlaylistNameValidator.validate(playlist.name) == null) {
                    Log.w(TAG, "Плейлист «${playlist.name}» не скопирован: имя не проходит валидацию")
                    false
                } else {
                    val tracks = playlist.tracks.map { track ->
                        PlaylistTrackInput(
                            trackUri = track.url,
                            title = track.title,
                            artist = track.artist
                        )
                    }
                    val newId = app.playlistRepository.addCatalogPlaylist(
                        CatalogPlaylistInput(
                            catalogId = playlist.id,
                            name = playlist.name,
                            tracks = tracks
                        )
                    )
                    val created = newId > 0L
                    if (created) {
                        Log.i(
                            TAG,
                            "Плейлист «${playlist.name}» добавлен из каталога: id=$newId, " +
                                "треков ${tracks.size} (скачивание не запускается: оно ручное)"
                        )
                    } else {
                        Log.w(TAG, "Плейлист «${playlist.name}» не скопирован")
                    }
                    created
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка добавления плейлиста «${playlist.name}»: ${e.message}", e)
                false
            }
            _copyEvent.value = AuthorCopyEvent(
                eventId = ++copyEventCounter,
                playlistName = playlist.name,
                success = success
            )
        }
    }

    fun consumeCopyEvent() {
        _copyEvent.value = null
    }

    /**
     * Stage 2: «Скачать» ОДИН трек read-only плейлиста «От автора». Тот же
     * менеджер и то же хранилище, что у загрузки плейлиста; плейлист при этом
     * НЕ добавляется в «Мою коллекцию» и его загрузка не запускается.
     * После успеха трек становится самостоятельной строкой библиотеки.
     */
    fun downloadTrack(track: PlaylistTrackUi) {
        if (!PlaylistDownloadKeys.isDownloadable(track.trackUri)) {
            Log.i(TAG, "Трек «${track.title}» не скачивается (не https)")
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
     * Part 3, задача 1: «Удалить из памяти» одного трека read-only плейлиста
     * «От автора». Удаляется только скачанный приложением файл; трек остаётся
     * в списке и продолжает играть из сети. Играющий трек останавливает живой
     * сервис существующим путём координатора.
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
     * Part 3, задача 2: «Удалить из памяти» для плейлиста каталога целиком.
     * Файлы удаляются по https-адресам манифеста, библиотека и сам плейлист
     * не меняются; импортированные файлы пользователя не затрагиваются.
     */
    fun deleteDownloadedPlaylist(playlist: AuthorManifestPlaylist) {
        val urls = playlist.tracks.map { it.url }
        if (!PlaylistDownloadKeys.hasDownloadableTracks(urls)) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    app.trackDownloads.cancelPlaylist(downloadKeyFor(playlist))
                    app.trackDownloads.deleteDownloadedTracks(urls)
                    Log.i(
                        TAG,
                        "Скачанные файлы плейлиста «${playlist.name}» удалены: " +
                            "проверено ${urls.size}"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Не удалось удалить скачанные файлы «${playlist.name}»: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Ключ задания скачивания для плейлиста каталога: у добавленной копии это
     * id локального плейлиста (общий прогресс с сеткой «Моей коллекции»),
     * у недобавленного — стабильный отрицательный ключ каталога.
     */
    fun downloadKeyFor(playlist: AuthorManifestPlaylist): Long {
        val localId = catalogLocalIds.value[playlist.id]
        return if (localId != null) {
            PlaylistDownloadKeys.libraryPlaylist(localId)
        } else {
            PlaylistDownloadKeys.catalogPlaylist(playlist.id)
        }
    }

    /**
     * «Скачать» на экране «От автора»: качает отсутствующие треки плейлиста
     * каталога, НЕ добавляя его в библиотеку. Уже скачанные треки (в том числе
     * скачанные раньше) пропускаются менеджером — повторной загрузки нет.
     */
    fun downloadCatalogPlaylist(playlist: AuthorManifestPlaylist) {
        val urls = playlist.tracks.map { it.url }
        if (!PlaylistDownloadKeys.hasDownloadableTracks(urls)) {
            Log.i(TAG, "Плейлист «${playlist.name}»: нет треков для скачивания")
            return
        }
        viewModelScope.launch {
            try {
                app.trackDownloads.startPlaylistDownload(downloadKeyFor(playlist), urls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось начать скачивание «${playlist.name}»: ${e.message}")
            }
        }
    }

    /**
     * «Отменить загрузку»: задание останавливается, недописанный файл текущего
     * трека удаляется менеджером, скачанные треки остаются на месте.
     * Отмена не отменяется вместе с ViewModel — иначе задание могло бы
     * продолжить запись и прогресс «завис» бы.
     */
    fun cancelCatalogDownload(playlist: AuthorManifestPlaylist) {
        val key = downloadKeyFor(playlist)
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    app.trackDownloads.cancelPlaylist(key)
                    Log.i(TAG, "Скачивание «${playlist.name}» отменено")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось отменить скачивание «${playlist.name}»: ${e.message}")
                }
            }
        }
    }

    /** Тап по треку: существующий путь списка в сервисе, старт с выбранного URL. */
    fun playAuthorPlaylist(playlist: AuthorManifestPlaylist, startUrl: String) {
        val queue = playlist.tracks.mapIndexed { index, track ->
            TrackEntity(
                // Отрицательные id — синтетические строки манифеста, в БД их нет.
                id = -1L - index,
                uri = track.url,
                title = track.title,
                artist = track.artist.orEmpty(),
                // Длительность манифеста доступна сразу: мини-плеер и
                // MediaMetadata получают её без ожидания плеера.
                durationMs = track.durationMs
            )
        }
        if (queue.isEmpty()) {
            Log.i(TAG, "Плейлист «${playlist.name}» пуст: воспроизведение не запущено")
            return
        }
        val startIndex = queue.indexOfFirst { it.uri == startUrl }.coerceAtLeast(0)
        Log.i(
            TAG,
            "Запуск плейлиста «От автора» «${playlist.name}»: " +
                "треков ${queue.size}, индекс старта $startIndex"
        )
        queuePlayer.playQueue(queue, startIndex)
    }

    override fun onCleared() {
        queuePlayer.unbind()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AuthorPlaylistsVM"
    }
}

/** Состояние блока «От автора». */
data class AuthorPlaylistsUiState(
    val isLoading: Boolean = false,
    /** Есть данные (из сети или кеша), которые можно показать. */
    val hasLoaded: Boolean = false,
    val playlists: List<AuthorManifestPlaylist> = emptyList(),
    /** Нет ни сети, ни кеша: текст ошибки и «Повторить». */
    val loadFailed: Boolean = false,
    /** Показан кеш после неудачного обновления: строка-подсказка в UI. */
    val cacheHint: Boolean = false
)

/** Одно событие копирования для Snackbar. */
data class AuthorCopyEvent(
    val eventId: Long,
    val playlistName: String,
    val success: Boolean
)
