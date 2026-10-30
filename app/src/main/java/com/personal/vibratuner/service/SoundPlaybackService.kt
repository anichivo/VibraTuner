package com.personal.vibratuner.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.personal.vibratuner.MainActivity
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.PlaybackTiming
import com.personal.vibratuner.audio.VolumeCalculator
import com.personal.vibratuner.audio.eq.AndroidAudioEffectsFactory
import com.personal.vibratuner.audio.eq.EqualizerEngine
import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.audio.eq.ExoPlayerEqualizerPlayer
import com.personal.vibratuner.audio.volume.AndroidVolumeBackend
import com.personal.vibratuner.audio.volume.AudioUsageStreams
import com.personal.vibratuner.audio.volume.DataStoreVolumeBackupStore
import com.personal.vibratuner.audio.volume.SystemVolumeController
import com.personal.vibratuner.audio.choppy.ChoppyAction
import com.personal.vibratuner.audio.choppy.ChoppyEvent
import com.personal.vibratuner.audio.choppy.ChoppySilentWindow
import com.personal.vibratuner.audio.choppy.ChoppyState
import com.personal.vibratuner.audio.choppy.ChoppyStateMachine
import com.personal.vibratuner.audio.coordinator.CoordinatorAction
import com.personal.vibratuner.audio.coordinator.CoordinatorEvent
import com.personal.vibratuner.audio.coordinator.CoordinatorState
import com.personal.vibratuner.audio.coordinator.CurrentSound
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.PlaybackCoordinator
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.audio.coordinator.PlaybackNotificationState
import com.personal.vibratuner.audio.coordinator.TrackQueueSelector
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundCalculator
import com.personal.vibratuner.core.network.ConnectionErrorClassifier
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.alarm.AlarmPlaybackSelection
import com.personal.vibratuner.data.local.alarm.AlarmPlaylistQueue
import com.personal.vibratuner.data.remote.CatalogAlarmPlaylist
import com.personal.vibratuner.data.local.alarm.AlarmScheduler
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.data.local.db.PlaylistTrackEntity
import com.personal.vibratuner.data.local.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackServiceState(
    // This is play intent (playWhenReady), not the transient ExoPlayer
    // isPlaying value which becomes false while buffering after a seek.
    val isPlaying: Boolean = false,
    val currentTrack: TrackEntity? = null,
    val shuffleEnabled: Boolean = false
)

/**
 * Снимок позиции музыки для полосы прокрутки.
 * durationMs == 0L означает, что длительность ещё неизвестна.
 */
