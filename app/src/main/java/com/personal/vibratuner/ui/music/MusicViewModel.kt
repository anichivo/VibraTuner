package com.personal.vibratuner.ui.music

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import com.personal.vibratuner.data.local.saf.SafTrackImporter
import com.personal.vibratuner.data.local.saf.TrackImportInserter
import com.personal.vibratuner.service.SoundPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

data class MusicUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    /** Режим порядка воспроизведения из координатора: OFF / SHUFFLE / REPEAT_ONE. */
    val playbackMode: PlaybackMode = PlaybackMode.OFF,
    /** Нормализованные URI треков в «Избранном»: дешёвая проверка для ♥. */
    val favoriteUris: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val lastImportResult: ImportResult? = null,
    val showLongPressHint: Boolean = false,
    /** null — состояние ещё загружается; иначе показывалась ли подсказка фильтра «Скачанные». */
    val downloadedFilterHintShown: Boolean? = null,
    /** null — состояние ещё загружается; иначе показывался ли диалог разрешения. */
    val notificationPermissionPromptShown: Boolean? = null
)

/**
 * Position and duration are kept separately from the screen-wide state so that
 * the one-second progress ticker recomposes only the slider and its time labels.
 * pendingSeekFraction is used only while the duration is not known yet.
 */
data class PlaybackProgressUiState(
    val trackId: Long? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val pendingSeekFraction: Float? = null
)

/**
 * Результат добавления файлов: сколько записей вставлено и сколько пропущено
 * как уже существующие (дубли по каноническому ключу файла, см. [TrackIdentity]).
 */