data class PlaybackPosition(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class SoundPlaybackService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var exoPlayer: ExoPlayer? = null
    private var microsoundPlayer: ExoPlayer? = null
    private var equalizerEngine: EqualizerEngine? = null

    /**
     * Часть 3: единственный класс, который меняет системную громкость.
     * Создаётся один раз вместе с сервисом; получает настройки эквалайзера
     * и флаг слышимости из координатора. Scope контроллера НЕ входит в
     * serviceScope, чтобы финальное восстановление пережило onDestroy.
     */
    private var systemVolumeController: SystemVolumeController? = null

    /**
     * USAGE, с которыми РЕАЛЬНО созданы оба плеера сервиса. Из них выводится
     * поток системной громкости (без захардкоженного STREAM_MUSIC).
     */
    private val playerAudioUsages = linkedSetOf<Int>()
    private var mediaSession: MediaSession? = null
    private var isDestroying: Boolean = false
    private var activeAudioFocusRequest: android.media.AudioFocusRequest? = null
    private var isPlayingMicrosoundOutsideSession: Boolean = false

    /**
     * Восстановленный трек подготовлен в паузе и ещё ни разу не запускался.
     * Если он не открывается, сессия очищается без автоперехода к следующему
     * треку: после перезапуска приложения музыка не должна зазвучать сама.
     */
    private var restoredPausedTrackPendingPlay: Boolean = false

    /**
     * Будильник, пришедший на T, пока ещё идёт микрозвук или последовательность
     * «Включение колонки». Для обычного микрозвука ожидание ограничено T + 30 с;
     * для «Включения колонки» полный звук и 10-секундная пауза не обрезаются.
     */
    private var pendingAlarmStart: PendingAlarmStart? = null
    private var pinnedAlarmWaitJob: Job? = null
    private var isAlarmStarting: Boolean = false

    /**
     * Идёт последовательность «Включение колонки»: звук уже играет или ещё длится
     * фиксированная 10-секундная пауза. Координатор остаётся в MicroSoundActive,
     * поэтому посторонние микрозвуки/прерывистые паузы отклоняются.
     */
    private var activeAlarmSequenceId: Long = -1L
    private var activeAlarmTriggerMs: Long = 0L
    private var speakerOnPauseJob: Job? = null

    private data class PendingAlarmStart(
        val durationMinutes: Int,
        val trackId: Long?,
        val sourceType: String,
        val playlistId: Long?,
        val shuffle: Boolean,
        val gain: Float,
        /** Плейлист каталога «От автора», не добавленный в библиотеку (v11). */
        val catalogPlaylistId: String? = null
    )

    private val coordinator = PlaybackCoordinator()
    private val choppyStateMachine = ChoppyStateMachine()

    /**
     * Окно тишины прерывистой музыки (этап 4): плеер продолжает играть,
     * усиление музыки равно 0, позиция старта запоминается для возврата.
     * Пока окно активно, любая смена громкости проходит через него.
     */
    private val choppySilentWindow = ChoppySilentWindow()

    private var playlist: List<TrackEntity> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var currentOrderIndex: Int = 0

    /**
     * Активная очередь плейлиста (этап 2): упорядоченный список треков, заданный
     * вызывающим экраном. Пока очередь не пуста, «следующий трек» идёт по ней;
     * после остановки/ручного выбора из библиотеки очередь очищается.
     */
    private var queueTracks: List<TrackEntity> = emptyList()
    private var queueOrder: List<Int> = emptyList()
    private var queueIndex: Int = 0

    /**
     * Активная очередь собрана будильником (этап 3): её порядок задан при
     * срабатывании (с учётом флага shuffle будильника) и НИКОГДА не перестраивается
     * глобальным shuffle. Сбрасывается вместе с [clearActiveQueue].
     */
    private var alarmQueueActive: Boolean = false

    /** Подряд идущие ошибки старта трека: защита от бесконечного перебора. */
    private var consecutiveTrackFailures: Int = 0

    private var shuffleEnabled: Boolean = false
    private var currentGain: Float = 1.0f
    // Не null — идёт воспроизведение, запущенное будильником: целевое усиление
    // берётся из усиления сеанса (alarms.gain), ограниченного 0..1.
    private var alarmGain: Float? = null

    /**
     * Последние прочитанные настройки эквалайзера. Нужны для композиции
     * громкости (параметр `settings` в [VolumeCalculator.effectiveVolumes])
     * и для перевода медиавремени в реальное ([PlaybackTiming]). Обновляются
     * единственной подпиской на `EqualizerRepository.settings`; второго
     * источника настроек в сервисе нет.
     */
    private var equalizerSettings: EqualizerSettings = EqualizerSettings.DEFAULT

    private var volumeRampDurationSec: Int = 10
    private var isVolumeRampActive: Boolean = false

    private var choppyTickerJob: Job? = null
    private var volumeRampJob: Job? = null
    private var alarmDurationJob: Job? = null

    private val _serviceState = MutableStateFlow(PlaybackServiceState())
    val serviceState: StateFlow<PlaybackServiceState> = _serviceState.asStateFlow()

    /**
     * Part 3: версия события «сбой соединения при воспроизведении сетевого
     * потока встроенного плейлиста». Растёт ТОЛЬКО когда [ConnectionErrorClassifier]
     * подтвердил сетевую причину у удалённого (http/https) потока; UI показывает
     * по новой версии одно короткое сообщение. Локальные файлы, отмена
     * пользователем и не-сетевые ошибки версию не меняют.
     */
    private val _connectionErrorVersion = MutableStateFlow(0L)
    val connectionErrorVersion: StateFlow<Long> = _connectionErrorVersion.asStateFlow()

    /**
     * Единственный звук, который слышен прямо сейчас (см. [PlaybackCoordinator]).
     * Источник ЕДИНСТВЕННОГО уведомления воспроизведения: уведомление строится
     * только из этого состояния и никогда не влияет на воспроизведение.
     */
    val currentSound: StateFlow<CurrentSound?> = coordinator.currentSound

    /**
     * Режим порядка воспроизведения (этап 3): OFF / SHUFFLE / REPEAT_ONE.
     * Состояние живёт в координаторе; UI только наблюдает и присылает команду.
     */
    val playbackMode: StateFlow<PlaybackMode> = coordinator.playbackMode

    /**
     * URI трека, который сейчас предпроигрывается из окна выбора мелодии, или
     * `null` (Stage 2). Живёт в координаторе; окно выбора только наблюдает за ним.
     */
    val previewTrackUri: StateFlow<String?> = coordinator.previewTrackUri

    /**
     * Можно ли сейчас начать предпрослушивание (покой/своё предпрослушивание).
     * Окно выбора гасит кнопку, пока играет музыка, микрозвук или идёт сеанс.
     */
    val previewAvailable: StateFlow<Boolean> = coordinator.previewAvailable

    /** Наблюдение за содержимым уведомления (`notificationState` координатора). */
    private var playbackNotificationJob: Job? = null

    /** Содержимое, для которого сейчас построено видимое уведомление (null — нет). */
    private var lastNotifiedState: PlaybackNotificationState? = null
    private var lastPlaybackNotification: Notification? = null
    private var isPlaybackNotificationVisible: Boolean = false

    /** Предупреждение об отказе в уведомлениях пишется один раз за жизнь сервиса. */
    private var notificationPermissionWarningLogged: Boolean = false

    inner class LocalBinder : Binder() {
        fun getService(): SoundPlaybackService = this@SoundPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        initEqualizerEngine()
        initExoPlayer()
        initMicrosoundPlayer()
        // Часть 3: контроллер создаётся после плееров — потоки системной
        // громкости выводятся из их фактических AudioAttributes.
        initSystemVolumeController()
        observePreferencesAndDatabase()
        observePlaybackNotification()
        startChoppyTicker()
        // Part 2: восстановление последней сессии в ПАУЗЕ (без play() и без фокуса)
        // и периодическое сохранение позиции, пока музыка играет.
        restoreSavedSession()
        startSessionAutoSave()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Порядок согласно <platform>: сначала startForeground, затем воспроизведение.
        // currentSound может быть ещё null (звук запустится ниже) — Android требует
        // startForeground сразу, поэтому на этот случай есть короткая заглушка.
        startForegroundForCommandStart()

        when (intent?.action) {
            ACTION_PLAY -> handlePlayCommand(intent.getLongExtra(EXTRA_TRACK_ID, -1L))
            ACTION_ALARM_START -> handleAlarmStartCommand(
                durationMinutes = intent.getIntExtra(EXTRA_ALARM_DURATION_MINUTES, 2),
                trackId = intent.getLongExtra(EXTRA_ALARM_TRACK_ID, -1L).takeIf { it > 0L },
                sourceType = intent.getStringExtra(EXTRA_ALARM_SOURCE_TYPE)
                    ?: AlarmSoundSource.TRACK.value,
                playlistId = intent.getLongExtra(EXTRA_ALARM_PLAYLIST_ID, -1L)
                    .takeIf { it > 0L },
                shuffle = intent.getBooleanExtra(EXTRA_ALARM_SHUFFLE, false),
                gain = intent.getFloatExtra(EXTRA_ALARM_GAIN, AlarmEntity.DEFAULT_GAIN),
                // Плейлист каталога «От автора» без копии в библиотеке (v11).
                catalogPlaylistId = intent.getStringExtra(EXTRA_ALARM_CATALOG_PLAYLIST_ID)
                    ?.takeIf { it.isNotBlank() }
            )
            ACTION_PLAY_MICROSOUND -> {
                val uri = intent.getStringExtra(EXTRA_MICROSOUND_URI) ?: ""
                val gain = intent.getFloatExtra(EXTRA_MICROSOUND_GAIN, 0.8f)
                val isPinnedPrelude = intent.getBooleanExtra(EXTRA_MICROSOUND_PINNED_PRELUDE, false)
                val isFakePinned = intent.getBooleanExtra(EXTRA_MICROSOUND_FAKE, false)
                val alarmTriggerMs = intent.getLongExtra(EXTRA_PINNED_ALARM_TRIGGER_MS, 0L)
                val alarmSequenceId = intent.getLongExtra(EXTRA_ALARM_SEQUENCE_ID, -1L)
                val speakerOnDurationMs = intent.getLongExtra(EXTRA_SPEAKER_ON_DURATION_MS, 0L)
                val displayName = intent.getStringExtra(EXTRA_MICROSOUND_NAME)
                handleMicrosoundCommand(
                    uri = uri,
                    gain = gain,
                    isPinnedPrelude = isPinnedPrelude,
                    isFakePinned = isFakePinned,
                    alarmTriggerMs = alarmTriggerMs,
                    alarmSequenceId = alarmSequenceId,
                    speakerOnDurationMs = speakerOnDurationMs,
                    displayName = displayName
                )
            }
            ACTION_PAUSE -> handlePauseCommand()
            ACTION_TOGGLE_PLAY_PAUSE -> handleTogglePlayPause()
            ACTION_NEXT -> handleNextCommand()
            ACTION_PREVIOUS -> handlePreviousCommand()
            ACTION_CYCLE_PLAYBACK_MODE -> handleCyclePlaybackModeCommand()
            ACTION_STOP -> handleStopCommand()
            ACTION_SHUFFLE_TOGGLE -> handleToggleShuffle()
            // Очередь плейлиста передаёт привязанный UI-клиент (playQueue).
            // Action нужен только для корректного старта Foreground-сервиса.
            ACTION_PLAY_QUEUE -> Unit
        }

        // Команда могла не запустить звук (пауза/стоп/ошибка) — убираем заглушку,
        // либо показываем уведомление уже для фактического состояния координатора.
        syncPlaybackNotification()

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------
    // Единственное уведомление воспроизведения.
    // Наблюдает ТОЛЬКО `notificationState` координатора и показывает/обновляет/
    // убирает startForeground-уведомление; логики воспроизведения здесь нет.
    // ---------------------------------------------------------------------------------

    private fun observePlaybackNotification() {
        playbackNotificationJob?.cancel()
        playbackNotificationJob = serviceScope.launch {
            // StateFlow сам эмитит только при РАЗЛИЧНОМ содержимом.
            coordinator.notificationState.collect { state ->
                applyPlaybackNotification(state)
            }
        }
    }

    /**
     * Приводит уведомление к текущему содержимому координатора.
     *
     * Пауза НЕ убирает уведомление (иначе кнопка «Играть» пропадала бы вместе с
     * ним), но и не создаёт его с нуля: если уведомления не было (например,
     * сессия восстановлена на паузе при холодном старте), оно не появляется.
     */
    private fun applyPlaybackNotification(state: PlaybackNotificationState?) {
        if (isDestroying) return
        when {
            state == null -> clearPlaybackNotification()
            state.isPlaying -> showPlaybackNotification(state)
            isPlaybackNotificationVisible -> showPlaybackNotification(state)
            else -> Unit
        }
    }

    /**
     * Обязательный `startForeground` в начале каждой команды. Если содержимое
     * уведомления уже известно — показываем его; если ещё нет — короткоживущую
     * заглушку (она будет заменена или удалена в конце `onStartCommand`).
     */
    private fun startForegroundForCommandStart() {
        val state = coordinator.notificationState.value
        if (state != null) {
            showPlaybackNotification(state)
            Log.i(TAG, "Сервис в foreground: уведомление для состояния $state")
            return
        }
        lastNotifiedState = null
        lastPlaybackNotification = null
        isPlaybackNotificationVisible = true
        try {
            PlatformCompat.startForegroundMediaPlayback(
                this,
                PlaybackNotification.ID,
                PlaybackNotification.buildPlaceholder(this)
            )
            Log.i(TAG, "Сервис в foreground: заглушка (звук ещё не определён)")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось показать уведомление-заглушку: ${e.message}", e)
        }
    }

    /** Приводит уведомление к фактическому состоянию после выполнения команды. */
    private fun syncPlaybackNotification() {
        applyPlaybackNotification(coordinator.notificationState.value)
    }

    private fun showPlaybackNotification(state: PlaybackNotificationState) {
        if (isDestroying) return
        try {
            if (!notificationPermissionWarningLogged &&
                !PlatformCompat.hasNotificationPermission(this)
            ) {
                Log.w(TAG, "Уведомления запрещены: воспроизведение продолжается без уведомления")
                notificationPermissionWarningLogged = true
            }

            // Содержимое меняется только при смене состояния; повторный startForeground
            // с тем же уведомлением нужен только для выполнения требования платформы.
            if (isPlaybackNotificationVisible &&
                state == lastNotifiedState &&
                lastPlaybackNotification != null
            ) {
                PlatformCompat.startForegroundMediaPlayback(
                    this,
                    PlaybackNotification.ID,
                    lastPlaybackNotification!!
                )
                return
            }

            val notification = PlaybackNotification.build(this, state)
            lastNotifiedState = state
            lastPlaybackNotification = notification
            isPlaybackNotificationVisible = true
            PlatformCompat.startForegroundMediaPlayback(this, PlaybackNotification.ID, notification)
        } catch (e: Exception) {
            // Уведомление не должно ронять сервис/воспроизведение ни на одном пути.
            Log.e(TAG, "Не удалось показать уведомление воспроизведения: ${e.message}", e)
        }
    }

    /**
     * Убирает уведомление и выводит сервис из foreground. Вызывается, когда
     * содержимого нет (тишина, конец звука, ошибка, stop, onDestroy), поэтому
     * уведомление не может залипнуть.
     */
    private fun clearPlaybackNotification() {
        lastNotifiedState = null
        lastPlaybackNotification = null
        isPlaybackNotificationVisible = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(PlaybackNotification.ID)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось убрать уведомление воспроизведения: ${e.message}", e)
        }
    }

    /**
     * Кнопка «Остановить» (и смахивание). Если уведомление относится к уже
     * завершившемуся звуку — безопасный no-op. На паузе «Остановить» завершает
     * воспроизведение целиком (пауза уже есть, иначе кнопка была бы мёртвой).
     */
    private fun onPlaybackNotificationStopRequested(
        kindName: String?,
        displayName: String?
    ) {
        if (isDestroying) return
        val state = coordinator.notificationState.value
        if (!matchesNotificationState(state, kindName, displayName)) {
            Log.i(TAG, "Уведомление: звук уже завершился, остановка не требуется")
            return
        }
        try {
            if (state != null && !state.isPlaying) {
                // Пауза: уведомление нужно закрыть вместе с сессией.
                stop()
                return
            }
            stopCurrent()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки звука из уведомления: ${e.message}", e)
        }
    }

    /**
     * Кнопка play/pause. Решение принимается по ЖИВОМУ состоянию координатора
     * (а не по extras устаревшего уведомления) и уходит в координатор обычными
     * событиями `UserPause`/`UserResume` — второго пути воспроизведения нет.
     * Ручная пауза запоминается координатором как `isPaused`, поэтому после
     * микрозвука/окна тишины музыка НЕ возобновляется сама.
     */
    private fun onPlaybackNotificationPlayPauseRequested() {
        if (isDestroying) return
        val state = coordinator.notificationState.value
        if (state == null || !state.canPlayPause) {
            Log.i(TAG, "Уведомление: play/pause недоступен для текущего звука")
            return
        }
        try {
            if (state.isPlaying) {
                dispatchCoordinatorEvent(CoordinatorEvent.UserPause)
            } else {
                dispatchCoordinatorEvent(CoordinatorEvent.UserResume)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка play/pause из уведомления: ${e.message}", e)
        }
    }

    /** Действие уведомления относится к актуальному состоянию, а не к прошлому звуку. */
    private fun matchesNotificationState(
        state: PlaybackNotificationState?,
        kindName: String?,
        displayName: String?
    ): Boolean {
        if (state == null) return false
        return state.kind.name == kindName && state.titleOrNull == displayName
    }

    private fun initEqualizerEngine() {
        equalizerEngine = EqualizerEngine(
            effectsFactory = AndroidAudioEffectsFactory(),
            log = { message -> Log.w(TAG, "Эквалайзер: $message") }
        )
    }

    /**
     * Единые AudioAttributes обоих плееров сервиса. USAGE запоминается в
     * [playerAudioUsages], чтобы SystemVolumeController получил поток(и)
     * системной громкости, соответствующие реально используемым атрибутам.
     */
    private fun buildPlayerAudioAttributes(): AudioAttributes {
        val usage = C.USAGE_MEDIA
        playerAudioUsages += usage
        return AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(usage)
            .build()
    }

    /**
     * Часть 3: единственная точка создания SystemVolumeController. Контроллер
     * сразу запускает crash-recovery (до первого обновления настроек и звука),
     * затем получает входы в observePreferencesAndDatabase().
     */
    private fun initSystemVolumeController() {
        try {
            val usages = playerAudioUsages.toList()
            val streams = AudioUsageStreams.streamsForUsages(usages)
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val controller = SystemVolumeController(
                backend = AndroidVolumeBackend(this),
                backupStore = DataStoreVolumeBackupStore(this),
                streams = streams,
                scope = controllerScope,
                log = { message -> Log.i(TAG, "Системная громкость: $message") }
            )
            systemVolumeController = controller
            Log.i(TAG, "SystemVolumeController: usages=$usages -> потоки=$streams")
            controllerScope.launch { controller.recoverIfPending() }
        } catch (e: Exception) {
            // Контроллер не критичен для воспроизведения: без него громкость
            // просто не поднимается, сервис работает как раньше.
            Log.e(TAG, "Не удалось создать SystemVolumeController: ${e.message}", e)
        }
    }

    private fun initExoPlayer() {
        try {
            val audioAttributes = buildPlayerAudioAttributes()

            val player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // Предпрослушивание трека (Stage 2) заканчивается тишиной:
                        // очередь/следующий трек не запускаются, окно остаётся открытым.
                        if (coordinator.state is CoordinatorState.Previewing) {
                            dispatchCoordinatorEvent(CoordinatorEvent.PreviewEnded)
                        } else {
                            dispatchCoordinatorEvent(CoordinatorEvent.TrackEnded)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Ошибка ExoPlayer: ${error.message}", error)
                    // Ошибка не имеет права оставить нулевое усиление окна тишины.
                    endChoppySilentWindow("ошибка плеера музыки")
                    // Ошибка предпрослушивания не запускает следующий трек и не
                    // трогает музыку: координатор просто возвращается в покой.
                    if (coordinator.state is CoordinatorState.Previewing) {
                        dispatchCoordinatorEvent(CoordinatorEvent.PreviewError(error))
                        return
                    }
                    consecutiveTrackFailures++
                    val current = _serviceState.value.currentTrack
                    // Part 3: сеть подвела у СЕТЕВОГО потока встроенного
                    // плейлиста — технические детали в лог, UI получает новую
                    // версию события (одно короткое сообщение). Пропуск трека и
                    // продолжение остаются прежними: ниже уходит TrackError,
                    // координатор логирует и запускает следующий трек.
                    // Классификация ТОЛЬКО по коду Media3: у скачанной копии
                    // (content-URI) сбой файла не должен выглядеть сетевым.
                    if (ConnectionErrorClassifier.isRemoteStream(current?.uri) &&
                        ConnectionErrorClassifier.isConnectionError(
                            playbackErrorCode = error.errorCode
                        )
                    ) {
                        Log.w(
                            TAG,
                            "Сбой соединения при воспроизведении «${current?.title}» " +
                                "(${current?.uri}): ${error.errorCodeName}, ${error.message}",
                            error
                        )
                        _connectionErrorVersion.value = _connectionErrorVersion.value + 1L
                    }
                    // Восстановленный трек не открылся до первого Play: сессию
                    // очищаем и НЕ переходим к следующему треку (иначе музыка
                    // зазвучала бы автоматически после перезапуска).
                    val state = coordinator.state
                    if (restoredPausedTrackPendingPlay &&
                        state is CoordinatorState.MusicPlaying &&
                        state.isPaused
                    ) {
                        restoredPausedTrackPendingPlay = false
                        consecutiveTrackFailures = 0
                        Log.w(
                            TAG,
                            "Восстановленный трек не открывается: сессия очищена, " +
                                "воспроизведение не запускаем"
                        )
                        clearPlaybackSession()
                        dispatchCoordinatorEvent(CoordinatorEvent.UserStop)
                        return
                    }
                    dispatchCoordinatorEvent(CoordinatorEvent.TrackError(current, error))
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Предпрослушивание трека (Stage 2) — НЕ воспроизведение музыки:
                    // мини-плеер/сессия/прерывистая музыка его не видят.
                    val previewing = coordinator.state is CoordinatorState.Previewing
                    // Keep the UI on play intent: a seek can briefly report
                    // isPlaying=false while the player is buffering.
                    updateServiceState(
                        isPlaying = !previewing && exoPlayer?.playWhenReady == true
                    )
                    if (previewing) return
                    if (isPlaying) {
                        // Трек реально зазвучал: серия ошибок прервана. Счётчик
                        // сбрасывается ТОЛЬКО здесь, иначе асинхронные ошибки
                        // ExoPlayer никогда не накопятся и защита не сработает.
                        consecutiveTrackFailures = 0
                        choppyStateMachine.processEvent(ChoppyEvent.PlaybackStarted)
                    } else {
                        choppyStateMachine.processEvent(ChoppyEvent.PlaybackPaused)
                    }
                }
            })
            exoPlayer = player
            applyCalculatedGain()
            initMediaSession(player)
            equalizerEngine?.attach(ExoPlayerEqualizerPlayer(player))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации ExoPlayer: ${e.message}", e)
        }
    }

    /**
     * MediaSession публикует позицию/длительность и разрешает системе
     * COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM (полоса прокрутки в шторке).
     * Управление идёт через CoordinatorRoutingPlayer, чтобы команды из системы
     * не создавали второй путь воспроизведения в обход координатора.
     */
    private fun initMediaSession(player: ExoPlayer) {
        try {
            val sessionActivity = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession = MediaSession.Builder(this, CoordinatorRoutingPlayer(player))
                .setSessionActivity(sessionActivity)
                .build()
            Log.i(TAG, "MediaSession создана: позиция/длительность и seek доступны системе")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать MediaSession: ${e.message}", e)
        }
    }

    /**
     * Обёртка над ExoPlayer для MediaSession: play/pause/seek из системных
     * элементов управления проходят через координатор, а не напрямую в плеер.
     * Доступные команды (включая COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) не скрываются:
     * все остальные методы делегируются исходному плееру.
     */
    @OptIn(UnstableApi::class)
    private inner class CoordinatorRoutingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() {
            if (!isDestroying) dispatchCoordinatorEvent(CoordinatorEvent.UserResume)
        }

        override fun pause() {
            if (!isDestroying) dispatchCoordinatorEvent(CoordinatorEvent.UserPause)
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (isDestroying) return
            if (playWhenReady) {
                dispatchCoordinatorEvent(CoordinatorEvent.UserResume)
            } else {
                dispatchCoordinatorEvent(CoordinatorEvent.UserPause)
            }
        }

        override fun stop() {
            if (!isDestroying) dispatchCoordinatorEvent(CoordinatorEvent.UserStop)
        }

        override fun seekTo(positionMs: Long) {
            if (!isDestroying) dispatchCoordinatorEvent(CoordinatorEvent.UserSeek(positionMs))
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (!isDestroying) dispatchCoordinatorEvent(CoordinatorEvent.UserSeek(positionMs))
        }
    }

    private fun initMicrosoundPlayer() {
        try {
            val audioAttributes = buildPlayerAudioAttributes()

            val player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // «Включение колонки» сначала отыгрывает файл полностью,
                        // затем выдерживает фиксированную паузу 10 с.
                        if (startSpeakerOnPostPauseIfNeeded()) return
                        dispatchCoordinatorEvent(CoordinatorEvent.MicroSoundCompleted)
                        checkStopAfterMicrosound()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Ошибка плеера микрозвука: ${error.message}", error)
                    dispatchCoordinatorEvent(CoordinatorEvent.MicroSoundError(error))
                    refreshAlarmSequenceState()
                    checkStopAfterMicrosound()
                }
            })

            microsoundPlayer = player
            equalizerEngine?.attach(ExoPlayerEqualizerPlayer(player))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации microsoundPlayer: ${e.message}", e)
        }
    }

    private fun handleMicrosoundCommand(
        uri: String,
        gain: Float,
        isPinnedPrelude: Boolean = false,
        isFakePinned: Boolean = false,
        alarmTriggerMs: Long = 0L,
        alarmSequenceId: Long = -1L,
        speakerOnDurationMs: Long = 0L,
        displayName: String? = null
    ) {
        if (uri.isBlank()) return

        // Этап 4: «Часы тишины» важнее всех микрозвуков. Настройки уже лежат
        // в координаторе (сервис подписан на тот же DataStore), поэтому здесь —
        // финальная проверка ДО захвата аудиофокуса и настройки последовательности:
        // пропущенный микрозвук не оставляет сервис в foreground и полу-состояние.
        if (coordinator.isWithinQuietHours()) {
            Log.i(
                TAG,
                "Микрозвук пропущен: сейчас «Часы тишины» " +
                    "(pinned=$isPinnedPrelude, fake=$isFakePinned)"
            )
            releaseMicrosoundOutsideSession()
            checkStopAfterMicrosound()
            return
        }

        Log.i(
            TAG,
            "Запуск микрозвука: $uri (gain=$gain, pinned=$isPinnedPrelude, " +
                "fake=$isFakePinned, T=$alarmTriggerMs, duration=$speakerOnDurationMs)"
        )

        val activeMicrosound = coordinator.state as? CoordinatorState.MicroSoundActive
        val isRealPreludeActive = activeMicrosound?.isPinnedAlarmPrelude == true

        // Пока идёт «Включение колонки» (включая паузу после него), любой другой
        // микрозвук пропускается и логируется, а не встаёт в очередь.
        // Фейк-включение пропускается решением координатора (единая точка правил
        // конфликта), поэтому здесь сервис не запрашивает для него аудиофокус.
        if (isRealPreludeActive && !isFakePinned) {
            Log.w(
                TAG,
                "Микрозвук пропущен: идёт последовательность «Включение колонки»"
            )
            return
        }

        if (isPinnedPrelude && speakerOnDurationMs <= 0L) {
            Log.w(TAG, "Включение колонки пропущено: неизвестна длительность звука")
            return
        }

        if (isFakePinned && speakerOnDurationMs <= 0L) {
            Log.w(TAG, "Фейк-включение колонки пропущено: неизвестна длительность звука")
            return
        }

        // Запоздавший сигнал предзвука (смена часов, задержка доставки) не должен
        // прерывать уже звучащий сигнал будильника: музыку на T запускает будильник.
        if (isPinnedPrelude && alarmTriggerMs > 0L && System.currentTimeMillis() >= alarmTriggerMs) {
            Log.w(
                TAG,
                "Включение колонки пропущено в сервисе: время будильника T=$alarmTriggerMs " +
                    "уже наступило (сигнал опоздал)"
            )
            return
        }

        if (isPinnedPrelude) {
            speakerOnPauseJob?.cancel()
            speakerOnPauseJob = null
            activeAlarmSequenceId = alarmSequenceId
            activeAlarmTriggerMs = alarmTriggerMs
            Log.i(
                TAG,
                "Включение колонки: старт последовательности (id=$alarmSequenceId, T=$alarmTriggerMs, " +
                    "длительность=$speakerOnDurationMs мс, gain=$gain)"
            )
        }
        if (isFakePinned) {
            Log.i(
                TAG,
                "Фейк-включение колонки: команда воспроизведения (длительность=$speakerOnDurationMs мс, gain=$gain)"
            )
        }

        // Если музыкальная сессия не активна (нет текущего трека и музыка не играет)
        if (!isRealPreludeActive &&
            _serviceState.value.currentTrack == null &&
            exoPlayer?.isPlaying != true
        ) {
            // Один кратковременный аудиофокус на один микрозвук: повторный запрос
            // (например, реальное «Включение колонки» вытесняет фейк) перезаписал бы
            // прошлый запрос и он остался бы неосвобождённым.
            if (!isPlayingMicrosoundOutsideSession) {
                val focusRequest = PlatformCompat.requestTransientAudioFocus(this) { focusChange ->
                    Log.d(TAG, "OnAudioFocusChangeListener микрозвука: $focusChange")
                }
                activeAudioFocusRequest = focusRequest
                isPlayingMicrosoundOutsideSession = true
                Log.i(
                    TAG,
                    "Аудиофокус для микрозвука: " +
                        if (focusRequest != null) "запрошен" else "недоступен, играем без него"
                )
            } else {
                Log.i(TAG, "Аудиофокус уже удерживается текущим микрозвуком, повторно не запрашивается")
            }
        }

        // Для предзвука страховка покрывает полную длительность файла и 10-секундную
        // паузу; звук никогда не обрезается ради будильника. Для фейка — полную
        // длительность того же файла и запас, как у обычного микрозвука.
        // Длительность файла — МЕДИАвремя; при ускоренном воспроизведении реальное
        // время больше/меньше, поэтому watchdog считается через PlaybackTiming.
        val wallSpeakerOnMs = PlaybackTiming.wallClockMs(speakerOnDurationMs, equalizerSettings)
        val maxDurationMs = when {
            isPinnedPrelude -> {
                val sequenceMs = wallSpeakerOnMs +
                    PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS
                val untilAlarmMs = (alarmTriggerMs - System.currentTimeMillis()).coerceAtLeast(0L)
                maxOf(sequenceMs, untilAlarmMs) + SPEAKER_ON_SEQUENCE_GRACE_MS
            }
            isFakePinned -> wallSpeakerOnMs + SPEAKER_ON_SEQUENCE_GRACE_MS
            // Обычный микрозвук: медиадлительность неизвестна, поэтому страховка
            // задана в реальном времени для 1x и растягивается на медленной скорости.
            else -> PlaybackTiming.wallClockMs(ORDINARY_MICROSOUND_WATCHDOG_MS, equalizerSettings)
        }

        dispatchCoordinatorEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = uri,
                gain = gain,
                maxDurationMs = maxDurationMs,
                timestampMs = System.currentTimeMillis(),
                isPinnedAlarmPrelude = isPinnedPrelude,
                isFakePinnedMicrosound = isFakePinned,
                displayName = displayName
            )
        )

        // Событие могло быть отклонено координатором (например, финальной
        // проверкой «Часов тишины» или конфликтом с реальной последовательностью
        // «Включения колонки»). Тогда звука нет: не держим сервис в foreground.
        if (coordinator.state !is CoordinatorState.MicroSoundActive) {
            Log.i(TAG, "Микрозвук не запущен: событие отклонено координатором")
            releaseMicrosoundOutsideSession()
            checkStopAfterMicrosound()
        }
    }

    /**
     * Звук «Включение колонки» доиграл до конца ИЛИ был остановлен вручную
     * (stopCurrent). В обоих случаях это считается естественным завершением
     * звука: запускается фиксированная пауза 10 с, в течение которой координатор
     * остаётся в MicroSoundActive (чужие звуки по-прежнему отклоняются).
     * Возвращает true, если пауза запущена или уже идёт (тогда событие
     * завершения микрозвука отправлять нельзя).
     */
    private fun startSpeakerOnPostPauseIfNeeded(manualStop: Boolean = false): Boolean {
        val state = coordinator.state as? CoordinatorState.MicroSoundActive ?: return false
        if (!state.isPinnedAlarmPrelude) return false

        // Запоздавший callback окончания плеера после ручной остановки не должен
        // перезапускать паузу или завершать последовательность досрочно.
        if (speakerOnPauseJob?.isActive == true) return true

        speakerOnPauseJob?.cancel()
        Log.i(
            TAG,
            if (manualStop) {
                "Включение колонки: звук остановлен вручную, пауза " +
                    "${PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS / 1000} с"
            } else {
                "Включение колонки: звук доигран полностью, пауза " +
                    "${PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS / 1000} с"
            }
        )

        // Тишина: currentSound становится null, приоритет MICROSOUND сохраняется.
        if (!state.isSpeakerOnSilence) {
            dispatchCoordinatorEvent(CoordinatorEvent.SpeakerOnSilenceStarted)
        }

        speakerOnPauseJob = serviceScope.launch {
            delay(PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS)
            if (!isActive) return@launch

            // Если файл оказался короче метаданных (или звук остановлен раньше),
            // не запускаем музыку раньше T: до самого будильника сохраняем тишину
            // и защиту от других микрозвуков. Но если до T ещё далеко (смена часов,
            // ошибка в метаданных), держать приоритет MICROSOUND часами нельзя:
            // последовательность завершается, музыку запустит сам будильник в T.
            val remainingUntilAlarmMs = (activeAlarmTriggerMs - System.currentTimeMillis())
                .coerceAtLeast(0L)
            if (remainingUntilAlarmMs > 0L) {
                if (!PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(remainingUntilAlarmMs)) {
                    Log.w(
                        TAG,
                        "Включение колонки: звук закончился раньше расчётного, до будильника " +
                            "ещё $remainingUntilAlarmMs мс — последовательность завершается, " +
                            "музыку запустит будильник в T=$activeAlarmTriggerMs"
                    )
                } else {
                    Log.i(
                        TAG,
                        "Включение колонки: тишина до будильника ещё $remainingUntilAlarmMs мс"
                    )
                    delay(remainingUntilAlarmMs)
                    if (!isActive) return@launch
                }
            }

            Log.i(TAG, "Включение колонки: пауза завершена, продолжаем последовательность")
            dispatchCoordinatorEvent(CoordinatorEvent.MicroSoundCompleted)
            refreshAlarmSequenceState()
            checkStopAfterMicrosound()
        }
        return true
    }

    /** Сбрасывает id последовательности, когда координатор уже не в предзвуке. */
    private fun refreshAlarmSequenceState() {
        val state = coordinator.state as? CoordinatorState.MicroSoundActive
        if (state?.isPinnedAlarmPrelude != true) {
            activeAlarmSequenceId = -1L
            activeAlarmTriggerMs = 0L
        }
    }

    private fun releaseMicrosoundOutsideSession() {
        if (!isPlayingMicrosoundOutsideSession) return
        isPlayingMicrosoundOutsideSession = false
        PlatformCompat.abandonTransientAudioFocus(this, activeAudioFocusRequest)
        activeAudioFocusRequest = null
    }

    private fun checkStopAfterMicrosound() {
        // Будильник ждёт окончания предзвука или уже готовится к старту —
        // сервис останавливать нельзя.
        if (pendingAlarmStart != null || isAlarmStarting) return
        if (coordinator.state is CoordinatorState.Idle &&
            _serviceState.value.currentTrack == null
        ) {
            releaseMicrosoundOutsideSession()
            Log.i(TAG, "Микрозвук вне музыкальной сессии завершен, остановка Foreground Service")
            // currentSound уже null: уведомление убирается тем же путём, что и всегда.
            clearPlaybackNotification()
            stopSelf()
        }
    }

    private fun observePreferencesAndDatabase() {
        val app = VibraTunerApplication.instance

        // Подписка на желаемое усиление музыки
        serviceScope.launch {
            app.preferencesDataStore.currentGainFlow.collectLatest { gain ->
                currentGain = gain
                applyCalculatedGain()
            }
        }

        // Подписка на длительность нарастания громкости
        serviceScope.launch {
            app.preferencesDataStore.volumeRampDurationSecFlow.collectLatest { rampSec ->
                volumeRampDurationSec = rampSec
            }
        }

        // Этап 4: глобальные «Часы тишины» для ФИНАЛЬНОЙ проверки в координаторе
        // перед любым микрозвуком. Источник тот же DataStore, что у планировщика.
        serviceScope.launch {
            app.preferencesDataStore.quietHoursFlow.collectLatest { settings ->
                coordinator.updateQuietHours(settings)
            }
        }

        // Подписка на режим shuffle
        serviceScope.launch {
            app.preferencesDataStore.shuffleEnabledFlow.collectLatest { shuffle ->
                shuffleEnabled = shuffle
                rebuildPlaybackOrder()
                rebuildQueueOrder()
                updateServiceState(shuffle = shuffleEnabled)
            }
        }

        // Подписка на настройки эквалайзера: переприменяются сразу на уже
        // играющих звуках (включая переключатель ON/OFF), без перезапуска.
        // Тот же поток настроек плюс единственный флаг слышимости координатора
        // питают SystemVolumeController (часть 3): второго источника нет.
        serviceScope.launch {
            combine(
                app.equalizerRepository.settings,
                coordinator.appIsAudible
            ) { settings, audible -> settings to audible }
                // Ошибка чтения настроек (повреждённый DataStore и т.п.) НИКОГДА
                // не оставляет системную громкость поднятой: сразу возвращаем
                // исходный уровень и пишем в лог. Эффекты при этом уже
                // освобождены, если ошибка пришла из движка (он ловит сам).
                .catch { e ->
                    Log.e(TAG, "Настройки эквалайзера недоступны: ${e.message}", e)
                    systemVolumeController?.restoreImmediately()
                }
                .collectLatest { (settings, audible) ->
                    // Настройки сохраняются для композиции громкости и таймингов
                    // (проверка скорости/ON-OFF), затем применяются к эффектам.
                    equalizerSettings = settings
                    equalizerEngine?.apply(settings)
                    systemVolumeController?.update(settings, audible)
                }
        }

        // Подписка на параметры прерывистой музыки
        serviceScope.launch {
            combine(
                app.preferencesDataStore.choppyEnabledFlow,
                app.preferencesDataStore.choppyPlayMinutesFlow,
                app.preferencesDataStore.choppyPauseMinutesFlow
            ) { enabled, playMin, pauseMin ->
                Triple(enabled, playMin, pauseMin)
            }.collectLatest { (enabled, playMin, pauseMin) ->
                choppyStateMachine.processEvent(
                    ChoppyEvent.Configure(
                        enabled = enabled,
                        playIntervalMs = playMin * 60_000L,
                        pauseIntervalMs = pauseMin * 60_000L
                    )
                )
                // Выключили прерывистую музыку во время окна тишины: звук обязан
                // вернуться СРАЗУ (усиление 0 не переживает выключение режима),
                // а уведомление — смениться с «пауза» на «играет».
                if (!enabled && coordinator.state is CoordinatorState.ChoppyPauseActive) {
                    Log.i(TAG, "Прерывистая музыка выключена: окно тишины завершено досрочно")
                    dispatchCoordinatorEvent(CoordinatorEvent.ChoppyPauseCompleted)
                }
            }
        }

        // Подписка на список треков из Room
        serviceScope.launch {
            app.database.trackDao().getAllTracksFlow().collectLatest { tracks ->
                playlist = tracks
                rebuildPlaybackOrder()
            }
        }
    }

    private fun startChoppyTicker() {
        choppyTickerJob?.cancel()
        choppyTickerJob = serviceScope.launch {
            // Дельта считается по elapsedRealtime: он идёт и в глубоком сне,
            // в отличие от delay/uptime. Если процесс всё же замер, первый
            // проснувшийся тик даёт большую дельту и окно корректно завершается.
            var lastTickElapsedMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(CHOPPY_TICK_MS)
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val deltaMs = (nowElapsedMs - lastTickElapsedMs).coerceAtLeast(0L)
                lastTickElapsedMs = nowElapsedMs

                // Страховка (Watchdog) координатора от зависших состояний паузы или микрозвука
                dispatchCoordinatorEvent(CoordinatorEvent.WatchdogTimeoutTick(System.currentTimeMillis()))

                // Последняя страховка усиления: окно тишины ни при каких условиях
                // не длится дольше своего срока + запас, иначе музыка осталась бы
                // неслышимой навсегда.
                if (choppySilentWindow.isDeadlineExceeded(nowElapsedMs)) {
                    Log.w(TAG, "Окно тишины прерывистой музыки просрочено: принудительно возвращаю звук")
                    if (coordinator.state is CoordinatorState.ChoppyPauseActive) {
                        dispatchCoordinatorEvent(CoordinatorEvent.ChoppyPauseCompleted)
                    }
                    endChoppySilentWindow("просроченное окно тишины")
                }

                // Таймер идёт ТОЛЬКО при реальном воспроизведении. В окне тишины
                // плеер продолжает играть (усиление 0), поэтому окно отсчитывается
                // как обычно, а пауза пользователя замораживает таймер.
                if (exoPlayer?.playWhenReady != true) continue

                val actions = choppyStateMachine.processEvent(ChoppyEvent.TimeTick(deltaMs))
                for (action in actions) {
                    when (action) {
                        is ChoppyAction.TriggerPause -> {
                            val musicState = coordinator.state
                            if (musicState !is CoordinatorState.MusicPlaying || musicState.isPaused) {
                                // Автомат досчитал интервал, пока музыка фактически
                                // не играла: окно не открываем, чтобы не заглушить паузу.
                                Log.w(TAG, "Прерывистая музыка: окно тишины пропущено, музыка не играет")
                                continue
                            }
                            val positionMs = currentMusicPositionMs()
                            Log.i(
                                TAG,
                                "Прерывистая музыка: окно тишины ${action.durationMs} мс, " +
                                    "позиция $positionMs мс, плеер продолжает играть"
                            )
                            dispatchCoordinatorEvent(
                                CoordinatorEvent.TriggerChoppyPause(
                                    durationMs = action.durationMs,
                                    timestampMs = System.currentTimeMillis(),
                                    positionMs = positionMs
                                )
                            )
                        }
                        is ChoppyAction.ResumePlayback -> {
                            Log.i(TAG, "Прерывистая музыка: конец окна тишины, возврат звука")
                            dispatchCoordinatorEvent(CoordinatorEvent.ChoppyPauseCompleted)
                        }
                    }
                }
            }
        }
    }

    /** Текущая позиция музыки (медиавремя, мс); ошибки не мешают окну тишины. */
    private fun currentMusicPositionMs(): Long = try {
        exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось прочитать позицию музыки для окна тишины: ${e.message}")
        0L
    }

    /**
     * Громкость музыки: ЕДИНСТВЕННАЯ точка её вычисления. Композиция — только
     * через [VolumeCalculator.effectiveVolumes]; эквалайзер на неё не влияет
     * (перегруз добавляет LoudnessEnhancer, а не `player.volume`).
     * [duckingFactor] — существующее нарастание громкости сеанса (0..1).
     */
    private fun musicVolume(duckingFactor: Float = VolumeCalculator.NEUTRAL_GAIN): Float =
        VolumeCalculator.effectiveVolumes(
            sessionVolume = VolumeCalculator.calculateTargetMusicGain(
                alarmGain = alarmGain,
                currentGain = currentGain
            ),
            microsoundVolume = VolumeCalculator.NEUTRAL_GAIN,
            duckingFactor = duckingFactor,
            settings = equalizerSettings
        ).music

    /**
     * Громкость микрозвука: собственное усиление микрозвука ОТНОСИТЕЛЬНО
     * громкости сеанса (во время сигнала) × ducking. Вне сеанса громкость
     * сеанса нейтральная, поэтому поведение прежнее.
     */
    private fun microsoundVolume(ownGain: Float): Float =
        VolumeCalculator.effectiveVolumes(
            sessionVolume = alarmGain ?: VolumeCalculator.NEUTRAL_GAIN,
            microsoundVolume = ownGain,
            duckingFactor = VolumeCalculator.NEUTRAL_GAIN,
            settings = equalizerSettings
        ).microsound

    private fun applyCalculatedGain() {
        if (isVolumeRampActive) {
            // Нарастание ведёт громкость своим таймером, но окно тишины обязано
            // глушить музыку и во время нарастания.
            if (choppySilentWindow.isActive) {
                applyMusicGain(duckingFactor = 0f)
            }
            return
        }
        applyMusicGain()
    }

    /**
     * Единственная точка записи `player.volume` для музыки: обычная композиция
     * громкости, умноженная на окно тишины прерывистой музыки. Системная
     * громкость здесь не меняется никогда.
     */
    private fun applyMusicGain(duckingFactor: Float = VolumeCalculator.NEUTRAL_GAIN) {
        exoPlayer?.volume = choppySilentWindow.applyGain(musicVolume(duckingFactor))
    }

    /**
     * Восстановить обычное усиление музыки и закрыть окно тишины. Вызывается на
     * КАЖДОМ пути, который завершает/отменяет окно (пауза, остановка, смена
     * трека, ошибка, остановка сервиса): усиление не имеет права остаться 0.
     * Безопасно вызывать, когда окна нет.
     */
    private fun endChoppySilentWindow(reason: String) {
        val wasActive = choppySilentWindow.isActive
        choppySilentWindow.end()
        applyCalculatedGain()
        if (wasActive) {
            Log.i(TAG, "Окно тишины прерывистой музыки закрыто: $reason; усиление музыки восстановлено")
        }
    }

    /** Завершает режим будильника: усиление снова берётся по источнику, повтор трека выключен. */
    private fun clearAlarmMode() {
        alarmGain = null
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
    }

    private fun rebuildPlaybackOrder() {
        if (playlist.isEmpty()) {
            playbackOrder = emptyList()
            currentOrderIndex = 0
            return
        }

        val size = playlist.size
        playbackOrder = if (shuffleEnabled) {
            (0 until size).shuffled()
        } else {
            (0 until size).toList()
        }

        // Синхронизируем currentOrderIndex с текущим треком
        val current = _serviceState.value.currentTrack
        if (current != null) {
            val trackIndexInPlaylist = playlist.indexOfFirst { it.id == current.id }
            val indexInOrder = playbackOrder.indexOf(trackIndexInPlaylist)
            if (indexInOrder != -1) {
                currentOrderIndex = indexInOrder
            }
        }
    }

    /**
     * Запуск воспроизведения из упорядоченного списка треков (плейлист, этап 2).
     * Использует ТОТ ЖЕ ExoPlayer и ТОТ ЖЕ координатор, что и обычная музыка
     * (`UserPlay`, `PlayNextTrack`): второго пути воспроизведения нет.
     *
     * Порядок — как передан; при включённом глобальном shuffle он перемешивается.
     * Недоступный трек пропускается с записью в лог; если недоступны все треки,
     * воспроизведение корректно останавливается (см. [advanceToNextTrack]).
     */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            Log.w(TAG, "Очередь пуста: воспроизведение не запущено")
            return
        }
        clearAlarmMode()
        cancelVolumeRamp()
        consecutiveTrackFailures = 0
        alarmQueueActive = false
        queueTracks = tracks
        val safeStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        queueOrder = buildQueueOrder(tracks.size)
        val positionInOrder = queueOrder.indexOf(safeStartIndex)
        queueIndex = if (positionInOrder >= 0) positionInOrder else 0
        val track = tracks[safeStartIndex]
        Log.i(
            TAG,
            "Запуск плейлиста: треков ${tracks.size}, старт «${track.title}», " +
                "shuffle=$shuffleEnabled"
        )
        dispatchCoordinatorEvent(CoordinatorEvent.UserPlay(track))
    }

    private fun buildQueueOrder(size: Int): List<Int> =
        if (shuffleEnabled) (0 until size).shuffled() else (0 until size).toList()

    /** Перестройка порядка активной очереди при смене shuffle: текущий трек сохраняется. */
    private fun rebuildQueueOrder() {
        // Очередь будильника строит сам будильник при срабатывании: глобальный
        // shuffle не должен менять её порядок посреди сигнала.
        if (alarmQueueActive) return
        if (queueTracks.isEmpty()) {
            queueOrder = emptyList()
            queueIndex = 0
            return
        }
        val currentId = _serviceState.value.currentTrack?.id
        val currentTrackIndex = if (currentId == null) {
            -1
        } else {
            queueTracks.indexOfFirst { it.id == currentId }
        }
        queueOrder = buildQueueOrder(queueTracks.size)
        if (currentTrackIndex >= 0) {
            val indexInOrder = queueOrder.indexOf(currentTrackIndex)
            if (indexInOrder >= 0) queueIndex = indexInOrder
        }
    }

    /** Очередь больше не нужна: следующий трек снова берётся из библиотеки. */
    private fun clearActiveQueue() {
        queueTracks = emptyList()
        queueOrder = emptyList()
        queueIndex = 0
        alarmQueueActive = false
    }

    /** Размер текущего источника треков — для страховки от бесконечного перебора ошибок. */
    private fun currentSourceSize(): Int =
        if (queueTracks.isNotEmpty()) queueTracks.size else playlist.size

    fun playTrack(track: TrackEntity) {
        // Ручной запуск всегда выходит из режима будильника.
        clearAlarmMode()
        cancelVolumeRamp()
        // Трек выбран в библиотеке: порядок снова библиотечный (как до этапа 2).
        clearActiveQueue()
        consecutiveTrackFailures = 0
        dispatchCoordinatorEvent(CoordinatorEvent.UserPlay(track))
    }

    fun playTrackById(trackId: Long) {
        val track = playlist.firstOrNull { it.id == trackId }
        if (track != null) {
            playTrack(track)
        }
    }

    fun pause() {
        clearAlarmMode()
        cancelVolumeRamp()
        alarmDurationJob?.cancel()
        dispatchCoordinatorEvent(CoordinatorEvent.UserPause)
    }

    // ---------------------------------------------------------------------------------
    // Предпрослушивание трека в окне выбора мелодии (Stage 2).
    //
    // Идёт ТЕМ ЖЕ плеером музыки и ТЕМ ЖЕ координатором: отдельного MediaPlayer/
    // ExoPlayer и второго пути воспроизведения нет. Координатор принимает
    // предпрослушивание только в покое и при музыке на паузе; при занятости
    // команда отклоняется с записью в лог (микрозвук/будильник/играющая музыка
    // не прерываются). Системная громкость не меняется никогда
    // (`AppAudibility` для предпрослушивания false).
    // ---------------------------------------------------------------------------------

    /** Начать/заменить предпрослушивание трека (низший приоритет, покой или пауза). */
    fun startPreview(track: TrackEntity) {
        // Музыка на паузе: запоминаем позицию, чтобы координатор вернул её на
        // паузу ровно там же после прослушивания (сессия не теряется).
        val paused = coordinator.state.let { it is CoordinatorState.MusicPlaying && it.isPaused }
        val positionMs = if (paused) getPlaybackPosition().positionMs else 0L
        dispatchCoordinatorEvent(
            CoordinatorEvent.PreviewPlay(track = track, pausedPositionMs = positionMs)
        )
    }

    /** Остановить предпрослушивание (закрытие окна, выбор трека, уход в фон). */
    fun stopPreview() {
        dispatchCoordinatorEvent(CoordinatorEvent.StopPreview)
    }

    /**
     * Переключатель строки окна выбора: тот же трек — остановить, другой — начать.
     * Решение принимается по состоянию координатора, а не по состоянию UI.
     */
    fun togglePreview(track: TrackEntity) {
        if (coordinator.previewTrackUri.value == track.uri) {
            stopPreview()
        } else {
            startPreview(track)
        }
    }

    fun resume() {
        cancelVolumeRamp()
        dispatchCoordinatorEvent(CoordinatorEvent.UserResume)
    }

    /**
     * Безопасное смещение позиции музыки через координатор.
     * Во время микрозвука событие отклоняется координатором; во время окна
     * тишины оно закрывает окно (усиление восстанавливается) и применяется.
     * Музыка при seek не запускается.
     *
     * @return true, если координатор принял смещение.
     */
    fun seekTo(positionMs: Long): Boolean {
        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(positionMs))
        for (action in actions) {
            executeAction(action)
        }
        return actions.any { it is CoordinatorAction.SeekTo }
    }

    /**
     * Текущая позиция и длительность для полосы прокрутки.
     * Вызывается с главного потока; при любой ошибке возвращает нулевой снимок.
     */
    fun getPlaybackPosition(): PlaybackPosition {
        val player = exoPlayer ?: return PlaybackPosition()
        return try {
            val duration = player.duration
            PlaybackPosition(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (duration == C.TIME_UNSET || duration <= 0L) 0L else duration
            )
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать позицию воспроизведения: ${e.message}")
            PlaybackPosition()
        }
    }

    fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) {
            pause()
        } else {
            val current = _serviceState.value.currentTrack
            if (current != null) {
                resume()
            } else if (playlist.isNotEmpty()) {
                playTrack(playlist.first())
            }
        }
    }

    fun next() {
        clearAlarmMode()
        cancelVolumeRamp()
        dispatchCoordinatorEvent(CoordinatorEvent.UserNext)
    }

    /**
     * «Предыдущий трек» (этап 3). Решение принимает координатор: позиция больше
     * 3 секунд — текущий трек начинается заново, иначе — трек из его истории.
     * Ручное управление идёт тем же путём, что next(): сброс режима будильника.
     */
    fun previous() {
        clearAlarmMode()
        cancelVolumeRamp()
        val mediaPositionMs = try {
            exoPlayer?.currentPosition ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать позицию для «Предыдущего»: ${e.message}")
            0L
        }
        // Порог «3 секунды» — время, которое слышал пользователь, а не медиапозиция:
        // при скорости 2x 6 секунд медиа — это 3 секунды реального звука.
        val wallPositionMs = PlaybackTiming.wallClockMs(mediaPositionMs, equalizerSettings)
        dispatchCoordinatorEvent(CoordinatorEvent.UserPrevious(wallPositionMs))
    }

    /**
     * Одна кнопка режима: OFF -> SHUFFLE -> REPEAT_ONE -> OFF. Состояние хранит
     * координатор (в памяти); воспроизведение и приоритеты эта команда не меняет.
     */
    fun cyclePlaybackMode() {
        dispatchCoordinatorEvent(CoordinatorEvent.UserCyclePlaybackMode)
    }

    fun stop() {
        clearAlarmMode()
        cancelVolumeRamp()
        // Пользовательская остановка закрывает окно тишины: усиление музыки
        // возвращается до остановки плеера, чтобы не остаться нулевым.
        endChoppySilentWindow("пользовательская остановка")
        // Явная остановка пользователем: системная громкость возвращается
        // немедленно, без grace-задержки (часть 3).
        systemVolumeController?.restoreImmediately()
        alarmDurationJob?.cancel()
        restoredPausedTrackPendingPlay = false
        // Остановка пользователем завершает и очередь плейлиста.
        clearActiveQueue()
        consecutiveTrackFailures = 0
        // Остановка пользователем отменяет и незавершённую последовательность.
        pendingAlarmStart = null
        pinnedAlarmWaitJob?.cancel()
        pinnedAlarmWaitJob = null
        speakerOnPauseJob?.cancel()
        speakerOnPauseJob = null
        activeAlarmSequenceId = -1L
        activeAlarmTriggerMs = 0L
        choppyStateMachine.processEvent(ChoppyEvent.PlaybackStopped)
        dispatchCoordinatorEvent(CoordinatorEvent.UserStop)
    }

    /**
     * Плейлист удалён из библиотеки (часть 3 правок). Если активная очередь
     * сервиса — РОВНО треки этого плейлиста, музыка останавливается существующим
     * путём [stop] (очередь и сессия очищаются, зависнуть нечему). Во всех
     * остальных случаях воспроизведение не трогаем:
     * - часть треков общая с другим плейлистом — файлы останутся;
     * - играет микрозвук/фейк/будильник — их останавливать нельзя;
     * - очередь не совпадает — удалены чужие строки.
     * НИКАКОГО второго пути воспроизведения метод не создаёт.
     */
    fun onPlaylistTracksDeleted(trackUris: Set<String>) {
        if (trackUris.isEmpty()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "onPlaylistTracksDeleted вызван не из главного потока: перенос в главный")
            serviceScope.launch { onPlaylistTracksDeleted(trackUris) }
            return
        }
        try {
            if (alarmQueueActive) {
                Log.i(TAG, "Удалён плейлист во время будильника: сигнал не прерываю")
                return
            }
            val activeUris = queueTracks.mapTo(mutableSetOf()) { it.uri }
            if (activeUris.isEmpty() || activeUris != trackUris) {
                Log.i(
                    TAG,
                    "Удалён плейлист, не совпадающий с активной очередью: " +
                        "воспроизведение не трогаю"
                )
                return
            }
            val sound = coordinator.currentSound.value
            if (sound != null && sound.kind != CurrentSoundKind.MUSIC) {
                Log.i(
                    TAG,
                    "Удалён плейлист во время звука ${sound.kind}: остановка отложена"
                )
                return
            }
            Log.i(TAG, "Удалён играющий плейлист: останавливаю воспроизведение")
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось безопасно остановить удалённый плейлист: ${e.message}", e)
        }
    }

    /**
     * Part 3, задача 1: скачанный приложением файл трека удалён пользователем.
     * Если этот трек сейчас играет обычной музыкой — останавливаем его
     * существующим путём [stop] (очередь и сессия очищаются, второго пути
     * воспроизведения нет). Будильник НЕ прерываем. Если музыка на паузе или
     * трек только в очереди, состояние не трогаем: при следующем старте
     * `buildMediaItem` не найдёт локальный файл и сыграет https-адрес, а
     * недоступный трек пропустится обычной обработкой `TrackError` с логом.
     */
    fun onDownloadedFilesDeleted(trackUris: Set<String>) {
        if (trackUris.isEmpty()) return
        if (isDestroying) {
            Log.i(TAG, "Сервис завершается: удаление файлов воспроизведение не трогает")
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "onDownloadedFilesDeleted вызван не из главного потока: перенос в главный")
            serviceScope.launch { onDownloadedFilesDeleted(trackUris) }
            return
        }
        try {
            val currentUri = _serviceState.value.currentTrack?.uri
            if (currentUri == null || currentUri !in trackUris) {
                Log.i(TAG, "Удалены скачанные файлы: текущий трек не затронут")
                return
            }
            val sound = coordinator.currentSound.value
            if (sound?.kind == CurrentSoundKind.ALARM) {
                Log.i(TAG, "Удалён файл трека сигнала будильника: сигнал не прерываю")
                return
            }
            if (sound == null || sound.kind != CurrentSoundKind.MUSIC) {
                Log.i(
                    TAG,
                    "Удалён файл текущего трека, но музыка не звучит " +
                        "(${sound?.kind ?: "тишина"}): воспроизведение не трогаю"
                )
                return
            }
            Log.i(TAG, "Удалён файл играющего трека: останавливаю воспроизведение")
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось безопасно остановить трек с удалённым файлом: ${e.message}", e)
        }
    }

    /**
     * Немедленная остановка звука, описанного [PlaybackCoordinator.currentSound]
     * (Part 2: кнопка единого уведомления). Выполняется в главном потоке через
     * координатор — то же сериализованное состояние, что и у событий; второго
     * пути воспроизведения нет.
     *
     * Семантику по видам звука задаёт координатор:
     * микрозвук/фейк завершаются как естественно, «Включение колонки» переходит
     * к паузе 10 с, музыка ставится на паузу, будильник завершает срабатывание.
     * Ничего не отключает и не удаляет расписания.
     */
    fun stopCurrent() {
        if (isDestroying) return
        // Координатор не потокобезопасен и живёт в главном потоке сервиса.
        // Если вызов пришёл из фонового потока (например, из другого binder-клиента),
        // переносим его в тот же сериализованный поток, что и события.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "stopCurrent вызван не из главного потока: перенос в главный поток")
            serviceScope.launch { performStopCurrent() }
            return
        }
        performStopCurrent()
    }

    private fun performStopCurrent() {
        if (isDestroying) return
        val soundBefore = coordinator.currentSound.value
        Log.i(TAG, "stopCurrent(): вызов для звука $soundBefore")
        val actions = coordinator.stopCurrent()
        for (action in actions) {
            executeAction(action)
        }
        Log.i(
            TAG,
            "stopCurrent(): выполнено действий ${actions.size}, " +
                "состояние=${coordinator.state.javaClass.simpleName}, " +
                "currentSound=${coordinator.currentSound.value}"
        )
        maybeStartPendingAlarm()
        refreshAlarmSequenceState()
        checkStopAfterMicrosound()
    }

    /**
     * Немедленно отменяет последовательность «Включение колонки» для удалённого
     * или выключенного будильника. Звук/пауза/ожидание музыки прекращаются.
     * Вызывается только из UI-слоя в главном потоке, пока сервис жив.
     */
    fun cancelAlarmSequenceIfActive(alarmId: Long) {
        if (alarmId <= 0L || activeAlarmSequenceId != alarmId) return
        val active = coordinator.state as? CoordinatorState.MicroSoundActive
        if (active?.isPinnedAlarmPrelude != true) {
            activeAlarmSequenceId = -1L
            activeAlarmTriggerMs = 0L
            return
        }

        Log.i(TAG, "Последовательность «Включение колонки» отменена для будильника id=$alarmId")
        pendingAlarmStart = null
        pinnedAlarmWaitJob?.cancel()
        pinnedAlarmWaitJob = null
        speakerOnPauseJob?.cancel()
        speakerOnPauseJob = null
        activeAlarmSequenceId = -1L
        activeAlarmTriggerMs = 0L
        // Предзвук завершается как естественное окончание (обычный путь координатора):
        // музыка/очередь восстанавливаются, а не уничтожаются событием UserStop.
        microsoundPlayer?.stop()
        val actions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted())
        for (action in actions) executeAction(action)
        refreshAlarmSequenceState()
        checkStopAfterMicrosound()
    }

    fun toggleShuffle() {
        serviceScope.launch {
            VibraTunerApplication.instance.preferencesDataStore.setShuffleEnabled(!shuffleEnabled)
        }
    }

    private fun handlePlayCommand(trackId: Long) {
        cancelVolumeRamp()
        if (trackId != -1L) {
            playTrackById(trackId)
        } else {
            resume()
        }
    }

    private fun handleAlarmStartCommand(
        durationMinutes: Int,
        trackId: Long?,
        sourceType: String,
        playlistId: Long?,
        shuffle: Boolean,
        gain: Float,
        catalogPlaylistId: String? = null
    ) {
        val currentState = coordinator.state
        if (currentState is CoordinatorState.MicroSoundActive) {
            pendingAlarmStart = PendingAlarmStart(
                durationMinutes = durationMinutes,
                trackId = trackId,
                sourceType = sourceType,
                playlistId = playlistId,
                shuffle = shuffle,
                gain = gain,
                catalogPlaylistId = catalogPlaylistId
            )
            pinnedAlarmWaitJob?.cancel()

            if (currentState.isPinnedAlarmPrelude) {
                // «Включение колонки» нельзя обрезать: ждём полный файл + 10-секундную
                // паузу, даже если будильник наступил раньше расчётного времени.
                Log.i(
                    TAG,
                    "Будильник наступил во время «Включения колонки»: музыка начнётся " +
                        "только после полного звука и паузы 10 с"
                )
                return
            }

            // Обычный микрозвук: будильник ждёт окончания, но не дольше T + 30 с.
            Log.i(
                TAG,
                "Будильник наступил во время микрозвука: музыка начнётся после его окончания, " +
                    "не позже ${ALARM_WAIT_GRACE_MS / 1000} с"
            )
            pinnedAlarmWaitJob = serviceScope.launch {
                delay(ALARM_WAIT_GRACE_MS)
                forceStartPendingAlarm()
            }
            return
        }
        // Будильник важнее окна тишины: окно не должно откладывать сигнал
        cancelChoppyPauseIfActive()
        startAlarmNow(durationMinutes, trackId, sourceType, playlistId, shuffle, gain, catalogPlaylistId)
    }

    /**
     * Завершает окно тишины перед стартом будильника: усиление музыки
     * восстанавливается (затем музыка будильника стартует своим путём). Иначе
     * координатор отклонил бы музыку будильника как событие более низкого
     * приоритета и сигнал мог бы не запуститься вовсе.
     */
    private fun cancelChoppyPauseIfActive() {
        if (coordinator.state !is CoordinatorState.ChoppyPauseActive) return
        Log.w(TAG, "Будильник отменяет прерывистую паузу")
        val actions = coordinator.processEvent(CoordinatorEvent.ChoppyPauseCompleted)
        for (action in actions) executeAction(action)
    }

    /**
     * Запуск сигнала будильника: сразу или после окончания закреплённого микрозвука.
     * Длительность сигнала отсчитывается от реального старта музыки.
     *
     * Источник PLAYLIST: очередь строится один раз при срабатывании (порядок
     * плейлиста или его перемешанная копия) и играет по кругу до истечения
     * СУЩЕСТВУЮЩЕЙ длительности сигнала. Пропущенный будильник (плейлиста нет
     * или он пуст) не меняет воспроизведение музыки.
     */
    private fun startAlarmNow(
        durationMinutes: Int,
        trackId: Long?,
        sourceType: String,
        playlistId: Long?,
        shuffle: Boolean,
        gain: Float,
        catalogPlaylistId: String? = null
    ) {
        pendingAlarmStart = null
        pinnedAlarmWaitJob?.cancel()
        pinnedAlarmWaitJob = null
        speakerOnPauseJob?.cancel()
        speakerOnPauseJob = null
        // T предзвука нужен только для строки в логе старта музыки.
        val preludeTriggerMs = activeAlarmTriggerMs
        activeAlarmSequenceId = -1L
        activeAlarmTriggerMs = 0L
        isAlarmStarting = true

        Log.i(
            TAG,
            "Запуск по будильнику, длительность сигнала: $durationMinutes мин, " +
                "source=$sourceType, trackId=$trackId, playlistId=$playlistId, " +
                "catalogPlaylistId=$catalogPlaylistId, shuffle=$shuffle, gain=$gain"
        )
        serviceScope.launch {
            var skipped = false
            try {
                releaseMicrosoundOutsideSession()
                // Источник PLAYLIST: либо локальный плейлист (`playlistId`), либо
                // плейлист каталога «От автора» без копии в библиотеке (`catalogPlaylistId`).
                val isPlaylist = AlarmSoundSource.fromValue(sourceType) ==
                    AlarmSoundSource.PLAYLIST && (playlistId != null || catalogPlaylistId != null)
                if (isPlaylist) {
                    skipped = !startAlarmPlaylistNow(
                        durationMinutes = durationMinutes,
                        playlistId = playlistId,
                        catalogPlaylistId = catalogPlaylistId,
                        shuffle = shuffle,
                        gain = gain,
                        preludeTriggerMs = preludeTriggerMs
                    )
                } else {
                    startAlarmTrackNow(
                        durationMinutes = durationMinutes,
                        trackId = trackId,
                        gain = gain,
                        preludeTriggerMs = preludeTriggerMs
                    )
                }
            } finally {
                isAlarmStarting = false
                if (skipped) {
                    // Срабатывание пропущено: координатор и музыка не тронуты.
                    // Если сервис поднимали только ради будильника, он больше не нужен.
                    stopIdleServiceAfterSkippedAlarm()
                }
            }
        }
    }

    /**
     * Плейлист будильника: очередь из сохранённого порядка (или shuffle).
     *
     * Локальный плейлист читается из Room; плейлист каталога «От автора» —
     * из кеша манифеста ([catalogPlaylistId]) без добавления в библиотеку.
     * Очередь в обоих случаях строится ОДНОЙ чистой функцией
     * [AlarmPlaylistQueue.build] и играет прежним путём координатора: локально
     * скачанный файл ([buildMediaItem]) предпочитается, иначе Media3 стримит
     * https-адрес. Ошибки/таймауты обрабатывает существующий путь пропуска
     * трека (`CoordinatorEvent.TrackError`), а не отдельный цикл ожидания.
     */
    private suspend fun startAlarmPlaylistNow(
        durationMinutes: Int,
        playlistId: Long?,
        catalogPlaylistId: String?,
        shuffle: Boolean,
        gain: Float,
        preludeTriggerMs: Long
    ): Boolean {
        val tracks = if (catalogPlaylistId != null) {
            loadCatalogAlarmPlaylistTracks(catalogPlaylistId)
        } else if (playlistId != null) {
            loadAlarmPlaylistTracks(playlistId)
        } else {
            emptyList()
        }
        val sourceLabel = catalogPlaylistId?.let { "каталога «$it»" } ?: "id=$playlistId"
        if (tracks.isEmpty()) {
            Log.w(
                TAG,
                "Будильник не запущен: плейлист $sourceLabel не найден или пуст — " +
                    "срабатывание пропущено, состояние музыки не меняется"
            )
            return false
        }
        val queue = AlarmPlaylistQueue.build(tracks, shuffle)
        if (queue.isEmpty()) {
            Log.w(TAG, "Будильник не запущен: пустая очередь плейлиста $sourceLabel")
            return false
        }

        // Свой порядок будильника: глобальный shuffle его не перестраивает.
        clearActiveQueue()
        consecutiveTrackFailures = 0
        queueTracks = queue
        queueOrder = queue.indices.toList()
        queueIndex = 0
        alarmQueueActive = true
        alarmGain = VolumeCalculator.calculateAlarmMusicGain(gain)
        // Repeat-all обеспечивает зацикливание самой очереди (advanceToNextQueueTrack).
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF

        Log.i(
            TAG,
            "Будильник играет плейлист $sourceLabel: треков ${queue.size}, " +
                "shuffle=$shuffle, gain=$alarmGain"
        )
        // Нарастание стартует ДО первого play: иначе первый кадр трека успел бы
        // прозвучать на полной громкости сеанса, а затем громкость упала бы в 0.
        startVolumeRamp()
        dispatchCoordinatorEvent(CoordinatorEvent.UserPlay(queue.first(), isAlarm = true))

        Log.i(
            TAG,
            "Музыка будильника (плейлист) стартовала: now=${System.currentTimeMillis()}, " +
                "T предзвука=$preludeTriggerMs"
        )
        startAlarmDurationTimer(durationMinutes)
        return true
    }

    /** Будильник с источником TRACK: поведение этапа v1 без изменений. */
    private suspend fun startAlarmTrackNow(
        durationMinutes: Int,
        trackId: Long?,
        gain: Float,
        preludeTriggerMs: Long
    ) {
        // Будильник всегда играет треки библиотеки, а не очередь плейлиста.
        clearActiveQueue()
        consecutiveTrackFailures = 0
        val tracks = ensureTracksAvailable()
        val target = AlarmPlaybackSelection.resolve(trackId, tracks)
        if (target.selectedTrackMissing) {
            Log.w(TAG, "Выбранный трек будильника не найден, играет вся библиотека")
        }

        alarmGain = VolumeCalculator.calculateAlarmMusicGain(gain)
        // Нарастание до первого play: первый кадр не должен звучать громче цели.
        startVolumeRamp()
        if (target.track != null) {
            // Выбранный трек повторяется весь сигнал; shuffle игнорируется.
            exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
            dispatchCoordinatorEvent(
                CoordinatorEvent.UserPlay(target.track, isAlarm = true)
            )
        } else {
            exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
            val current = _serviceState.value.currentTrack
            if (current != null) {
                dispatchCoordinatorEvent(CoordinatorEvent.AlarmMusicResume)
            } else if (playlist.isNotEmpty()) {
                dispatchCoordinatorEvent(
                    CoordinatorEvent.UserPlay(playlist.first(), isAlarm = true)
                )
            }
        }

        Log.i(
            TAG,
            "Музыка будильника стартовала: now=${System.currentTimeMillis()}, " +
                "T предзвука=$preludeTriggerMs"
        )

        // Таймер длительности будильника
        startAlarmDurationTimer(durationMinutes)
    }

    /** Однократное чтение треков плейлиста для срабатывания будильника. */
    private suspend fun loadAlarmPlaylistTracks(playlistId: Long): List<PlaylistTrackEntity> =
        try {
            withContext(Dispatchers.IO) {
                VibraTunerApplication.instance.playlistRepository.getTracksOnce(playlistId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать плейлист id=$playlistId для будильника: ${e.message}", e)
            emptyList()
        }

    /**
     * Треки плейлиста каталога «От автора» (будильник без копии в библиотеке):
     * ТОЛЬКО кеш манифеста, сеть не трогается — иначе срабатывание в офлайне
     * зависело бы от запроса манифеста. Нет кеша/плейлиста — пустой список,
     * срабатывание пропускается с логом, музыка пользователя не меняется.
     */
    private suspend fun loadCatalogAlarmPlaylistTracks(
        catalogPlaylistId: String
    ): List<PlaylistTrackEntity> =
        try {
            val playlists = withContext(Dispatchers.IO) {
                VibraTunerApplication.instance.authorManifest.loadCached()
            }
            val tracks = CatalogAlarmPlaylist.tracks(catalogPlaylistId, playlists)
            if (tracks.isEmpty()) {
                Log.w(
                    TAG,
                    "Плейлист каталога «$catalogPlaylistId» не найден в кеше манифеста " +
                        "или пуст: будильник будет пропущен"
                )
            }
            tracks
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Не удалось прочитать плейлист каталога «$catalogPlaylistId»: ${e.message}",
                e
            )
            emptyList()
        }

    /** Пропущенный будильник не должен оставлять поднятый сервис без звука. */
    private fun stopIdleServiceAfterSkippedAlarm() {
        if (pendingAlarmStart != null || isAlarmStarting) return
        if (coordinator.state is CoordinatorState.Idle &&
            _serviceState.value.currentTrack == null
        ) {
            Log.i(TAG, "Будильник пропущен, звук не нужен: остановка сервиса")
            releaseMicrosoundOutsideSession()
            clearPlaybackNotification()
            stopSelf()
        }
    }

    /**
     * Страховка T + 30 с: если предзвук всё ещё играет, принудительно завершаем
     * микрозвук и запускаем будильник. Будильник не должен ждать дольше.
     */
    private fun forceStartPendingAlarm() {
        val pending = pendingAlarmStart ?: return
        pendingAlarmStart = null
        pinnedAlarmWaitJob = null
        Log.w(
            TAG,
            "Микрозвук не завершился за ${ALARM_WAIT_GRACE_MS / 1000} с: " +
                "принудительный запуск будильника"
        )
        if (coordinator.state is CoordinatorState.MicroSoundActive) {
            val actions = coordinator.processEvent(CoordinatorEvent.UserStop)
            for (action in actions) executeAction(action)
        }
        startAlarmNow(
            pending.durationMinutes,
            pending.trackId,
            pending.sourceType,
            pending.playlistId,
            pending.shuffle,
            pending.gain,
            pending.catalogPlaylistId
        )
    }

    /** Вызывается после каждого события координатора: ждать больше нечего — стартуем будильник. */
    private fun maybeStartPendingAlarm() {
        val pending = pendingAlarmStart ?: return
        if (coordinator.state is CoordinatorState.MicroSoundActive) return
        // Предзвук мог вернуть координатор в прерывистую паузу — снимаем её
        cancelChoppyPauseIfActive()
        Log.i(TAG, "Микрозвук завершён: запуск будильника")
        startAlarmNow(
            pending.durationMinutes,
            pending.trackId,
            pending.sourceType,
            pending.playlistId,
            pending.shuffle,
            pending.gain,
            pending.catalogPlaylistId
        )
    }

    /**
     * Список треков для срабатывания будильника. При холодном старте сервиса
     * Flow из Room может ещё не успеть заполнить playlist, поэтому читаем БД напрямую.
     */
    private suspend fun ensureTracksAvailable(): List<TrackEntity> {
        if (playlist.isNotEmpty()) return playlist
        return try {
            val loaded = withContext(Dispatchers.IO) {
                VibraTunerApplication.instance.database.trackDao().getAllTracks()
            }
            if (loaded.isNotEmpty()) {
                playlist = loaded
                rebuildPlaybackOrder()
            }
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось загрузить треки для будильника: ${e.message}", e)
            emptyList()
        }
    }

    private fun startVolumeRamp() {
        volumeRampJob?.cancel()
        if (volumeRampDurationSec <= 0) {
            isVolumeRampActive = false
            applyCalculatedGain()
            return
        }

        isVolumeRampActive = true
        val totalRampMs = volumeRampDurationSec * 1000L
        // Нарастание — это ducking-фактор композиции: старт с 0, затем
        // musicVolume(progress). Отдельной формулы усиления больше нет.
        applyMusicGain(duckingFactor = 0f)

        volumeRampJob = serviceScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive && isVolumeRampActive) {
                val elapsed = System.currentTimeMillis() - startTime
                if (alarmGain == null) {
                    // Нарастание запускается только при старте будильника; при
                    // отсутствии цели просто применяем рассчитанное усиление.
                    isVolumeRampActive = false
                    applyCalculatedGain()
                    break
                }
                val rampProgress = VolumeCalculator.calculateRampProgress(
                    elapsedMs = elapsed,
                    rampDurationMs = totalRampMs
                )
                applyMusicGain(duckingFactor = rampProgress)

                if (elapsed >= totalRampMs) {
                    isVolumeRampActive = false
                    applyCalculatedGain()
                    break
                }
                delay(50L)
            }
        }
    }

    private fun cancelVolumeRamp() {
        if (isVolumeRampActive) {
            volumeRampJob?.cancel()
            volumeRampJob = null
            isVolumeRampActive = false
            applyCalculatedGain()
        }
    }

    private fun startAlarmDurationTimer(durationMinutes: Int) {
        alarmDurationJob?.cancel()
        // Миллисекунды считаем в Long: верхняя граница 1440 минут — это
        // 86 400 000 мс, никаких переполнений Int и ограничений таймера нет.
        val durationMs = durationMinutes.coerceAtLeast(1).toLong() * 60_000L
        alarmDurationJob = serviceScope.launch {
            delay(durationMs)
            Log.i(TAG, "Длительность сигнала будильника ($durationMinutes мин) истекла. Остановка музыки.")
            finishAlarmOccurrence()
        }
    }

    /**
     * Завершение ТЕКУЩЕГО срабатывания будильника — общий путь для истечения
     * длительности сигнала и для stopCurrent. Будильник не отключается:
     * будущие срабатывания перепланируются обычным планировщиком.
     */
    private fun finishAlarmOccurrence() {
        cancelVolumeRamp()
        handleStopCommand()
        serviceScope.launch {
            try {
                AlarmScheduler.rescheduleAll(
                    this@SoundPlaybackService,
                    reason = "окончание сигнала будильника"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка перепланирования будильников после сигнала: ${e.message}", e)
            }
        }
    }

    private fun handlePauseCommand() = pause()
    private fun handleTogglePlayPause() = togglePlayPause()
    private fun handleNextCommand() = next()
    private fun handlePreviousCommand() = previous()
    private fun handleCyclePlaybackModeCommand() = cyclePlaybackMode()
    private fun handleStopCommand() = stop()
    private fun handleToggleShuffle() = toggleShuffle()

    private fun dispatchCoordinatorEvent(event: CoordinatorEvent) {
        val stateBefore = coordinator.state
        val actions = coordinator.processEvent(event)
        for (action in actions) {
            executeAction(action)
        }
        // Микрозвук прервал окно тишины: окно отменено координатором, поэтому
        // автомат начинает цикл заново. Иначе остаток окна отсчитывался бы уже
        // после возврата музыки и откладывал следующий цикл.
        if (stateBefore is CoordinatorState.ChoppyPauseActive &&
            event is CoordinatorEvent.TriggerMicroSound
        ) {
            choppyStateMachine.processEvent(ChoppyEvent.PlaybackStopped)
        }
        // Пользователь вернул звук, пока автомат ещё считал окно тишины (кнопка
        // «Играть» в уведомлении, мини-плеер, MediaSession). Координатор окно уже
        // закрыл, поэтому цикл начинается заново с интервала X — как после
        // обычного возобновления, без «остатка» паузы.
        if (event is CoordinatorEvent.UserResume &&
            choppyStateMachine.state is ChoppyState.InChoppyPause &&
            coordinator.state !is CoordinatorState.ChoppyPauseActive
        ) {
            choppyStateMachine.processEvent(ChoppyEvent.PlaybackStopped)
            choppyStateMachine.processEvent(ChoppyEvent.PlaybackStarted)
        }
        // Завершился закреплённый микрозвук — можно стартовать ждавший будильник
        maybeStartPendingAlarm()
    }

    private fun executeAction(action: CoordinatorAction) {
        when (action) {
            is CoordinatorAction.StartMusicPlayback -> {
                startPlayingTrack(action.track)
            }
            is CoordinatorAction.PreparePausedMusic -> {
                preparePausedTrack(action.track, action.positionMs)
            }
            is CoordinatorAction.PauseMusicPlayback -> {
                exoPlayer?.pause()
                // Нулевое усиление окна тишины не переживает паузу: музыка на
                // паузе и будущее возобновление обязаны быть слышимыми.
                endChoppySilentWindow("пауза музыки")
                updateServiceState(isPlaying = false)
                // Пауза — точка сохранения последней сессии (Part 2).
                savePlaybackSession()
            }
            is CoordinatorAction.ResumeMusicPlayback -> {
                // Пользователь/будильник запустил звук: дальше ошибки идут
                // обычным путём координатора (пропуск трека с логом).
                restoredPausedTrackPendingPlay = false
                endChoppySilentWindow("возобновление музыки")
                exoPlayer?.play()
                updateServiceState(isPlaying = true)
            }
            is CoordinatorAction.SeekTo -> {
                try {
                    val player = exoPlayer ?: return
                    if (!player.isCurrentMediaItemSeekable) {
                        Log.w(
                            TAG,
                            "Seek на ${action.positionMs} мс пропущен: элемент не поддерживает seek"
                        )
                        return
                    }
                    val duration = player.duration
                    val target = if (duration == C.TIME_UNSET || duration <= 0L) {
                        action.positionMs.coerceAtLeast(0L)
                    } else {
                        action.positionMs.coerceIn(0L, duration)
                    }
                    player.seekTo(target)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка смещения позиции на ${action.positionMs} мс: ${e.message}", e)
                }
            }
            is CoordinatorAction.StopMusicPlayback -> {
                exoPlayer?.stop()
                endChoppySilentWindow("остановка музыки")
                updateServiceState(isPlaying = false, track = null)
                // Пользовательская остановка завершает сессию: при следующем
                // запуске мини-плеер не должен показывать старый трек.
                clearPlaybackSession()
                // Уведомление удалит наблюдатель currentSound (он станет null).
            }
            is CoordinatorAction.PlayNextTrack -> {
                advanceToNextTrack()
            }
            is CoordinatorAction.PlayPreviousTrack -> {
                playPreviousTrack(action.track)
            }
            is CoordinatorAction.LogMessage -> {
                if (action.isWarning) {
                    Log.w(TAG, action.message)
                } else {
                    Log.i(TAG, action.message)
                }
            }
            is CoordinatorAction.StartChoppyPauseTimer -> {
                // Таймер отслеживается в ChoppyStateMachine
            }
            is CoordinatorAction.MuteMusicForSilentWindow -> {
                if (exoPlayer?.playWhenReady != true) {
                    // Плеер не играет — глушить нечего. Окно закрывается штатной
                    // паузой (без возврата позиции), чтобы в конце не сделать
                    // лишний seek назад по никогда не звучавшему отрезку.
                    Log.w(TAG, "Окно тишины не открыто: музыка не играет, окно закрывается паузой")
                    dispatchCoordinatorEvent(CoordinatorEvent.UserPause)
                } else {
                    choppySilentWindow.start(
                        positionMs = action.positionMs,
                        durationMs = action.durationMs,
                        nowElapsedMs = SystemClock.elapsedRealtime()
                    )
                    applyCalculatedGain()
                    Log.i(
                        TAG,
                        "Прерывистая музыка: усиление музыки 0 на ${action.durationMs} мс, " +
                            "плеер продолжает играть (позиция ${action.positionMs} мс)"
                    )
                }
            }
            is CoordinatorAction.UnmuteMusicPlayback -> {
                endChoppySilentWindow("снятие окна тишины")
            }
            is CoordinatorAction.PlayMicroSoundTrack -> {
                // ЕДИНСТВЕННАЯ точка разрешения источника микрозвука: `builtin:<id>`
                // превращается в android.resource URI встроенного каталога, свой файл
                // остаётся прежним content:// URI. Второго пути воспроизведения нет.
                val mediaUri = resolveMicrosoundUri(action.uri)
                if (mediaUri == null) {
                    Log.w(
                        TAG,
                        "Микрозвук пропущен: источник '${action.uri}' не разрешён " +
                            "(нет в каталоге или пустой)"
                    )
                    dispatchCoordinatorEvent(
                        CoordinatorEvent.MicroSoundError(
                            IllegalStateException("Неизвестный источник микрозвука: ${action.uri}")
                        )
                    )
                    refreshAlarmSequenceState()
                    checkStopAfterMicrosound()
                    return
                }
                try {
                    val player = microsoundPlayer ?: return
                    player.stop()
                    // Усиление микрозвука относительно громкости сеанса —
                    // только через единую композицию; эквалайзер сюда не входит.
                    player.volume = microsoundVolume(action.gain)
                    player.setMediaItem(MediaItem.fromUri(mediaUri))
                    player.prepare()
                    player.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка воспроизведения микрозвука ${action.uri}: ${e.message}", e)
                    dispatchCoordinatorEvent(CoordinatorEvent.MicroSoundError(e))
                    refreshAlarmSequenceState()
                    checkStopAfterMicrosound()
                }
            }
            is CoordinatorAction.StopMicroSoundTrack -> {
                microsoundPlayer?.stop()
            }
            is CoordinatorAction.StartSpeakerOnPostPause -> {
                // Остановленный вручную звук «Включение колонки» доигрывает
                // свой обычный сценарий: 10 с тишины, затем музыка будильника.
                startSpeakerOnPostPauseIfNeeded(manualStop = true)
            }
            is CoordinatorAction.CompleteAlarmOccurrence -> {
                finishAlarmOccurrence()
            }
            is CoordinatorAction.StartTrackPreview -> {
                startTrackPreview(action.track)
            }
            is CoordinatorAction.StopTrackPreview -> {
                stopTrackPreview()
            }
        }
    }

    /**
     * Предпрослушивание трека из окна выбора мелодии (Stage 2): тот же плеер
     * музыки, но БЕЗ очереди, истории, сохранения сессии, мини-плеера и
     * уведомления. Длительность остаётся в метаданных элемента. Ошибка не роняет
     * сервис: координатор получает [CoordinatorEvent.PreviewError] и возвращается
     * в покой.
     */
    private fun startTrackPreview(track: TrackEntity) {
        val player = exoPlayer
        if (player == null) {
            // Плеера нет (ошибка инициализации): не оставляем окно в состоянии
            // «играет» — координатор возвращается в покой, иконка сбрасывается.
            Log.w(TAG, "Предпрослушивание недоступно: плеер не создан")
            dispatchCoordinatorEvent(CoordinatorEvent.PreviewError(null))
            return
        }
        try {
            // Предыдущее предпрослушивание не смешивается с новым.
            player.stop()
            player.setMediaItem(buildMediaItem(track))
            player.prepare()
            applyCalculatedGain()
            player.play()
            Log.i(TAG, "Предпрослушивание: «${track.title}»")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось начать предпрослушивание «${track.title}»: ${e.message}")
            dispatchCoordinatorEvent(CoordinatorEvent.PreviewError(e))
        }
    }

    /**
     * Остановка предпрослушивания. Плеер не освобождается (он один на всё
     * приложение), поэтому утечки плеера/привязки к сервису нет; состояние
     * музыки, очередь и сохранённая сессия не меняются.
     */
    private fun stopTrackPreview() {
        try {
            exoPlayer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось остановить предпрослушивание: ${e.message}")
        }
    }

    private fun startPlayingTrack(track: TrackEntity, fromHistory: Boolean = false) {
        restoredPausedTrackPendingPlay = false
        try {
            val player = exoPlayer ?: return
            // Смена трека закрывает окно тишины: запомненная позиция относится
            // к прошлому элементу, а усиление не имеет права остаться нулевым.
            endChoppySilentWindow("смена трека")
            player.setMediaItem(buildMediaItem(track))
            player.prepare()
            applyCalculatedGain()
            player.play()

            updateServiceState(isPlaying = true, track = track)
            // Следующий трек мог стартовать в обход события UserPlay:
            // обновляем только отображаемый трек в координаторе.
            // История «Предыдущего» наполняется здесь же, по фактическому порядку;
            // при возврате назад ([fromHistory]) покидаемый трек в неё не попадает.
            coordinator.onMusicTrackStarted(track, recordInHistory = !fromHistory)
            // Смена трека — точка сохранения сессии: позиция нового трека 0.
            savePlaybackSession(positionOverrideMs = 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось воспроизвести трек ${track.title}: ${e.message}", e)
            consecutiveTrackFailures++
            dispatchCoordinatorEvent(CoordinatorEvent.TrackError(track, e))
        }
    }

    /**
     * Переход к треку из истории «Предыдущего». Стартует тот же единственный
     * путь ([startPlayingTrack]); дополнительно синхронизируются индексы
     * активной очереди/библиотечного порядка, чтобы «дальше» продолжило
     * именно с этого трека. Ошибки трека обрабатываются обычным путём
     * координатора (пропуск с логом), музыка не остаётся на паузе навсегда.
     */
    private fun playPreviousTrack(track: TrackEntity) {
        try {
            if (queueTracks.isNotEmpty()) {
                val queueTrackIndex = queueTracks.indexOfFirst { it.uri == track.uri }
                if (queueTrackIndex >= 0) {
                    val positionInOrder = queueOrder.indexOf(queueTrackIndex)
                    if (positionInOrder >= 0) {
                        queueIndex = positionInOrder
                    }
                } else {
                    // Трек из другой очереди: продолжаем как обычный выбор трека.
                    clearActiveQueue()
                }
            }
            if (queueTracks.isEmpty() && playlist.isNotEmpty()) {
                val playlistIndex = playlist.indexOfFirst { it.uri == track.uri }
                if (playlistIndex >= 0) {
                    if (playbackOrder.isEmpty() || playbackOrder.size != playlist.size) {
                        rebuildPlaybackOrder()
                    }
                    val positionInOrder = playbackOrder.indexOf(playlistIndex)
                    if (positionInOrder >= 0) {
                        currentOrderIndex = positionInOrder
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось синхронизировать порядок для «Предыдущего»: ${e.message}")
        }
        startPlayingTrack(track, fromHistory = true)
    }

    /**
     * Сохранённый источник микрозвука -> URI для Media3.
     * `builtin:<id>` — файл встроенного каталога (`android.resource://`), свой
     * файл — прежний content:// URI. Неизвестный id или пустое значение дают null:
     * вызывающий код обязан пропустить звук с записью в лог, а не падать.
     */
    private fun resolveMicrosoundUri(stored: String): Uri? {
        if (stored.isBlank()) return null
        if (!BuiltinMicrosoundCatalog.hasBuiltinPrefix(stored)) {
            return try {
                Uri.parse(stored)
            } catch (e: Exception) {
                Log.e(TAG, "Некорректный URI микрозвука '$stored': ${e.message}", e)
                null
            }
        }
        val resourceUri = BuiltinMicrosoundCatalog.resourceUriString(packageName, stored)
        if (resourceUri == null) {
            Log.w(TAG, "Встроенный микрозвук не найден в каталоге: '$stored'")
        }
        return resourceUri?.let { Uri.parse(it) }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaItem(track: TrackEntity): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
        if (track.durationMs > 0L) {
            metadataBuilder.setDurationMs(track.durationMs)
        }
        // Скачанный трек играем с локального Uri: публичная копия в
        // Download/VibraTuner (content://) или устаревший приватный файл.
        // Иначе — из сети. Выбор источника только здесь, второго пути нет.
        val localUri = downloadedUri(track.uri)
        val mediaUri = localUri ?: Uri.parse(track.uri)
        return MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /** Локальная копия трека, если она уже скачана; ошибки не мешают воспроизведению. */
    private fun downloadedUri(trackUri: String): Uri? {
        return try {
            (application as? VibraTunerApplication)?.trackDownloads?.playbackUri(trackUri)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проверить скачанный файл: ${e.message}")
            null
        }
    }

    /**
     * Подготовка восстановленной сессии (Part 2): медиаэлемент и позиция
     * загружены, но `playWhenReady = false` — звука и аудиофокуса НЕТ,
     * пока пользователь не нажмёт Play. Состояние координатора — музыка на паузе.
     */
    private fun preparePausedTrack(track: TrackEntity, positionMs: Long) {
        try {
            val player = exoPlayer ?: return
            endChoppySilentWindow("подготовка трека на паузе")
            player.setMediaItem(buildMediaItem(track))
            player.prepare()
            if (positionMs > 0L) {
                player.seekTo(positionMs)
            }
            player.playWhenReady = false
            updateServiceState(isPlaying = false, track = track)
            applyCalculatedGain()
            // Синхронизируем позицию восстановленного трека с библиотечным
            // порядком, чтобы «Следующий» продолжил именно с него.
            rebuildPlaybackOrder()
            restoredPausedTrackPendingPlay = true
            Log.i(
                TAG,
                "Восстановлена сессия (пауза): «${track.title}», позиция ${positionMs} мс"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось подготовить восстановленный трек: ${e.message}", e)
            clearPlaybackSession()
            // Не оставляем координатор в паузе, указывающей на битый элемент.
            dispatchCoordinatorEvent(CoordinatorEvent.UserStop)
        }
    }

    private fun advanceToNextTrack() {
        // Все треки источника недоступны: не зацикливаемся, тишина и чистый лог.
        val sourceSize = currentSourceSize()
        if (sourceSize > 0 && consecutiveTrackFailures >= sourceSize) {
            Log.w(
                TAG,
                "Все треки источника недоступны ($consecutiveTrackFailures ошибок из $sourceSize): " +
                    "воспроизведение остановлено"
            )
            consecutiveTrackFailures = 0
            clearActiveQueue()
            handleStopCommand()
            return
        }

        if (queueTracks.isNotEmpty()) {
            advanceToNextQueueTrack()
            return
        }

        if (playlist.isEmpty()) return

        // SHUFFLE (этап 3): следующий трек выбирается случайно из библиотеки,
        // без немедленного повтора текущего. Порядок остаётся прежним для
        // будущего OFF — меняется только выбор конкретного трека.
        if (coordinator.playbackMode.value == PlaybackMode.SHUFFLE) {
            val randomTrack = pickRandomLibraryTrack()
            if (randomTrack != null) {
                startPlayingTrack(randomTrack)
                return
            }
            Log.w(TAG, "SHUFFLE: не удалось выбрать трек, продолжаем по порядку")
        }

        if (playbackOrder.isEmpty() || playbackOrder.size != playlist.size) {
            rebuildPlaybackOrder()
        }

        // По кругу: инкремент индекса с остатком от деления
        currentOrderIndex = (currentOrderIndex + 1) % playbackOrder.size
        val nextPlaylistIndex = playbackOrder[currentOrderIndex]
        val nextTrack = playlist.getOrNull(nextPlaylistIndex) ?: playlist.first()

        startPlayingTrack(nextTrack)
    }

    /**
     * Случайный трек библиотеки для режима SHUFFLE (кроме текущего).
     * Индекс библиотечного порядка синхронизируется с выбором, чтобы «дальше»
     * в режиме OFF продолжило с этого места.
     */
    private fun pickRandomLibraryTrack(): TrackEntity? {
        if (playlist.isEmpty()) return null
        val currentPlaylistIndex = playlist.indexOfFirst {
            it.uri == _serviceState.value.currentTrack?.uri
        }
        val randomIndex = TrackQueueSelector.randomIndex(playlist.size, currentPlaylistIndex)
            ?: return null
        val track = playlist.getOrNull(randomIndex) ?: return null
        if (playbackOrder.isEmpty() || playbackOrder.size != playlist.size) {
            rebuildPlaybackOrder()
        }
        val positionInOrder = playbackOrder.indexOf(randomIndex)
        if (positionInOrder >= 0) {
            currentOrderIndex = positionInOrder
        }
        return track
    }

    /** Следующий трек активной очереди плейлиста (по её порядку, с зацикливанием). */
    private fun advanceToNextQueueTrack() {
        if (queueOrder.isEmpty() || queueOrder.size != queueTracks.size) {
            rebuildQueueOrder()
        }
        if (queueOrder.isEmpty()) {
            clearActiveQueue()
            handleStopCommand()
            return
        }
        // SHUFFLE (этап 3): случайный трек очереди без немедленного повтора.
        // Очередь будильника играет по своему фиксированному порядку, как раньше.
        if (coordinator.playbackMode.value == PlaybackMode.SHUFFLE && !alarmQueueActive) {
            val randomTrack = pickRandomQueueTrack()
            if (randomTrack != null) {
                startPlayingTrack(randomTrack)
                return
            }
            Log.w(TAG, "SHUFFLE: не удалось выбрать трек очереди, продолжаем по порядку")
        }
        queueIndex = (queueIndex + 1) % queueOrder.size
        val nextTrack = queueTracks.getOrNull(queueOrder[queueIndex])
        if (nextTrack == null) {
            Log.w(TAG, "Трек очереди не найден: воспроизведение остановлено")
            clearActiveQueue()
            handleStopCommand()
            return
        }
        startPlayingTrack(nextTrack)
    }

    /** Случайный трек активной очереди (кроме текущего) с синхронизацией индекса. */
    private fun pickRandomQueueTrack(): TrackEntity? {
        if (queueTracks.isEmpty()) return null
        val currentTrackIndex = queueTracks.indexOfFirst {
            it.uri == _serviceState.value.currentTrack?.uri
        }
        val randomIndex = TrackQueueSelector.randomIndex(
            size = queueTracks.size,
            currentIndex = currentTrackIndex
        ) ?: return null
        val track = queueTracks.getOrNull(randomIndex) ?: return null
        val positionInOrder = queueOrder.indexOf(randomIndex)
        if (positionInOrder >= 0) {
            queueIndex = positionInOrder
        }
        return track
    }

    /**
     * Part 2: восстановление последней музыкальной сессии из DataStore.
     * Трек ищется в библиотеке; удалённый/недоступный трек очищает сессию с
     * записью в лог и НЕ показывается мини-плеером. Подготовка идёт через
     * координатор ([CoordinatorEvent.RestoreSession]) — второго пути нет.
     * Воспроизведение не запускается и аудиофокус не запрашивается.
     */
    private fun restoreSavedSession() {
        serviceScope.launch {
            try {
                val app = VibraTunerApplication.instance
                val session = app.preferencesDataStore.lastPlaybackSessionFlow.first()
                    ?: return@launch
                val track = app.database.trackDao().getTrackById(session.trackId)
                if (track == null) {
                    Log.i(
                        TAG,
                        "Сессия не восстановлена: трек id=${session.trackId} больше не существует"
                    )
                    app.preferencesDataStore.clearLastPlaybackSession()
                    return@launch
                }
                if (!canOpenTrack(track)) {
                    Log.w(
                        TAG,
                        "Сессия не восстановлена: файл «${track.title}» недоступен для чтения"
                    )
                    app.preferencesDataStore.clearLastPlaybackSession()
                    return@launch
                }
                // Не перетираем уже начавшийся звук (будильник/микрозвук/музыку):
                // сервис мог быть создан командой, пока читалась сессия.
                if (isDestroying ||
                    coordinator.state !is CoordinatorState.Idle ||
                    _serviceState.value.currentTrack != null
                ) {
                    Log.i(TAG, "Сессия не восстановлена: сервис уже занят другим звуком")
                    return@launch
                }
                dispatchCoordinatorEvent(
                    CoordinatorEvent.RestoreSession(
                        track = track,
                        positionMs = session.positionMs
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось восстановить последнюю сессию: ${e.message}")
            }
        }
    }

    /** Позиция сохраняется примерно раз в 5 секунд, пока музыка реально играет. */
    private fun startSessionAutoSave() {
        serviceScope.launch {
            while (isActive) {
                delay(SESSION_SAVE_INTERVAL_MS)
                if (exoPlayer?.playWhenReady == true) {
                    savePlaybackSession()
                }
            }
        }
    }

    /**
     * Сохраняет последнюю сессию (id трека + позиция) в существующий DataStore.
     * Синтетические треки «От автора» (id <= 0) не сохраняются: их нельзя
     * восстановить по id из библиотеки.
     */
    private fun savePlaybackSession(positionOverrideMs: Long? = null): Job? {
        // Во время предпрослушивания позиция плеера относится к ДРУГОМУ треку:
        // сохранять её как позицию сессии нельзя (иначе восстановление после
        // перезапуска начнёт музыку не с того места).
        if (coordinator.state is CoordinatorState.Previewing) return null
        val track = _serviceState.value.currentTrack ?: return null
        if (track.id <= 0L) return null
        val position = (positionOverrideMs ?: getPlaybackPosition().positionMs)
            .coerceAtLeast(0L)
        return serviceScope.launch {
            try {
                VibraTunerApplication.instance.preferencesDataStore
                    .saveLastPlaybackSession(track.id, position)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось сохранить позицию сессии: ${e.message}")
            }
        }
    }

    /** Очистка сессии (трек удалён/недоступен или пользователь остановил музыку). */
    private fun clearPlaybackSession() {
        serviceScope.launch {
            try {
                VibraTunerApplication.instance.preferencesDataStore.clearLastPlaybackSession()
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось очистить сессию воспроизведения: ${e.message}")
            }
        }
    }

    /** Проверка, что сохранённый локальный файл ещё открывается. */
    private suspend fun canOpenTrack(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(track.uri)
            val isLocalFile = uri.scheme.equals("content", ignoreCase = true) ||
                uri.scheme.equals("file", ignoreCase = true)
            if (!isLocalFile) {
                // Сетевой трек проверять сетевым запросом нельзя: восстановление
                // не должно ходить в сеть, доступность решит обычная ошибка Media3.
                true
            } else {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Файл сессии недоступен (${track.uri}): ${e.message}")
            false
        }
    }

    private fun updateServiceState(
        isPlaying: Boolean = exoPlayer?.playWhenReady == true,
        track: TrackEntity? = _serviceState.value.currentTrack,
        shuffle: Boolean = shuffleEnabled
    ) {
        _serviceState.value = PlaybackServiceState(
            isPlaying = isPlaying,
            currentTrack = track,
            shuffleEnabled = shuffle
        )
        // Уведомление здесь НЕ обновляется: единственный источник — notificationState
        // координатора. Иначе усиление/нарастание громкости обновляли бы уведомление
        // много раз в секунду.
    }

    /**
     * Part 2: задача удалена из недавних — сохраняем позицию (сервис может быть
     * убит системой без [onDestroy]).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackSession()
        // Задача удалена: сервис может быть убит системой в любой момент,
        // поэтому исходная системная громкость возвращается немедленно.
        systemVolumeController?.restoreImmediately()
        Log.i(TAG, "Задача удалена: позиция последней сессии сохранена")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Part 2: сохраняем позицию до освобождения плеера (пауза или игра).
        // Возвращённый Job нужен ниже: отмена serviceScope должна произойти
        // ПОСЛЕ завершения этого сохранения (правило: сессия сохраняется в onDestroy).
        val sessionSaveJob = savePlaybackSession()
        isDestroying = true
        // Часть 3: сервис завершается — системная громкость возвращается
        // немедленно. Scope контроллера независим от serviceScope, поэтому
        // запись очищенного резерва успевает завершиться.
        systemVolumeController?.restoreImmediately()
        systemVolumeController = null
        // Усиление окна тишины не переживает сервис: флаг снимается до
        // освобождения плеера (сам звук уже не важен, важно состояние).
        choppySilentWindow.end()
        choppyTickerJob?.cancel()
        playbackNotificationJob?.cancel()
        volumeRampJob?.cancel()
        alarmDurationJob?.cancel()
        pinnedAlarmWaitJob?.cancel()
        speakerOnPauseJob?.cancel()
        pendingAlarmStart = null
        activeAlarmSequenceId = -1L
        activeAlarmTriggerMs = 0L
        // finally-очистка отображаемого звука: после остановки сервиса тишина.
        coordinator.reset()
        // Уведомление не должно пережить сервис ни на одном пути остановки.
        clearPlaybackNotification()
        if (instance === this) {
            instance = null
        }
        try {
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения MediaSession", e)
        }
        // Эффекты эквалайзера освобождаются ДО плееров (им ещё нужны живые sessionId).
        equalizerEngine?.releaseAll()
        equalizerEngine = null
        // Плееры освобождаются синхронно, на том же главном потоке, что и раньше:
        // отложенный serviceScope.launch здесь невозможен, так как scope отменяется
        // ниже — иначе подписки и цикл автосохранения пережили бы сервис.
        try {
            exoPlayer?.release()
            exoPlayer = null
            microsoundPlayer?.release()
            microsoundPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения ExoPlayer", e)
        }
        // Отмена scope — последней корутиной в очереди главного потока: сохранение
        // сессии (запущено первым) успевает завершиться до отмены.
        serviceScope.launch {
            sessionSaveJob?.join()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SoundPlaybackService"

        /** Период тика прерывистой музыки и watchdog координатора (1 с). */
        private const val CHOPPY_TICK_MS = 1_000L

        /**
         * Живой экземпляр сервиса для отмены уже запущенной последовательности
         * из UI-слоя (удаление/выключение будильника). Не используется для
         * воспроизведения; все переходы по-прежнему идут через координатор.
         */
        @Volatile
        private var instance: SoundPlaybackService? = null

        /**
         * Действие пользователя из единого уведомления (play/pause, «Остановить»,
         * смахивание). Сервис НИКОГДА не запускается из уведомления: если он не
         * жив, это no-op с удалением устаревшего уведомления. Тап по уведомлению
         * сюда не приходит — он открывает Activity отдельным PendingIntent.
         * Обе команды уходят в координатор (см.
         * [SoundPlaybackService.onPlaybackNotificationPlayPauseRequested] и
         * [SoundPlaybackService.onPlaybackNotificationStopRequested]).
         */
        fun handlePlaybackNotificationAction(
            context: Context,
            action: String,
            kindName: String?,
            displayName: String?
        ) {
            val service = instance
            if (service == null) {
                Log.i(TAG, "Уведомление: сервис уже остановлен, действие проигнорировано")
                try {
                    NotificationManagerCompat.from(context).cancel(PlaybackNotification.ID)
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось убрать устаревшее уведомление: ${e.message}")
                }
                return
            }
            when (action) {
                PlaybackNotification.ACTION_TOGGLE_PLAY_PAUSE ->
                    service.onPlaybackNotificationPlayPauseRequested()
                PlaybackNotification.ACTION_STOP_SOUND,
                PlaybackNotification.ACTION_DISMISSED ->
                    service.onPlaybackNotificationStopRequested(kindName, displayName)
                else -> Log.i(TAG, "Уведомление: неизвестное действие $action проигнорировано")
            }
        }

        const val ACTION_PLAY = "com.personal.vibratuner.action.PLAY"
        const val ACTION_ALARM_START = "com.personal.vibratuner.action.ALARM_START"
        const val ACTION_PLAY_MICROSOUND = "com.personal.vibratuner.action.PLAY_MICROSOUND"
        const val ACTION_PAUSE = "com.personal.vibratuner.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.personal.vibratuner.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.personal.vibratuner.action.NEXT"
        const val ACTION_PREVIOUS = "com.personal.vibratuner.action.PREVIOUS"
        const val ACTION_CYCLE_PLAYBACK_MODE = "com.personal.vibratuner.action.CYCLE_PLAYBACK_MODE"
        const val ACTION_STOP = "com.personal.vibratuner.action.STOP"
        const val ACTION_SHUFFLE_TOGGLE = "com.personal.vibratuner.action.SHUFFLE_TOGGLE"
        /** Старт Foreground-сервиса для очереди плейлиста; саму очередь отдаёт UI через binder. */
        const val ACTION_PLAY_QUEUE = "com.personal.vibratuner.action.PLAY_QUEUE"

        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_ALARM_DURATION_MINUTES = "extra_alarm_duration_minutes"
        const val EXTRA_ALARM_TRACK_ID = "extra_alarm_track_id"
        const val EXTRA_ALARM_GAIN = "extra_alarm_gain"
        const val EXTRA_ALARM_SOURCE_TYPE = "extra_alarm_source_type"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_CATALOG_PLAYLIST_ID = "extra_alarm_catalog_playlist_id"
        const val EXTRA_ALARM_SHUFFLE = "extra_alarm_shuffle"
        const val EXTRA_MICROSOUND_ID = "extra_microsound_id"
        const val EXTRA_MICROSOUND_URI = "extra_microsound_uri"
        const val EXTRA_MICROSOUND_GAIN = "extra_microsound_gain"
        const val EXTRA_MICROSOUND_NAME = "extra_microsound_name"
        const val EXTRA_MICROSOUND_PINNED_PRELUDE = "extra_microsound_pinned_prelude"
        const val EXTRA_MICROSOUND_FAKE = "extra_microsound_fake"
        const val EXTRA_PINNED_ALARM_TRIGGER_MS = "extra_pinned_alarm_trigger_ms"
        const val EXTRA_ALARM_SEQUENCE_ID = "extra_alarm_sequence_id"
        const val EXTRA_SPEAKER_ON_DURATION_MS = "extra_speaker_on_duration_ms"

        // Страховка: если обычный микрозвук всё ещё играет на T, будильник стартует не позже T + 30 с.
        const val ALARM_WAIT_GRACE_MS = 30_000L

        /** Базовая (для 1x) страховка обычного микрозвука в реальном времени. */
        private const val ORDINARY_MICROSOUND_WATCHDOG_MS = 15_000L

        /** Период автосохранения позиции последней сессии (Part 2). */
        private const val SESSION_SAVE_INTERVAL_MS = 5_000L

        /** Запас поверх полной длительности «Включения колонки» + 10 с для watchdog. */
        const val SPEAKER_ON_SEQUENCE_GRACE_MS = 30_000L

        /** Отмена последовательности «Включение колонки» для удалённого/выключенного будильника. */
        fun cancelAlarmSequence(alarmId: Long) {
            instance?.cancelAlarmSequenceIfActive(alarmId)
        }

        /**
         * Удаление плейлиста из библиотеки (часть 3): если он сейчас играет,
         * живой сервис останавливает музыку безопасным путём. Сервис НЕ
         * запускается: если его нет, останавливать нечего.
         */
        fun notifyPlaylistDeleted(trackUris: Set<String>) {
            if (trackUris.isEmpty()) return
            val service = instance
            if (service == null) {
                Log.i(TAG, "Сервис не запущен: удаление плейлиста не затрагивает воспроизведение")
                return
            }
            service.onPlaylistTracksDeleted(trackUris)
        }

        /**
         * Part 3: файлы скачанных треков удалены из памяти. Живой сервис
         * останавливает играющий трек существующим путём координатора; сам
         * сервис НЕ запускается (нечего останавливать).
         */
        fun notifyDownloadedFilesDeleted(trackUris: Set<String>) {
            if (trackUris.isEmpty()) return
            val service = instance
            if (service == null) {
                Log.i(TAG, "Сервис не запущен: удаление файлов не затрагивает воспроизведение")
                return
            }
            service.onDownloadedFilesDeleted(trackUris)
        }

        fun startServiceIntent(context: Context, action: String = ACTION_PLAY, trackId: Long = -1L): Intent {
            return Intent(context, SoundPlaybackService::class.java).apply {
                this.action = action
                if (trackId != -1L) {
                    putExtra(EXTRA_TRACK_ID, trackId)
                }
            }
        }

        fun startAlarmIntent(
            context: Context,
            alarmId: Long,
            durationMinutes: Int,
            trackId: Long? = null,
            gain: Float = AlarmEntity.DEFAULT_GAIN,
            sourceType: String = AlarmSoundSource.TRACK.value,
            playlistId: Long? = null,
            shuffle: Boolean = false,
            catalogPlaylistId: String? = null
        ): Intent {
            return Intent(context, SoundPlaybackService::class.java).apply {
                this.action = ACTION_ALARM_START
                putExtra(EXTRA_ALARM_DURATION_MINUTES, durationMinutes)
                putExtra(EXTRA_ALARM_TRACK_ID, trackId ?: -1L)
                putExtra(EXTRA_ALARM_GAIN, gain)
                putExtra(EXTRA_ALARM_SOURCE_TYPE, sourceType)
                putExtra(EXTRA_ALARM_PLAYLIST_ID, playlistId ?: -1L)
                putExtra(EXTRA_ALARM_CATALOG_PLAYLIST_ID, catalogPlaylistId)
                putExtra(EXTRA_ALARM_SHUFFLE, shuffle)
                putExtra(EXTRA_TRACK_ID, -1L)
            }
        }

        fun startMicrosoundIntent(
            context: Context,
            microsoundId: Long,
            uri: String,
            gain: Float,
            name: String? = null
        ): Intent {
            return Intent(context, SoundPlaybackService::class.java).apply {
                this.action = ACTION_PLAY_MICROSOUND
                putExtra(EXTRA_MICROSOUND_ID, microsoundId)
                putExtra(EXTRA_MICROSOUND_URI, uri)
                putExtra(EXTRA_MICROSOUND_GAIN, gain)
                if (!name.isNullOrBlank()) {
                    putExtra(EXTRA_MICROSOUND_NAME, name)
                }
            }
        }

        /**
         * Запуск последовательности «Включение колонки» в sequenceStart.
         * Использует ТОТ ЖЕ ACTION_PLAY_MICROSOUND и тот же путь координатора,
         * что и обычный микрозвук; отличается флагом предзвука, длительностью
         * и временем будильника T.
         */
        fun startPinnedMicrosoundIntent(
            context: Context,
            alarmId: Long,
            uri: String,
            gain: Float,
            alarmTriggerMs: Long,
            speakerOnDurationMs: Long,
            name: String? = null
        ): Intent {
            return Intent(context, SoundPlaybackService::class.java).apply {
                this.action = ACTION_PLAY_MICROSOUND
                putExtra(EXTRA_ALARM_SEQUENCE_ID, alarmId)
                putExtra(EXTRA_MICROSOUND_URI, uri)
                putExtra(EXTRA_MICROSOUND_GAIN, gain)
                putExtra(EXTRA_MICROSOUND_PINNED_PRELUDE, true)
                putExtra(EXTRA_PINNED_ALARM_TRIGGER_MS, alarmTriggerMs)
                putExtra(EXTRA_SPEAKER_ON_DURATION_MS, speakerOnDurationMs)
                if (!name.isNullOrBlank()) {
                    putExtra(EXTRA_MICROSOUND_NAME, name)
                }
            }
        }

        /**
         * Запуск фейк-включения колонки: тот же ACTION_PLAY_MICROSOUND и тот же
         * путь координатора, что у обычного микрозвука. Отличается только флагом
         * фейка; URI (звук «Включения колонки»: встроенный или свой файл) и
         * усиление прочитаны из текущих настроек в момент срабатывания.
         * Длительность файла нужна только для watchdog.
         */
        fun startFakePinnedMicrosoundIntent(
            context: Context,
            uri: String,
            gain: Float,
            speakerOnDurationMs: Long,
            name: String? = null
        ): Intent {
            return Intent(context, SoundPlaybackService::class.java).apply {
                this.action = ACTION_PLAY_MICROSOUND
                putExtra(EXTRA_MICROSOUND_URI, uri)
                putExtra(EXTRA_MICROSOUND_GAIN, gain)
                putExtra(EXTRA_MICROSOUND_FAKE, true)
                putExtra(EXTRA_SPEAKER_ON_DURATION_MS, speakerOnDurationMs)
                if (!name.isNullOrBlank()) {
                    putExtra(EXTRA_MICROSOUND_NAME, name)
                }
            }
        }
    }
}