data class ImportResult(
    val added: Int,
    val skipped: Int,
    val eventId: Long = System.nanoTime()
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication
    private val trackDao = app.database.trackDao()
    private val playlists = app.playlistRepository
    private val preferences = app.preferencesDataStore

    /** Единая точка вставки импортированных треков (см. [TrackImportInserter]). */
    private val trackInserter = TrackImportInserter(trackDao)

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgressUiState())
    val playbackProgress: StateFlow<PlaybackProgressUiState> = _playbackProgress.asStateFlow()

    /**
     * Одноразовое событие «прокрутить открытый список к играющему треку»:
     * его отправляет тело мини-плеера, а наблюдает тот список, который сейчас
     * на экране. Без replay и без состояния: если списка нет («Микрозвуки»,
     * «Расписание», «Настройки») или трека нет в списке, событие просто
     * пропадает — без навигации и без сообщений.
     */
    private val _scrollToActiveTrack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToActiveTrack: SharedFlow<Unit> = _scrollToActiveTrack.asSharedFlow()

    /**
     * URI трека, который сейчас предпроигрывается из окна выбора мелодии
     * (Stage 2), либо `null`. Значение приходит ТОЛЬКО из координатора сервиса —
     * своего состояния предпрослушивания UI не хранит.
     */
    private val _previewTrackUri = MutableStateFlow<String?>(null)
    val previewTrackUri: StateFlow<String?> = _previewTrackUri.asStateFlow()

    /**
     * Можно ли сейчас начать предпрослушивание (покой или музыка на паузе).
     * Играет музыка/микрозвук/сеанс — кнопка в строке выбора недоступна.
     */
    private val _previewAvailable = MutableStateFlow(true)
    val previewAvailable: StateFlow<Boolean> = _previewAvailable.asStateFlow()

    /**
     * Версия события «сбой соединения при воспроизведении сетевого потока»
     * (Part 3). Растёт в сервисе только по результату
     * [com.personal.vibratuner.core.network.ConnectionErrorClassifier]; UI
     * показывает одно сообщение на каждое новое значение.
     */
    private val _connectionErrorVersion = MutableStateFlow(0L)
    val connectionErrorVersion: StateFlow<Long> = _connectionErrorVersion.asStateFlow()

    /**
     * Приветственный диалог первого запуска (тексты — `help_texts.xml`):
     * `null` — флаг ещё не прочитан из DataStore, `false` — диалог ещё не
     * показывался, `true` — уже закрыт. Отдельный Flow, а не поле [MusicUiState]:
     * корневой `MainScreenLayout` читает его и НЕ должен перерисовываться на
     * каждое изменение большого состояния экрана «Звуки».
     */
    private val _welcomeShown = MutableStateFlow<Boolean?>(null)
    val welcomeShown: StateFlow<Boolean?> = _welcomeShown.asStateFlow()

    /**
     * Правила первого запуска (тексты — `help_texts.xml`): `null` — флаг ещё не
     * прочитан из DataStore, `false` — правила ещё не приняты (окно обязательное),
     * `true` — приняты. Отдельный Flow по той же причине, что [welcomeShown].
     */
    private val _termsAccepted = MutableStateFlow<Boolean?>(null)
    val termsAccepted: StateFlow<Boolean?> = _termsAccepted.asStateFlow()

    private var playbackService: SoundPlaybackService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SoundPlaybackService.LocalBinder
            playbackService = binder?.getService()
            isServiceBound = true

            // Подписка на состояние сервиса
            viewModelScope.launch {
                playbackService?.serviceState?.collectLatest { serviceState ->
                    val newTrackId = serviceState.currentTrack?.id
                    val trackChanged = _uiState.value.currentTrack?.id != newTrackId
                    _uiState.value = _uiState.value.copy(
                        currentTrack = serviceState.currentTrack,
                        isPlaying = serviceState.isPlaying
                    )
                    if (trackChanged) {
                        // Never leave the previous track's position on the new track.
                        _playbackProgress.value = PlaybackProgressUiState(
                            trackId = newTrackId,
                            durationMs = serviceState.currentTrack?.durationMs
                                ?.takeIf { it > 0L } ?: 0L
                        )
                    }
                    refreshPlaybackPosition()
                }
            }

            // Режим порядка живёт в координаторе: UI только наблюдает за ним.
            viewModelScope.launch {
                playbackService?.playbackMode?.collectLatest { mode ->
                    _uiState.value = _uiState.value.copy(playbackMode = mode)
                }
            }

            // Предпрослушивание из окна выбора мелодии: состояние — из координатора.
            viewModelScope.launch {
                playbackService?.previewTrackUri?.collectLatest { uri ->
                    _previewTrackUri.value = uri
                }
            }
            viewModelScope.launch {
                playbackService?.previewAvailable?.collectLatest { available ->
                    _previewAvailable.value = available
                }
            }

            // Part 3: сбой соединения при воспроизведении сетевого потока —
            // версия события из того же сервиса, второго источника нет.
            viewModelScope.launch {
                playbackService?.connectionErrorVersion?.collectLatest { version ->
                    _connectionErrorVersion.value = version
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            _previewTrackUri.value = null
            _previewAvailable.value = true
            _playbackProgress.value = PlaybackProgressUiState()
            _uiState.value = _uiState.value.copy(playbackMode = PlaybackMode.OFF)
        }
    }

    init {
        // Подписка на треки из Room
        viewModelScope.launch {
            trackDao.getAllTracksFlow().collectLatest { trackList ->
                _uiState.value = _uiState.value.copy(tracks = trackList)
            }
        }

        // ♥ в списке: набор избранных URI обновляется после каждого переключения
        viewModelScope.launch {
            playlists.favoriteUris().collectLatest { favoriteUris ->
                _uiState.value = _uiState.value.copy(favoriteUris = favoriteUris)
            }
        }

        // Подсказка про долгое нажатие видна, пока будильник так ещё не создавали
        viewModelScope.launch {
            preferences.alarmLongPressHintShownFlow.collectLatest { shown ->
                _uiState.value = _uiState.value.copy(showLongPressHint = !shown)
            }
        }

        // Запрос POST_NOTIFICATIONS показывается только один раз
        viewModelScope.launch {
            preferences.notificationPermissionPromptShownFlow.collectLatest { shown ->
                _uiState.value = _uiState.value.copy(
                    notificationPermissionPromptShown = shown
                )
            }
        }

        // Stage 3: подсказка в фильтре «Скачанные» показывается только один раз
        viewModelScope.launch {
            preferences.downloadedFilterHintShownFlow.collectLatest { shown ->
                _uiState.value = _uiState.value.copy(downloadedFilterHintShown = shown)
            }
        }

        // Приветственный диалог первого запуска показывается один раз
        viewModelScope.launch {
            preferences.welcomeShownFlow.collectLatest { shown ->
                _welcomeShown.value = shown
            }
        }

        // Правила первого запуска показываются после приветствия, пока не приняты
        viewModelScope.launch {
            preferences.termsAcceptedFlow.collectLatest { accepted ->
                _termsAccepted.value = accepted
            }
        }

        bindPlaybackService()
    }

    private fun bindPlaybackService() {
        val intent = Intent(app, SoundPlaybackService::class.java)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureServiceStarted(action: String, trackId: Long = -1L) {
        val intent = SoundPlaybackService.startServiceIntent(app, action, trackId)
        PlatformCompat.startForegroundServiceSafely(app, intent)
    }

    fun onTrackClick(track: TrackEntity) {
        ensureServiceStarted(SoundPlaybackService.ACTION_PLAY, track.id)
        playbackService?.playTrack(track)
    }

    /**
     * Тап по строке трека (Part 3): активный трек переключает Play <-> Pause
     * существующей командой координатора, остальные строки запускают трек как
     * раньше. Активность определяется по стабильному `TrackEntity.uri` —
     * тому же значению, что у строк плейлистов.
     */
    fun onTrackRowClick(track: TrackEntity) {
        val activeUri = _uiState.value.currentTrack?.uri
        if (activeUri != null && activeUri == track.uri) {
            onPlayPauseClick()
        } else {
            onTrackClick(track)
        }
    }

    /** Тап по телу мини-плеера: показать играющий трек в открытом списке. */
    fun onMiniPlayerBodyClick() {
        _scrollToActiveTrack.tryEmit(Unit)
    }

    fun onPlayPauseClick() {
        if (_uiState.value.isPlaying) {
            playbackService?.pause() ?: ensureServiceStarted(SoundPlaybackService.ACTION_PAUSE)
        } else {
            val current = _uiState.value.currentTrack
            if (current != null) {
                playbackService?.resume() ?: ensureServiceStarted(SoundPlaybackService.ACTION_PLAY, current.id)
            } else if (_uiState.value.tracks.isNotEmpty()) {
                val first = _uiState.value.tracks.first()
                playbackService?.playTrack(first) ?: ensureServiceStarted(SoundPlaybackService.ACTION_PLAY, first.id)
            }
        }
    }

    fun onNextClick() {
        playbackService?.next() ?: ensureServiceStarted(SoundPlaybackService.ACTION_NEXT)
    }

    /**
     * Предпрослушивание звука из ОБЩЕГО окна выбора (трек, встроенный микрозвук
     * или свой файл): команда уходит в существующий координатор сервиса тем же
     * путём, что и раньше. Если сервис ещё не привязан (практически невозможно —
     * привязка живёт с Activity), пишем в лог и ничего не ломаем: окно остаётся
     * без звука, второй путь воспроизведения не создаётся.
     */
    fun onPreviewToggle(uri: String, title: String) {
        val service = playbackService
        if (service == null) {
            Log.i(TAG, "Предпрослушивание недоступно: сервис ещё не подключён")
            return
        }
        if (uri.isBlank()) return
        service.togglePreview(TrackEntity(uri = uri, title = title))
    }

    /** Остановить предпрослушивание (закрытие окна выбора, выбор трека, уход в фон). */
    fun onPreviewStop() {
        playbackService?.stopPreview()
    }

    /**
     * «Предыдущий» (этап 3): решение о перезапуске/переходе по истории принимает
     * координатор, UI только отправляет команду.
     */
    fun onPreviousClick() {
        playbackService?.previous() ?: ensureServiceStarted(SoundPlaybackService.ACTION_PREVIOUS)
    }

    /** Одна кнопка режима: OFF -> SHUFFLE -> REPEAT_ONE -> OFF (состояние у координатора). */
    fun onPlaybackModeClick() {
        playbackService?.cyclePlaybackMode()
            ?: ensureServiceStarted(SoundPlaybackService.ACTION_CYCLE_PLAYBACK_MODE)
    }

    /**
     * Updates only the leaf progress state. Called by the slider's lifecycle-aware
     * ticker; it never touches track metadata or the rest of the music screen.
     */
    fun refreshPlaybackPosition() {
        // Во время предпрослушивания в окне выбора мелодии позиция плеера
        // относится к другому треку — полоса мини-плеера её не показывает.
        if (_previewTrackUri.value != null) return
        val track = _uiState.value.currentTrack
        if (track == null) {
            if (_playbackProgress.value.trackId != null) {
                _playbackProgress.value = PlaybackProgressUiState()
            }
            return
        }

        val snapshot = playbackService?.getPlaybackPosition() ?: return
        val currentProgress = _playbackProgress.value
        val sameTrack = currentProgress.trackId == track.id
        val duration = snapshot.durationMs.takeIf { it > 0L }
            ?: track.durationMs.takeIf { it > 0L }
            ?: currentProgress.durationMs.takeIf { sameTrack && it > 0L }
            ?: 0L

        // ExoPlayer can briefly report zeros while it is buffering after a seek.
        // Keep the last known position instead of flashing back to the start.
        val snapshotLooksTransient = sameTrack &&
            snapshot.durationMs <= 0L &&
            snapshot.positionMs == 0L &&
            currentProgress.durationMs > 0L &&
            currentProgress.positionMs > 0L
        var position = if (snapshotLooksTransient) {
            currentProgress.positionMs
        } else {
            snapshot.positionMs.coerceAtLeast(0L)
        }
        if (duration > 0L) {
            position = position.coerceIn(0L, duration)
        }

        val pendingFraction = currentProgress.pendingSeekFraction.takeIf { sameTrack }
        if (pendingFraction != null && duration > 0L) {
            val target = (duration * pendingFraction.toDouble()).roundToLong()
                .coerceIn(0L, duration)
            if (playbackService?.seekTo(target) == true) {
                _playbackProgress.value = PlaybackProgressUiState(
                    trackId = track.id,
                    positionMs = target,
                    durationMs = duration,
                    pendingSeekFraction = null
                )
                return
            }
        }

        val next = PlaybackProgressUiState(
            trackId = track.id,
            positionMs = position,
            durationMs = duration,
            pendingSeekFraction = pendingFraction
        )
        if (next != currentProgress) {
            _playbackProgress.value = next
        }
    }

    /**
     * A slider gesture is converted to one seek only when the gesture finishes.
     * If duration is still unknown, the fraction is remembered and applied as
     * soon as the duration becomes available. Seek never starts playback.
     */
    fun onSeekFraction(fraction: Float) {
        val track = _uiState.value.currentTrack ?: return
        val safeFraction = fraction.coerceIn(0f, 1f)
        val currentProgress = _playbackProgress.value
        val duration = currentProgress.durationMs.takeIf {
            currentProgress.trackId == track.id && it > 0L
        } ?: track.durationMs.takeIf { it > 0L } ?: 0L
        val progressForTrack = if (currentProgress.trackId == track.id) {
            currentProgress.copy(durationMs = duration)
        } else {
            PlaybackProgressUiState(trackId = track.id, durationMs = duration)
        }

        if (duration > 0L) {
            val target = (duration * safeFraction.toDouble()).roundToLong()
                .coerceIn(0L, duration)
            // Update the UI only when the coordinator accepted the seek.
            if (playbackService?.seekTo(target) == true) {
                _playbackProgress.value = progressForTrack.copy(
                    positionMs = target,
                    pendingSeekFraction = null
                )
            }
        } else {
            _playbackProgress.value = progressForTrack.copy(
                positionMs = 0L,
                pendingSeekFraction = safeFraction
            )
        }
    }

    fun onDeleteTrack(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Удаление записи БД не прерывает воспроизведение: сервис/координатор
            // держат снимок трека. Случай активного трека только логируем — файл
            // пользователя и текущая очередь не трогаются.
            if (_uiState.value.currentTrack?.id == track.id) {
                Log.i(
                    TAG,
                    "Удаляется текущий трек id=${track.id}: воспроизведение не прерывается"
                )
            }
            trackDao.deleteTrack(track)
        }
    }

    /**
     * Part 3, задача 1: «Удалить из памяти» — удалить ТОЛЬКО скачанный
     * приложением файл трека библиотеки. Строка `tracks` и связи плейлистов
     * остаются (трек просто снова играет из сети); импортированные файлы
     * пользователя не затрагиваются. Без подтверждения и без диалога.
     */
    fun onDeleteDownloadedTrack(track: TrackEntity) {
        viewModelScope.launch {
            try {
                app.trackDownloads.deleteDownloadedTracks(listOf(track.uri))
                Log.i(TAG, "Скачанный файл трека «${track.title}» удалён из памяти")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось удалить файл трека «${track.title}»: ${e.message}")
            }
        }
    }

    /** ♥: добавляет трек в «Избранное» или убирает его оттуда. Ряд не запускает музыку. */
    fun onFavoriteToggle(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            playlists.toggleFavorite(
                PlaylistTrackInput(
                    trackUri = track.uri,
                    title = track.title,
                    artist = track.artist.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /** Долгое нажатие использовано: подсказка больше не показывается. */
    fun onLongPressHintUsed() {
        if (!_uiState.value.showLongPressHint) return
        viewModelScope.launch {
            preferences.setAlarmLongPressHintShown()
        }
    }

    /**
     * Диалог запроса разрешения на уведомления больше не показываем:
     * при отказе звук работает, а включить разрешение можно на экране «Надёжность».
     */
    fun onNotificationPermissionPromptHandled() {
        viewModelScope.launch {
            preferences.setNotificationPermissionPromptShown()
        }
    }

    /**
     * Приветственный диалог первого запуска закрыт любым способом (кнопка,
     * системный Back, тап вне диалога): сразу пишем флаг DataStore, поэтому
     * после поворота и перезапуска приложения диалог больше не показывается.
     */
    fun onWelcomeDialogDismissed() {
        if (_welcomeShown.value == true) return
        viewModelScope.launch {
            preferences.setWelcomeShown()
        }
    }

    /**
     * Правила первого запуска приняты кнопкой «Принять»: сразу пишем флаг
     * DataStore, поэтому после поворота и перезапуска окно больше не показывается.
     */
    fun onTermsAccepted() {
        if (_termsAccepted.value == true) return
        viewModelScope.launch {
            preferences.setTermsAccepted()
        }
    }

    /** Подсказка в фильтре «Скачанные» показана: больше никогда не показываем. */
    fun onDownloadedFilterHintShown() {
        if (_uiState.value.downloadedFilterHintShown == true) return
        viewModelScope.launch {
            preferences.setDownloadedFilterHintShown()
        }
    }

    fun onAddFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val newTracks = mutableListOf<TrackEntity>()
            for (uri in uris) {
                SafTrackImporter.takePersistablePermission(app, uri)
                val track = SafTrackImporter.extractTrackFromUri(app, uri)
                if (track != null) {
                    newTracks.add(track)
                }
            }
            val result = insertTracksAndCount(newTracks)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                lastImportResult = result
            )
        }
    }

    fun onAddFolder(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            // Persistable-доступ забираем ОДИН раз и проверяем, что он сохранён:
            // иначе после перезапуска автосканирование не сможет открыть папку.
            val permissionSaved = SafTrackImporter.takePersistablePermission(app, folderUri)
            val scannedTracks = SafTrackImporter.scanFolderRecursively(
                app,
                folderUri,
                takePermission = false
            )
            val result = insertTracksAndCount(scannedTracks)
            if (permissionSaved) {
                // Папка запоминается для автоматического дочитывания новых файлов
                // на старте, при возврате в foreground и открытии библиотеки.
                app.folderImport.rememberFolder(folderUri.toString())
            } else {
                Log.w(
                    TAG,
                    "Папка не сохранена для автоимпорта: persistable-доступ не выдан"
                )
            }
            app.folderImport.markScannedNow()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                lastImportResult = result
            )
        }
    }

    /**
     * Открытие экрана «Звуки»/библиотеки: дочитываем новые файлы из ранее
     * выбранной папки. Сканирование идёт в фоновом скоупе приложения и
     * схлопывается с вызовами на старте/возврате в foreground.
     */
    fun onLibraryScreenOpened() {
        app.folderImport.requestRescan("library_screen")
    }

    /**
     * ЕДИНАЯ точка импорта для обеих SAF-веток («Выбрать файлы» и «Выбрать папку
     * целиком») и автосканирования сохранённой папки: перед вставкой проверяет,
     * нет ли трека уже в БД или выше в этой же пачке, по каноническому ключу
     * `TrackIdentity` — document id, а не строке URI, поэтому tree-URI и
     * документ-URI одного файла дают один ключ.
     * Дубли по URI дополнительно игнорирует DAO (OnConflictStrategy.IGNORE),
     * -1 в возвращённых id означает пропуск.
     */
    private suspend fun insertTracksAndCount(tracks: List<TrackEntity>): ImportResult? {
        val result = trackInserter.insertNew(tracks) ?: return null
        return ImportResult(added = result.added, skipped = result.skipped)
    }

    fun consumeImportResult() {
        _uiState.value = _uiState.value.copy(lastImportResult = null)
    }

    override fun onCleared() {
        if (isServiceBound) {
            try {
                app.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Игнорируем
            }
            isServiceBound = false
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "MusicViewModel"
    }
}
