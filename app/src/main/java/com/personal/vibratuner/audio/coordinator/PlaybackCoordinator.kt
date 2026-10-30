package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.audio.microsound.QuietHoursCalculator
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.data.local.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/**
 * Приоритеты событий и состояний аудиодвижка:
 * MICROSOUND (3) > CHOPPY_PAUSE (2) > MUSIC (1)
 */
enum class EventPriority(val level: Int) {
    MUSIC(1),
    CHOPPY_PAUSE(2),
    MICROSOUND(3)
}

data class QueuedMicroSound(
    val soundUri: String,
    val gain: Float,
    val maxDurationMs: Long,
    val timestampMs: Long,
    val isPinnedAlarmPrelude: Boolean = false,
    /** true — это фейк-включение колонки (тот же звук по интервалу, без будильника). */
    val isFakePinnedMicrosound: Boolean = false,
    /** Имя микрозвука/файла для отображения (может отсутствовать). */
    val displayName: String? = null
)

sealed interface CoordinatorState {
    object Idle : CoordinatorState

    data class MusicPlaying(
        val currentTrack: TrackEntity?,
        val isPaused: Boolean = false
    ) : CoordinatorState

    /**
     * Окно тишины прерывистой музыки (этап 4). Музыка при этом НЕ на паузе:
     * плеер продолжает играть (foreground-сервис, уведомление и MediaSession
     * живы), а усиление музыки равно 0. [savedPositionMs] — позиция на момент
     * старта окна: в конце окна к ней возвращаемся, чтобы трек продолжился ровно
     * с того места, где стал тихим. [resumePlaybackAfter] сохраняется для
     * совместимости переходов и описывает намерение «звук должен вернуться».
     */
    data class ChoppyPauseActive(
        val savedTrack: TrackEntity?,
        val resumePlaybackAfter: Boolean,
        val startedAtMs: Long,
        val maxDurationMs: Long,
        val savedPositionMs: Long = 0L
    ) : CoordinatorState {
        /**
         * Точный wall-clock момент (мс), когда окно тишины закончится и музыка
         * вернётся. Единственный источник времени для обратного отсчёта в
         * уведомлении: сервис не обновляет уведомление каждую секунду.
         */
        val resumeAtMs: Long get() = startedAtMs + maxDurationMs
    }

    /**
     * Предпрослушивание трека из окна выбора мелодии (Stage 2). Самый низкий
     * приоритет: состояние принимается только в покое и при музыке НА ПАУЗЕ,
     * любое другое событие сначала останавливает предпрослушивание и
     * обрабатывается обычным путём. `currentSound` для него `null`
     * (уведомления нет), `appIsAudible` = false (системная громкость не меняется
     * никогда).
     *
     * [pausedTrack] / [pausedPositionMs] — музыка, стоявшая на паузе. После
     * предпрослушивания она ОБЯЗАТЕЛЬНО возвращается на паузу с той же позицией,
     * поэтому ни музыка, ни сессия не теряются (и музыка никогда не остаётся
     * играть или навсегда замолкать из-за прослушивания).
     */
    data class Previewing(
        val previewTrack: TrackEntity,
        val pausedTrack: TrackEntity? = null,
        val pausedPositionMs: Long = 0L
    ) : CoordinatorState

    data class MicroSoundActive(
        val soundUri: String,
        val gain: Float = 0.8f,
        val savedTrack: TrackEntity?,
        val resumeMusicAfter: Boolean,
        /**
         * С этапа 4 окно тишины микрозвуком ОТМЕНЯЕТСЯ, поэтому эти два поля
         * всегда false/0L (оставлены для совместимости конструкторов и чтобы
         * явно фиксировать «остаток окна не переносится»).
         */
        val wasInChoppyPause: Boolean = false,
        val choppyPauseRemainingMs: Long = 0L,
        val queuedMicroSound: QueuedMicroSound? = null,
        /** true — это предзвук «Включение колонки» (звук + 10-секундная пауза перед будильником). */
        val isPinnedAlarmPrelude: Boolean = false,
        /** true — это фейк-включение колонки: обычный микрозвук по приоритету. */
        val isFakePinnedMicrosound: Boolean = false,
        /** true — звук уже доиграл, идёт фиксированная 10-секундная тишина перед будильником. */
        val isSpeakerOnSilence: Boolean = false,
        /** Имя микрозвука/файла для отображения (может отсутствовать). */
        val displayName: String? = null,
        val startedAtMs: Long,
        val maxDurationMs: Long
    ) : CoordinatorState

}

sealed interface CoordinatorEvent {
    val priority: EventPriority

    data class UserPlay(
        val track: TrackEntity?,
        /** true — воспроизведение запущено будильником (нужно только для currentSound). */
        val isAlarm: Boolean = false
    ) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    object UserPause : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    object UserResume : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /**
     * Будильник продолжает уже загруженный трек (возобновление после паузы).
     * Отдельное событие, чтобы не выдавать действие будильника за ручное
     * управление пользователем; воспроизведение идёт обычным путём ResumeMusicPlayback.
     */
    object AlarmMusicResume : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    object UserNext : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /**
     * «Предыдущий трек» (этап 3). Сколько пользователь РЕАЛЬНО слышал текущий
     * трек, приходит из сервиса (медиапозиция уже переведена в wall-clock по
     * скорости эквалайзера, часть 5): больше [TrackHistory.RESTART_THRESHOLD_MS] —
     * текущий трек начинается заново, иначе — трек из истории координатора.
     * Решение принимает только координатор.
     */
    data class UserPrevious(val currentWallClockMs: Long = 0L) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /**
     * Одна кнопка режима: OFF -> SHUFFLE -> REPEAT_ONE -> OFF.
     * Это НЕ событие воспроизведения: оно принимается в ЛЮБОМ состоянии
     * координатора и никогда не меняет текущий звук/приоритеты.
     */
    object UserCyclePlaybackMode : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    object UserStop : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /**
     * Смещение позиции музыки. Не является командой воспроизведения:
     * координатор только перемещает позицию и никогда не запускает музыку.
     */
    data class UserSeek(val positionMs: Long) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /**
     * Восстановление последней сессии (Part 2): трек и позиция из DataStore.
     * Принимается ТОЛЬКО в покое; музыка переходит в состояние паузы,
     * воспроизведение не запускается и аудиофокус не запрашивается.
     */
    data class RestoreSession(
        val track: TrackEntity,
        val positionMs: Long
    ) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    object TrackEnded : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    data class TrackError(val track: TrackEntity?, val error: Throwable?) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    // Для Этапа 2/4 (окно тишины прерывистой музыки)
    data class TriggerChoppyPause(
        val durationMs: Long,
        val timestampMs: Long,
        /** Позиция музыки на момент старта окна (медиавремя, мс). */
        val positionMs: Long = 0L
    ) : CoordinatorEvent {
        override val priority = EventPriority.CHOPPY_PAUSE
    }

    object ChoppyPauseCompleted : CoordinatorEvent {
        override val priority = EventPriority.CHOPPY_PAUSE
    }

    // Для Этапа 3 (микрозвуки)
    data class TriggerMicroSound(
        val soundUri: String,
        val gain: Float = 0.8f,
        val maxDurationMs: Long = 15_000L,
        val timestampMs: Long = System.currentTimeMillis(),
        /** true — закреплённый микрозвук перед будильником: ждём окончания до старта музыки. */
        val isPinnedAlarmPrelude: Boolean = false,
        /** true — фейк-включение колонки: тот же звук, но обычный приоритет микрозвука. */
        val isFakePinnedMicrosound: Boolean = false,
        /** Имя микрозвука/файла для currentSound (в БД не сохраняется). */
        val displayName: String? = null
    ) : CoordinatorEvent {
        override val priority = EventPriority.MICROSOUND
    }

    /**
     * Звук «Включение колонки» доиграл (или был остановлен вручную), началась
     * фиксированная 10-секундная тишина. Меняет только отображаемое состояние:
     * координатор остаётся в MicroSoundActive, чтобы посторонние звуки
     * по-прежнему отклонялись до старта музыки будильника.
     */
    object SpeakerOnSilenceStarted : CoordinatorEvent {
        override val priority = EventPriority.MICROSOUND
    }

    open class MicroSoundCompleted(val timestampMs: Long = System.currentTimeMillis()) : CoordinatorEvent {
        override val priority = EventPriority.MICROSOUND
        companion object : MicroSoundCompleted(0L)
    }

    data class MicroSoundError(val error: Throwable?) : CoordinatorEvent {
        override val priority = EventPriority.MICROSOUND
    }

    // Таймаут-страховка от зависших состояний паузы/звука
    data class WatchdogTimeoutTick(val currentTimestampMs: Long) : CoordinatorEvent {
        override val priority = EventPriority.MICROSOUND
    }

    /**
     * Начать/сменить предпрослушивание трека в окне выбора мелодии.
     * Принимается только в покое и при музыке на паузе: при играющей музыке,
     * окне тишины, микрозвуке или будильнике событие отклоняется с записью в лог.
     *
     * [pausedPositionMs] — позиция музыки, стоящей на паузе (для точного
     * возврата после прослушивания); для покоя не используется.
     */
    data class PreviewPlay(
        val track: TrackEntity,
        val pausedPositionMs: Long = 0L
    ) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /** Остановить предпрослушивание (закрытие окна, выбор трека, фон). */
    object StopPreview : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /** Трек предпрослушивания доиграл до конца — окно продолжает ждать. */
    object PreviewEnded : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }

    /** Ошибка воспроизведения предпрослушивания: остановиться, не мешая остальному. */
    data class PreviewError(val error: Throwable? = null) : CoordinatorEvent {
        override val priority = EventPriority.MUSIC
    }
}

sealed interface CoordinatorAction {
    data class StartMusicPlayback(val track: TrackEntity) : CoordinatorAction
    object PauseMusicPlayback : CoordinatorAction
    object ResumeMusicPlayback : CoordinatorAction
    object StopMusicPlayback : CoordinatorAction
    object PlayNextTrack : CoordinatorAction
    /** Перейти к треку из истории «Предыдущего» (тот же путь старта, что у музыки). */
    data class PlayPreviousTrack(val track: TrackEntity) : CoordinatorAction
    data class SeekTo(val positionMs: Long) : CoordinatorAction
    /** Подготовить трек и позицию без запуска: восстановленная сессия на паузе. */
    data class PreparePausedMusic(val track: TrackEntity, val positionMs: Long) : CoordinatorAction
    data class LogMessage(val message: String, val isWarning: Boolean = true) : CoordinatorAction

    data class StartChoppyPauseTimer(val durationMs: Long) : CoordinatorAction

    /**
     * Начать окно тишины прерывистой музыки: плеер продолжает играть, усиление
     * музыки становится 0, позиция [positionMs] запоминается для возврата.
     */
    data class MuteMusicForSilentWindow(
        val positionMs: Long,
        val durationMs: Long
    ) : CoordinatorAction

    /**
     * Восстановить обычное усиление музыки. Обязательное действие на КАЖДОМ
     * завершении/отмене окна тишины: усиление не имеет права остаться нулевым.
     */
    object UnmuteMusicPlayback : CoordinatorAction
    data class PlayMicroSoundTrack(val uri: String, val gain: Float) : CoordinatorAction
    object StopMicroSoundTrack : CoordinatorAction

    /** Начать фиксированную паузу 10 с после остановленного/доигранного «Включения колонки». */
    object StartSpeakerOnPostPause : CoordinatorAction

    /** Завершить срабатывание будильника (перепланирование, без отключения будущих). */
    object CompleteAlarmOccurrence : CoordinatorAction

    /**
     * Запустить предпрослушивание трека тем же плеером музыки, но БЕЗ очереди,
     * сохранения сессии, мини-плеера и уведомления.
     */
    data class StartTrackPreview(val track: TrackEntity) : CoordinatorAction

    /** Остановить предпрослушивание (плеер музыки останавливается). */
    object StopTrackPreview : CoordinatorAction
}

data class TransitionResult(
    val newState: CoordinatorState,
    val actions: List<CoordinatorAction>
)

/**
 * Координатор (State Machine) аудиодвижка.
 * Чистая детерминированная логика переходов состояний и разрешения конфликтов.
 */
class PlaybackCoordinator(
    initialState: CoordinatorState = CoordinatorState.Idle,
    private val defaultWatchdogTimeoutMs: Long = 15_000L,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    var state: CoordinatorState = initialState
        private set

    private val _currentSound = MutableStateFlow(
        CurrentSoundResolver.resolve(initialState, alarmMusicActive = false)
    )

    private val _appIsAudible = MutableStateFlow(AppAudibility.isAudible(initialState))

    /**
     * Музыка запущена будильником. Флаг живёт в координаторе и переживает
     * паузу музыки на время микрозвука/окна тишины, чтобы currentSound
     * после возврата музыки снова показывал ALARM. Меняется только событиями
     * и не влияет на воспроизведение.
     */
    private var alarmMusicActive: Boolean = false

    /**
     * Глобальные «Часы тишины» (этап 4). Значение кладёт сервис из того же
     * DataStore; координатор применяет единственное правило: в тишину не
     * начинает играть НИ ОДИН микрозвук — обычный, встроенный, фейк-включение
     * и «Включение колонки». Само окно микрозвука проверяется планировщиком и
     * ресивером, часы тишины важнее его всегда.
     */
    @Volatile
    private var quietHoursSettings: QuietHoursSettings = QuietHoursSettings()

    /** Обновляет «Часы тишины» (вызывает только сервис при изменении DataStore). */
    fun updateQuietHours(settings: QuietHoursSettings) {
        quietHoursSettings = settings
    }

    /** true — в момент [timestampMs] действуют включённые и корректные «Часы тишины». */
    fun isWithinQuietHours(timestampMs: Long = System.currentTimeMillis()): Boolean =
        QuietHoursCalculator.isWithinQuietHours(timestampMs, quietHoursSettings, zoneId)

    /**
     * Единственный звук, который слышен прямо сейчас; `null` — тишина.
     * Обновляется после КАЖДОГО перехода (start/end/interrupt/error/cancel/stop)
     * в [processEvent] и [stopCurrent], а также при смене трека следующим.
     * Только состояние: не влияет на воспроизведение.
     */
    val currentSound: StateFlow<CurrentSound?> = _currentSound.asStateFlow()

    /**
     * Содержимое единственного уведомления воспроизведения (`null` — уведомления
     * нет). В отличие от [currentSound] НЕ обнуляется на паузе музыки: уведомление
     * с кнопкой «Играть» обязано пережить ручную паузу. Строится ОДНИМ маппером
     * [PlaybackNotificationContent.from] в том же месте, где обновляется
     * [currentSound], поэтому не может «залипнуть» на устаревшем состоянии.
     */
    private val _notificationState = MutableStateFlow(
        PlaybackNotificationContent.from(initialState)
    )
    val notificationState: StateFlow<PlaybackNotificationState?> =
        _notificationState.asStateFlow()

    /**
     * true — приложение реально слышно или уже запускает звук (музыка играет
     * по намерению, включая буферизацию и смену трека; играет микрозвук/фейк/
     * «Включение колонки»). false — покой, пауза музыки, прерывистая пауза и
     * 10-секундная тишина после «Включения колонки».
     *
     * Единственный сигнал слышимости для `SystemVolumeController` (часть 3):
     * системная громкость поднимается только при true и возвращается при false.
     * Обновляется после КАЖДОГО перехода вместе с [currentSound]; на
     * воспроизведение не влияет.
     */
    val appIsAudible: StateFlow<Boolean> = _appIsAudible.asStateFlow()

    /**
     * Режим порядка воспроизведения (этап 3): OFF / SHUFFLE / REPEAT_ONE.
     * Единственный источник истины для кнопки мини-плеера. Живёт только в памяти
     * координатора: после перезапуска приложения — снова OFF.
     */
    private val _playbackMode = MutableStateFlow(PlaybackMode.OFF)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    /**
     * URI трека, который сейчас предпроигрывается из окна выбора мелодии,
     * либо `null`. Единственный источник истины для иконки play/pause в строке
     * окна: UI не хранит собственное состояние предпрослушивания.
     */
    private val _previewTrackUri = MutableStateFlow<String?>(null)
    val previewTrackUri: StateFlow<String?> = _previewTrackUri.asStateFlow()

    /**
     * Можно ли сейчас начать предпрослушивание (покой, музыка на паузе или уже
     * идущее предпрослушивание). Окно выбора делает кнопку недоступной, пока
     * играет музыка/микрозвук/сеанс, — вместо «мёртвой» кнопки и без сообщений
     * об ошибке. Команда всё равно перепроверяется координатором.
     */
    private val _previewAvailable = MutableStateFlow(previewAllowedIn(initialState))
    val previewAvailable: StateFlow<Boolean> = _previewAvailable.asStateFlow()

    /**
     * История проигранных треков для «Предыдущего» (не более 50, в памяти).
     * Наполняется в [onMusicTrackStarted] — то есть по ФАКТИЧЕСКОМУ порядку
     * воспроизведения, поэтому shuffle тоже даёт честную историю.
     */
    private val trackHistory = TrackHistory()

    /** Сколько треков доступно для «Предыдущего» (0 — история пуста). */
    val historySize: Int get() = trackHistory.size

    /** Время последней реально выполненной остановки — защита от двойного нажатия. */
    private var lastStopCurrentAtMs: Long = Long.MIN_VALUE

    fun processEvent(event: CoordinatorEvent): List<CoordinatorAction> {
        // Режим порядка — не событие звука: принимается в любом состоянии,
        // не меняет ни state, ни currentSound, ни приоритеты.
        if (event is CoordinatorEvent.UserCyclePlaybackMode) {
            return try {
                val newMode = _playbackMode.value.next()
                _playbackMode.value = newMode
                // Режим показан в подзаголовке уведомления: обновляем ТОЛЬКО его,
                // не создавая переходов и не трогая воспроизведение.
                refreshNotificationState()
                listOf(
                    CoordinatorAction.LogMessage(
                        "Режим воспроизведения: $newMode",
                        isWarning = false
                    )
                )
            } catch (e: Exception) {
                // Любая ошибка логики режима: лог и безопасный откат к OFF.
                _playbackMode.value = PlaybackMode.OFF
                listOf(
                    CoordinatorAction.LogMessage(
                        "Ошибка переключения режима (${e.message}): режим сброшен в OFF",
                        isWarning = true
                    )
                )
            }
        }
        // Окончание трека в REPEAT_ONE — это не тишина: currentSound сохраняется.
        val keepsSoundOnTrackEnd = event is CoordinatorEvent.TrackEnded && restartsCurrentTrack()
        // Предпрослушивание — самый низкий приоритет: в любом занятом состоянии
        // команда отклоняется с записью в лог (микрозвук/окно тишины/играющая
        // музыка/будильник не прерываются). Ничего не ломается: окно просто
        // остаётся без звука, а текущий звук продолжает играть. Разрешено оно
        // в покое, при музыке НА ПАУЗЕ и поверх собственного предпрослушивания.
        if (event is CoordinatorEvent.PreviewPlay && !previewAllowedIn(state)) {
            return listOf(
                CoordinatorAction.LogMessage(
                    "Предпрослушивание пропущено: занято (${describeState(state)})",
                    isWarning = true
                )
            )
        }
        // Этап 4: глобальные «Часы тишины» ВСЕГДА важнее собственных окон
        // микрозвуков. Это ФИНАЛЬНАЯ проверка прямо перед запуском звука:
        // ни один микрозвук (обычный, встроенный, фейк, «Включение колонки»)
        // не начинает играть в тишину. Событие отклоняется с записью в лог,
        // состояние и текущая музыка не меняются.
        if (event is CoordinatorEvent.TriggerMicroSound && isWithinQuietHours(event.timestampMs)) {
            return listOf(
                CoordinatorAction.LogMessage(
                    "Микрозвук пропущен: сейчас «Часы тишины» " +
                        "(${quietHoursSettings.startHour}:${quietHoursSettings.startMinute}–" +
                        "${quietHoursSettings.endHour}:${quietHoursSettings.endMinute})",
                    isWarning = true
                )
            )
        }
        return try {
            val result = transition(state, event)
            state = result.newState
            result.actions
        } finally {
            // finally-обновление отображаемого состояния: даже при исключении
            // currentSound не останется со старым непустым значением.
            updateAlarmMusicMode(event)
            refreshCurrentSound(
                forceNull = (event is CoordinatorEvent.TrackEnded && !keepsSoundOnTrackEnd) ||
                    event is CoordinatorEvent.TrackError
            )
            refreshPreview()
        }
    }

    /** Короткое описание занятого состояния для лога (предпрослушивание отклонено). */
    private fun describeState(current: CoordinatorState): String = when (current) {
        is CoordinatorState.Idle -> "покой"
        is CoordinatorState.Previewing -> "предпрослушивание"
        is CoordinatorState.MusicPlaying -> if (current.isPaused) "музыка на паузе" else "музыка"
        is CoordinatorState.ChoppyPauseActive -> "окно тишины прерывистой музыки"
        is CoordinatorState.MicroSoundActive -> "микрозвук"
    }

    /**
     * Где допустимо предпрослушивание: покой, музыка на паузе (сессия
     * возвращается на паузу после прослушивания) и уже идущее прослушивание.
     * Играющая музыка, микрозвук, окно тишины и будильник — НИКОГДА.
     */
    private fun previewAllowedIn(current: CoordinatorState): Boolean = when (current) {
        is CoordinatorState.Idle,
        is CoordinatorState.Previewing -> true
        is CoordinatorState.MusicPlaying -> current.isPaused
        is CoordinatorState.ChoppyPauseActive,
        is CoordinatorState.MicroSoundActive -> false
    }

    /**
     * Немедленно останавливает звук, описанный [currentSound], считая его
     * ЗАВЕРШЁННЫМ (не отключённой задачей). Возвращает действия для сервиса.
     *
     * Семантика по видам:
     * - MICROSOUND / FAKE_SPEAKER_ON — тот же путь, что при естественном окончании:
     *   музыка восстанавливается, следующее срабатывание остаётся запланированным;
     * - SPEAKER_ON — звук завершается, запускается пауза 10 с, затем музыка
     *   будильника; будильник не отменяется;
     * - MUSIC — пауза (очередь и позиция сохраняются);
     * - ALARM — завершение текущего срабатывания без отключения будущих.
     *
     * Идемпотентно: при пустом [currentSound] и при повторном вызове в течение
     * [STOP_CURRENT_DUPLICATE_WINDOW_MS] возвращается только запись в лог.
     * Вызывается в том же (главном) потоке, что и [processEvent].
     */
    fun stopCurrent(nowMs: Long = System.currentTimeMillis()): List<CoordinatorAction> {
        val actions = mutableListOf<CoordinatorAction>()
        try {
            val sound = currentSound.value
            if (sound == null) {
                actions.add(
                    CoordinatorAction.LogMessage(
                        "Остановка текущего звука: сейчас ничего не звучит (no-op)",
                        isWarning = false
                    )
                )
                return actions
            }
            if (isDuplicateStop(nowMs)) {
                actions.add(
                    CoordinatorAction.LogMessage(
                        "Остановка текущего звука: повторное нажатие проигнорировано (${sound.kind})",
                        isWarning = false
                    )
                )
                return actions
            }

            lastStopCurrentAtMs = nowMs

            // Окно тишины — это не пауза: сначала корректно завершаем его
            // (усиление музыки восстанавливается, позиция возвращается), а уже
            // затем останавливаем музыку обычным путём. Иначе усиление могло бы
            // остаться нулевым после пользовательской остановки.
            if (state is CoordinatorState.ChoppyPauseActive) {
                actions.addAll(processEvent(CoordinatorEvent.ChoppyPauseCompleted))
            }

            val stopAction = StopCurrentPolicy.actionFor(sound.kind)
            val namePart = sound.displayName?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            actions.add(
                CoordinatorAction.LogMessage(
                    "Остановка текущего звука: ${sound.kind}$namePart -> $stopAction",
                    isWarning = false
                )
            )

            when (stopAction) {
                StopCurrentAction.STOP_MICROSOUND -> {
                    // Как при естественном окончании: остановить плеер, затем
                    // обычный переход завершения (возврат музыки/очереди).
                    actions.add(CoordinatorAction.StopMicroSoundTrack)
                    actions.addAll(processEvent(CoordinatorEvent.MicroSoundCompleted(nowMs)))
                }
                StopCurrentAction.FINISH_SPEAKER_ON -> {
                    // Координатор остаётся в MicroSoundActive (приоритет сохраняется),
                    // но звук больше не слышен; сервис запускает паузу 10 с.
                    actions.add(CoordinatorAction.StopMicroSoundTrack)
                    actions.addAll(processEvent(CoordinatorEvent.SpeakerOnSilenceStarted))
                    actions.add(CoordinatorAction.StartSpeakerOnPostPause)
                }
                StopCurrentAction.PAUSE_MUSIC -> {
                    actions.addAll(processEvent(CoordinatorEvent.UserPause))
                }
                StopCurrentAction.STOP_ALARM -> {
                    actions.addAll(processEvent(CoordinatorEvent.UserStop))
                    actions.add(CoordinatorAction.CompleteAlarmOccurrence)
                }
            }
            return actions
        } finally {
            refreshCurrentSound()
        }
    }

    /**
     * Обновляет ТОЛЬКО отображаемый трек после фактического старта следующего
     * трека в сервисе (плеером управляет по-прежнему координатор через события).
     * Не создаёт новых переходов и не меняет воспроизведение.
     *
     * Здесь же ведётся история «Предыдущего»: это единственная точка, где
     * известно, что трек РЕАЛЬНО начал звучать ([recordInHistory] = false —
     * возврат назад по истории, тогда покидаемый трек в историю не попадает).
     */
    fun onMusicTrackStarted(track: TrackEntity?, recordInHistory: Boolean = true) {
        if (track != null) {
            trackHistory.recordStart(track, recordInHistory)
        }
        val current = state
        if (current !is CoordinatorState.MusicPlaying) return
        if (current.currentTrack == track) return
        state = current.copy(currentTrack = track)
        refreshCurrentSound()
    }

    /** Аварийный сброс при остановке сервиса: звука больше нет, режим и история сброшены. */
    fun reset() {
        state = CoordinatorState.Idle
        alarmMusicActive = false
        _currentSound.value = null
        _notificationState.value = null
        _appIsAudible.value = false
        _previewTrackUri.value = null
        _previewAvailable.value = true
        lastStopCurrentAtMs = Long.MIN_VALUE
        trackHistory.clear()
        // Режим — только в памяти: новый сервис начинает с OFF.
        _playbackMode.value = PlaybackMode.OFF
    }

    /** true — окончание трека должно перезапустить его же (REPEAT_ONE, не музыка будильника). */
    private fun restartsCurrentTrack(): Boolean =
        _playbackMode.value == PlaybackMode.REPEAT_ONE && !alarmMusicActive

    private fun updateAlarmMusicMode(event: CoordinatorEvent) {
        when (event) {
            // Учитываем только ПРИНЯТОЕ событие: отклонённое координатором
            // низкоприоритетное событие не должно менять вид звука.
            is CoordinatorEvent.UserPlay -> {
                if (state is CoordinatorState.MusicPlaying) {
                    alarmMusicActive = event.isAlarm
                }
            }
            is CoordinatorEvent.UserResume -> {
                if (state is CoordinatorState.MusicPlaying) {
                    alarmMusicActive = false
                }
            }
            is CoordinatorEvent.UserPrevious -> {
                // Ручное управление: режим усиления будильника больше не действует.
                if (state is CoordinatorState.MusicPlaying) {
                    alarmMusicActive = false
                }
            }
            is CoordinatorEvent.AlarmMusicResume -> {
                if (state is CoordinatorState.MusicPlaying) {
                    alarmMusicActive = true
                }
            }
            is CoordinatorEvent.UserStop -> {
                alarmMusicActive = false
                // Пользовательская остановка завершает воспроизведение — историю
                // «Предыдущего» больше не к чему применять.
                trackHistory.clear()
            }
            else -> Unit
        }
        if (state is CoordinatorState.Idle || state is CoordinatorState.Previewing) {
            alarmMusicActive = false
        }
    }

    /**
     * Публикует URI трека предпрослушивания для окна выбора мелодии.
     * Вызывается после КАЖДОГО перехода и при сбросе: иконка play/pause в строке
     * не может «залипнуть» на треке, который уже не звучит.
     */
    private fun refreshPreview() {
        _previewTrackUri.value = (state as? CoordinatorState.Previewing)?.previewTrack?.uri
        // Кнопка прослушивания доступна в покое, при музыке на паузе и во время
        // уже идущего прослушивания; при любом другом звуке — недоступна.
        _previewAvailable.value = previewAllowedIn(state)
    }

    private fun refreshCurrentSound(forceNull: Boolean = false) {
        // Ошибка/окончание музыки не должны обнулять звучащий микрозвук.
        val shouldForceNull = forceNull && state !is CoordinatorState.MicroSoundActive
        _currentSound.value = if (shouldForceNull) {
            null
        } else {
            CurrentSoundResolver.resolve(state, alarmMusicActive)
        }
        refreshNotificationState(shouldForceNull)
        _appIsAudible.value = AppAudibility.isAudible(state)
    }

    /** Содержимое уведомления считается ТОЛЬКО по текущему состоянию координатора. */
    private fun refreshNotificationState(forceNull: Boolean = false) {
        _notificationState.value = PlaybackNotificationContent.from(
            state = state,
            alarmMusicActive = alarmMusicActive,
            forceNull = forceNull,
            playbackMode = _playbackMode.value
        )
    }

    private fun isDuplicateStop(nowMs: Long): Boolean {
        val elapsedMs = nowMs - lastStopCurrentAtMs
        return elapsedMs in 0..STOP_CURRENT_DUPLICATE_WINDOW_MS
    }

    private fun transition(
        currentState: CoordinatorState,
        event: CoordinatorEvent
    ): TransitionResult {
        return when (currentState) {
            is CoordinatorState.Idle -> handleIdle(event)
            is CoordinatorState.MusicPlaying -> handleMusicPlaying(currentState, event)
            is CoordinatorState.ChoppyPauseActive -> handleChoppyPause(currentState, event)
            is CoordinatorState.MicroSoundActive -> handleMicroSound(currentState, event)
            is CoordinatorState.Previewing -> handlePreviewing(currentState, event)
        }
    }

    private fun handleIdle(event: CoordinatorEvent): TransitionResult {
        return when (event) {
            is CoordinatorEvent.UserPlay -> {
                if (event.track != null) {
                    TransitionResult(
                        newState = CoordinatorState.MusicPlaying(
                            currentTrack = event.track,
                            isPaused = false
                        ),
                        actions = listOf(CoordinatorAction.StartMusicPlayback(event.track))
                    )
                } else {
                    TransitionResult(newState = CoordinatorState.Idle, actions = emptyList())
                }
            }
            is CoordinatorEvent.TriggerMicroSound -> {
                // Микрозвук может играть и в состоянии покоя
                TransitionResult(
                    newState = CoordinatorState.MicroSoundActive(
                        soundUri = event.soundUri,
                        gain = event.gain,
                        savedTrack = null,
                        resumeMusicAfter = false,
                        isPinnedAlarmPrelude = event.isPinnedAlarmPrelude,
                        isFakePinnedMicrosound = event.isFakePinnedMicrosound,
                        displayName = event.displayName,
                        startedAtMs = event.timestampMs,
                        maxDurationMs = event.maxDurationMs
                    ),
                    actions = listOf(CoordinatorAction.PlayMicroSoundTrack(event.soundUri, event.gain))
                )
            }
            is CoordinatorEvent.RestoreSession -> {
                // Восстановление сессии — только подготовка в паузу, без звука.
                TransitionResult(
                    newState = CoordinatorState.MusicPlaying(
                        currentTrack = event.track,
                        isPaused = true
                    ),
                    actions = listOf(
                        CoordinatorAction.PreparePausedMusic(
                            track = event.track,
                            positionMs = event.positionMs.coerceAtLeast(0L)
                        )
                    )
                )
            }
            is CoordinatorEvent.UserSeek -> {
                // Смещать нечего: музыка не активна, и запускать её запрещено
                TransitionResult(
                    newState = CoordinatorState.Idle,
                    actions = listOf(
                        CoordinatorAction.LogMessage(
                            "Смещение позиции проигнорировано: воспроизведение неактивно",
                            isWarning = false
                        )
                    )
                )
            }
            is CoordinatorEvent.UserPrevious -> {
                // В покое переключать нечего и запускать музыку запрещено.
                TransitionResult(
                    newState = CoordinatorState.Idle,
                    actions = listOf(
                        CoordinatorAction.LogMessage(
                            "Предыдущий трек: воспроизведение неактивно",
                            isWarning = false
                        )
                    )
                )
            }
            is CoordinatorEvent.PreviewPlay -> {
                // Предпрослушивание допустимо в покое: ничего не играет и нет
                // сессии, поэтому вмешаться некуда.
                TransitionResult(
                    newState = CoordinatorState.Previewing(event.track),
                    actions = listOf(CoordinatorAction.StartTrackPreview(event.track))
                )
            }
            else -> TransitionResult(CoordinatorState.Idle, emptyList())
        }
    }

    /**
     * Предпрослушивание трека (самый низкий приоритет).
     *
     * Правила:
     * - команда на ДРУГОЙ трек просто заменяет предпрослушивание;
     * - закрытие окна/окончание трека/ошибка останавливают плеер и возвращают
     *   покой либо музыку на паузу с той же позицией;
     * - любое другое событие (музыка, микрозвук, окно тишины) ВАЖНЕЕ: сначала
     *   предпрослушивание останавливается, восстанавливается пауза музыки (если
     *   она была), затем событие обрабатывается обычным путём — так
     *   предпрослушивание никогда не блокирует будильник/микрозвук и не
     *   оставляет музыку на паузе навсегда;
     * - таймаут-страховка к предпрослушиванию не применяется.
     */
    private fun handlePreviewing(
        current: CoordinatorState.Previewing,
        event: CoordinatorEvent
    ): TransitionResult {
        return when (event) {
            is CoordinatorEvent.PreviewPlay -> {
                if (event.track.uri == current.previewTrack.uri) {
                    // Тот же трек: состояние не меняется, плеер не перезапускается.
                    TransitionResult(current, emptyList())
                } else {
                    TransitionResult(
                        newState = current.copy(previewTrack = event.track),
                        actions = listOf(CoordinatorAction.StartTrackPreview(event.track))
                    )
                }
            }
            is CoordinatorEvent.StopPreview,
            is CoordinatorEvent.PreviewEnded -> restoreAfterPreview(current)
            is CoordinatorEvent.PreviewError -> {
                val restored = restoreAfterPreview(current)
                TransitionResult(
                    newState = restored.newState,
                    actions = restored.actions + CoordinatorAction.LogMessage(
                        "Предпрослушивание прервано ошибкой: ${event.error?.message}",
                        isWarning = true
                    )
                )
            }
            // Страховка от зависаний относится к паузам/микрозвукам, не к предпрослушиванию.
            is CoordinatorEvent.WatchdogTimeoutTick -> TransitionResult(current, emptyList())
            else -> {
                // Событие важнее: останавливаем предпрослушивание, возвращаем музыку
                // на паузу (если она была) и обрабатываем событие обычным путём.
                // Второго пути воспроизведения не появляется, музыка не «залипает».
                val restoredState = current.pausedTrack?.let { paused ->
                    CoordinatorState.MusicPlaying(currentTrack = paused, isPaused = true)
                } ?: CoordinatorState.Idle
                val restoreActions = mutableListOf<CoordinatorAction>(
                    CoordinatorAction.StopTrackPreview
                )
                current.pausedTrack?.let { paused ->
                    restoreActions.add(
                        CoordinatorAction.PreparePausedMusic(
                            track = paused,
                            positionMs = current.pausedPositionMs
                        )
                    )
                }
                val result = when (restoredState) {
                    is CoordinatorState.MusicPlaying ->
                        handleMusicPlaying(restoredState, event)
                    else -> handleIdle(event)
                }
                TransitionResult(
                    newState = result.newState,
                    actions = restoreActions +
                        CoordinatorAction.LogMessage(
                            "Предпрослушивание остановлено: $event",
                            isWarning = false
                        ) +
                        result.actions
                )
            }
        }
    }

    /**
     * Выход из предпрослушивания: если музыка стояла на паузе, она возвращается
     * на паузу с той же позицией (плеер снова держит её элемент, мини-плеер и
     * сессия не теряются). Иначе — покой.
     */
    private fun restoreAfterPreview(current: CoordinatorState.Previewing): TransitionResult {
        val paused = current.pausedTrack
        return if (paused != null) {
            TransitionResult(
                newState = CoordinatorState.MusicPlaying(currentTrack = paused, isPaused = true),
                actions = listOf(
                    CoordinatorAction.StopTrackPreview,
                    CoordinatorAction.PreparePausedMusic(
                        track = paused,
                        positionMs = current.pausedPositionMs
                    )
                )
            )
        } else {
            TransitionResult(
                newState = CoordinatorState.Idle,
                actions = listOf(CoordinatorAction.StopTrackPreview)
            )
        }
    }

    private fun handleMusicPlaying(
        current: CoordinatorState.MusicPlaying,
        event: CoordinatorEvent
    ): TransitionResult {
        return when (event) {
            is CoordinatorEvent.UserPlay -> {
                if (event.track != null && event.track != current.currentTrack) {
                    TransitionResult(
                        newState = CoordinatorState.MusicPlaying(
                            currentTrack = event.track,
                            isPaused = false
                        ),
                        actions = listOf(CoordinatorAction.StartMusicPlayback(event.track))
                    )
                } else {
                    // Тот же трек: возобновление
                    TransitionResult(
                        newState = current.copy(isPaused = false),
                        actions = listOf(CoordinatorAction.ResumeMusicPlayback)
                    )
                }
            }
            is CoordinatorEvent.UserPause -> {
                TransitionResult(
                    newState = current.copy(isPaused = true),
                    actions = listOf(CoordinatorAction.PauseMusicPlayback)
                )
            }
            is CoordinatorEvent.UserResume -> {
                TransitionResult(
                    newState = current.copy(isPaused = false),
                    actions = listOf(CoordinatorAction.ResumeMusicPlayback)
                )
            }
            is CoordinatorEvent.AlarmMusicResume -> {
                // Будильник продолжает уже загруженный трек тем же действием,
                // что и обычное возобновление; отличается только вид звука (ALARM).
                TransitionResult(
                    newState = current.copy(isPaused = false),
                    actions = listOf(CoordinatorAction.ResumeMusicPlayback)
                )
            }
            is CoordinatorEvent.PreviewPlay -> {
                // Предпрослушивание при музыке НА ПАУЗЕ: трек музыки и позиция
                // запоминаются, после прослушивания сессия вернётся на паузу.
                TransitionResult(
                    newState = CoordinatorState.Previewing(
                        previewTrack = event.track,
                        pausedTrack = current.currentTrack,
                        pausedPositionMs = event.pausedPositionMs.coerceAtLeast(0L)
                    ),
                    actions = listOf(CoordinatorAction.StartTrackPreview(event.track))
                )
            }
            is CoordinatorEvent.UserNext -> {
                TransitionResult(
                    newState = current,
                    actions = listOf(CoordinatorAction.PlayNextTrack)
                )
            }
            is CoordinatorEvent.UserPrevious -> {
                // Сколько пользователь слышал трек (wall-clock): > 3 с — начинаем заново.
                if (event.currentWallClockMs > TrackHistory.RESTART_THRESHOLD_MS) {
                    TransitionResult(
                        newState = current.copy(isPaused = false),
                        actions = listOf(
                            CoordinatorAction.SeekTo(0L),
                            CoordinatorAction.ResumeMusicPlayback,
                            CoordinatorAction.LogMessage(
                                "Предыдущий: перезапуск текущего трека с начала",
                                isWarning = false
                            )
                        )
                    )
                } else {
                    val previousTrack = trackHistory.popPrevious()
                    if (previousTrack != null) {
                        TransitionResult(
                            newState = current.copy(currentTrack = previousTrack, isPaused = false),
                            actions = listOf(
                                CoordinatorAction.PlayPreviousTrack(previousTrack),
                                CoordinatorAction.LogMessage(
                                    "Предыдущий: «${previousTrack.title}» из истории",
                                    isWarning = false
                                )
                            )
                        )
                    } else {
                        // История пуста — те же действия, что при перезапуске.
                        TransitionResult(
                            newState = current.copy(isPaused = false),
                            actions = listOf(
                                CoordinatorAction.SeekTo(0L),
                                CoordinatorAction.ResumeMusicPlayback,
                                CoordinatorAction.LogMessage(
                                    "Предыдущий: история пуста, текущий трек с начала",
                                    isWarning = false
                                )
                            )
                        )
                    }
                }
            }
            is CoordinatorEvent.UserStop -> {
                TransitionResult(
                    newState = CoordinatorState.Idle,
                    actions = listOf(CoordinatorAction.StopMusicPlayback)
                )
            }
            is CoordinatorEvent.UserSeek -> {
                // Только перемещение позиции: состояние (включая isPaused) не меняется,
                // воспроизведение не запускается.
                TransitionResult(
                    newState = current,
                    actions = listOf(CoordinatorAction.SeekTo(event.positionMs.coerceAtLeast(0L)))
                )
            }
            is CoordinatorEvent.TrackEnded -> {
                if (restartsCurrentTrack()) {
                    // REPEAT_ONE: текущий трек начинается заново тем же плеером.
                    // Микрозвук/прерывистая пауза сюда не попадают: их состояния
                    // обрабатываются отдельно и как раньше ставят музыку на паузу.
                    TransitionResult(
                        newState = current,
                        actions = listOf(
                            CoordinatorAction.SeekTo(0L),
                            CoordinatorAction.ResumeMusicPlayback
                        )
                    )
                } else {
                    // По кругу: запрашиваем следующий трек
                    TransitionResult(
                        newState = current,
                        actions = listOf(CoordinatorAction.PlayNextTrack)
                    )
                }
            }
            is CoordinatorEvent.TrackError -> {
                // При ошибке: не падаем, логируем, пробуем следующий трек
                TransitionResult(
                    newState = current,
                    actions = listOf(
                        CoordinatorAction.LogMessage("Ошибка трека: ${event.track?.title}, причина: ${event.error?.message}"),
                        CoordinatorAction.PlayNextTrack
                    )
                )
            }
            is CoordinatorEvent.TriggerChoppyPause -> {
                // Приоритет CHOPPY_PAUSE > MUSIC. Плеер НЕ ставится на паузу:
                // окно тишины — это усиление 0 при продолжающемся воспроизведении,
                // иначе сервис теряет foreground и таймер возобновления замирает.
                val savedPosition = event.positionMs.coerceAtLeast(0L)
                TransitionResult(
                    newState = CoordinatorState.ChoppyPauseActive(
                        savedTrack = current.currentTrack,
                        resumePlaybackAfter = !current.isPaused,
                        startedAtMs = event.timestampMs,
                        maxDurationMs = event.durationMs,
                        savedPositionMs = savedPosition
                    ),
                    actions = listOf(
                        CoordinatorAction.MuteMusicForSilentWindow(
                            positionMs = savedPosition,
                            durationMs = event.durationMs
                        ),
                        CoordinatorAction.LogMessage(
                            "Прерывистая музыка: окно тишины ${event.durationMs} мс, " +
                                "плеер продолжает играть, позиция ${savedPosition} мс",
                            isWarning = false
                        )
                    )
                )
            }
            is CoordinatorEvent.TriggerMicroSound -> {
                // Приоритет MICROSOUND > MUSIC
                val resumeAfter = !current.isPaused
                TransitionResult(
                    newState = CoordinatorState.MicroSoundActive(
                        soundUri = event.soundUri,
                        gain = event.gain,
                        savedTrack = current.currentTrack,
                        resumeMusicAfter = resumeAfter,
                        isPinnedAlarmPrelude = event.isPinnedAlarmPrelude,
                        isFakePinnedMicrosound = event.isFakePinnedMicrosound,
                        displayName = event.displayName,
                        startedAtMs = event.timestampMs,
                        maxDurationMs = event.maxDurationMs
                    ),
                    actions = listOf(
                        CoordinatorAction.PauseMusicPlayback,
                        CoordinatorAction.PlayMicroSoundTrack(event.soundUri, event.gain)
                    )
                )
            }
            else -> TransitionResult(current, emptyList())
        }
    }

    private fun handleChoppyPause(
        current: CoordinatorState.ChoppyPauseActive,
        event: CoordinatorEvent
    ): TransitionResult {
        // Текущий приоритет: CHOPPY_PAUSE (2). Музыка при этом продолжает
        // играть с нулевым усилением, поэтому переходы «в музыку» безопасны.
        return when {
            // Более высокий приоритет: MICROSOUND (3) всегда выигрывает у окна
            // тишины. Окно при этом ОТМЕНЯЕТСЯ (а не ставится на паузу с
            // остатком): после микрозвука музыка обязана снова звучать, иначе
            // усиление осталось бы нулевым. Остаток окна не переносится —
            // следующий цикл начнётся с чистого интервала X.
            event is CoordinatorEvent.TriggerMicroSound -> {
                TransitionResult(
                    newState = CoordinatorState.MicroSoundActive(
                        soundUri = event.soundUri,
                        gain = event.gain,
                        savedTrack = current.savedTrack,
                        resumeMusicAfter = current.resumePlaybackAfter,
                        wasInChoppyPause = false,
                        choppyPauseRemainingMs = 0L,
                        isPinnedAlarmPrelude = event.isPinnedAlarmPrelude,
                        isFakePinnedMicrosound = event.isFakePinnedMicrosound,
                        displayName = event.displayName,
                        startedAtMs = event.timestampMs,
                        maxDurationMs = event.maxDurationMs
                    ),
                    actions = listOf(
                        CoordinatorAction.LogMessage(
                            "Микрозвук прервал окно тишины прерывистой музыки: " +
                                "окно отменено, усиление музыки восстанавливается",
                            isWarning = false
                        ),
                        CoordinatorAction.PauseMusicPlayback,
                        CoordinatorAction.UnmuteMusicPlayback,
                        CoordinatorAction.PlayMicroSoundTrack(event.soundUri, event.gain)
                    )
                )
            }
            event is CoordinatorEvent.ChoppyPauseCompleted -> {
                finishSilentWindow(current)
            }
            // Таймаут-страховка зависшего окна. Запас времени нужен, чтобы
            // штатное завершение (тикающий автомат) не логировалось как сбой.
            event is CoordinatorEvent.WatchdogTimeoutTick -> {
                if (event.currentTimestampMs - current.startedAtMs >=
                    current.maxDurationMs + CHOPPY_WATCHDOG_GRACE_MS
                ) {
                    finishSilentWindow(
                        current = current,
                        warning = "Сработала таймаут-страховка окна тишины прерывистой музыки"
                    )
                } else {
                    TransitionResult(current, emptyList())
                }
            }
            // События музыки НЕ создают звук, поэтому окно тишины их не блокирует:
            // они завершают окно (усиление восстанавливается) и обрабатываются
            // обычным музыкальным переходом. Так усиление не может залипнуть на
            // нуле, а пауза/плей/следующий/конец трека/ошибка работают сразу.
            event.priority == EventPriority.MUSIC -> {
                val endActions = endSilentWindowActions("событие музыки $event")
                val result = handleMusicPlaying(
                    CoordinatorState.MusicPlaying(
                        currentTrack = current.savedTrack,
                        isPaused = false
                    ),
                    event
                )
                TransitionResult(result.newState, endActions + result.actions)
            }
            else -> TransitionResult(current, emptyList())
        }
    }

    /**
     * Штатное завершение окна тишины: усиление восстанавливается, плеер
     * возвращается к позиции, с которой трек стал тихим, и продолжает играть.
     * Seek выполняется ДО снятия нулевого усиления. Если трека нет — тишина.
     */
    private fun finishSilentWindow(
        current: CoordinatorState.ChoppyPauseActive,
        warning: String? = null
    ): TransitionResult {
        val actions = mutableListOf<CoordinatorAction>()
        if (warning != null) {
            actions.add(CoordinatorAction.LogMessage(warning))
        }
        actions.add(
            CoordinatorAction.LogMessage(
                "Окно тишины прерывистой музыки завершено: возврат к позиции " +
                    "${current.savedPositionMs} мс, усиление музыки восстанавливается",
                isWarning = false
            )
        )
        val track = current.savedTrack ?: run {
            actions.add(CoordinatorAction.UnmuteMusicPlayback)
            actions.add(CoordinatorAction.StopMusicPlayback)
            return TransitionResult(CoordinatorState.Idle, actions)
        }
        actions.add(CoordinatorAction.SeekTo(current.savedPositionMs))
        actions.add(CoordinatorAction.UnmuteMusicPlayback)
        actions.add(CoordinatorAction.ResumeMusicPlayback)
        return TransitionResult(
            newState = CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false),
            actions = actions
        )
    }

    /**
     * Завершение окна тишины перед событием музыки: усиление восстанавливается,
     * но позиция НЕ откатывается — приоритет у намерения пользователя/системы
     * (пауза, следующий, конец трека). Возвращает действия для сервиса.
     */
    private fun endSilentWindowActions(reason: String): List<CoordinatorAction> = listOf(
        CoordinatorAction.UnmuteMusicPlayback,
        CoordinatorAction.LogMessage(
            "Окно тишины прерывистой музыки закрыто ($reason): усиление музыки восстановлено",
            isWarning = false
        )
    )

    private fun handleMicroSound(
        current: CoordinatorState.MicroSoundActive,
        event: CoordinatorEvent
    ): TransitionResult {
        // Текущий приоритет: MICROSOUND (3) - наивысший
        return when {
            event is CoordinatorEvent.TriggerMicroSound -> {
                // a) Фейк-включение колонки во время реальной последовательности
                // (звук или пауза 10 с до музыки будильника) пропускается.
                // Следующее срабатывание фейка планируется по обычному интервалу
                // в ресивере, поэтому здесь ничего не перепланируется.
                if (SpeakerOnConflictPolicy.fakeMustBeSkipped(
                        currentState = current,
                        incomingIsFake = event.isFakePinnedMicrosound
                    )
                ) {
                    return TransitionResult(
                        newState = current,
                        actions = listOf(
                            CoordinatorAction.LogMessage(
                                "Фейк-включение колонки пропущено: идёт последовательность " +
                                    "«Включение колонки»",
                                isWarning = true
                            )
                        )
                    )
                }

                // «Включение колонки» важнее любого другого микрозвука: полный
                // предзвук обязан прозвучать. Текущий микрозвук прерывается,
                // очередь сбрасывается, чтобы не задерживать будильник.
                // b) Если сейчас играет фейк — реальный звук побеждает и
                // начинает звучать с начала немедленно.
                if (event.isPinnedAlarmPrelude) {
                    val preemptsFake = SpeakerOnConflictPolicy.realPreemptsFake(current)
                    TransitionResult(
                        newState = current.copy(
                            soundUri = event.soundUri,
                            gain = event.gain,
                            isPinnedAlarmPrelude = true,
                            isFakePinnedMicrosound = false,
                            isSpeakerOnSilence = false,
                            displayName = event.displayName,
                            queuedMicroSound = null,
                            startedAtMs = event.timestampMs,
                            maxDurationMs = event.maxDurationMs
                        ),
                        actions = listOf(
                            CoordinatorAction.LogMessage(
                                if (preemptsFake) {
                                    "Реальное «Включение колонки» остановило фейк и " +
                                        "начинается с начала"
                                } else {
                                    "«Включение колонки» прерывает текущий микрозвук"
                                },
                                isWarning = true
                            ),
                            CoordinatorAction.StopMicroSoundTrack,
                            CoordinatorAction.PlayMicroSoundTrack(event.soundUri, event.gain)
                        )
                    )
                } else if (current.isPinnedAlarmPrelude) {
                    // Пока играет предзвук или идёт его 10-секундная пауза,
                    // посторонние микрозвуки не ставятся в очередь.
                    TransitionResult(
                        newState = current,
                        actions = listOf(
                            CoordinatorAction.LogMessage(
                                "Микрозвук ${event.soundUri} пропущен: идёт «Включение колонки»",
                                isWarning = true
                            )
                        )
                    )
                } else if (current.queuedMicroSound == null) {
                    TransitionResult(
                        newState = current.copy(
                            queuedMicroSound = QueuedMicroSound(
                                soundUri = event.soundUri,
                                gain = event.gain,
                                maxDurationMs = event.maxDurationMs,
                                timestampMs = event.timestampMs,
                                isPinnedAlarmPrelude = event.isPinnedAlarmPrelude,
                                isFakePinnedMicrosound = event.isFakePinnedMicrosound,
                                displayName = event.displayName
                            )
                        ),
                        actions = listOf(
                            CoordinatorAction.LogMessage(
                                "Микрозвук добавлен в очередь (1 ожидающий)",
                                isWarning = false
                            )
                        )
                    )
                } else {
                    TransitionResult(
                        newState = current,
                        actions = listOf(
                            CoordinatorAction.LogMessage(
                                "Очередь микрозвуков заполнена (макс. 1). Микрозвук ${event.soundUri} пропущен",
                                isWarning = true
                            )
                        )
                    )
                }
            }
            event is CoordinatorEvent.SpeakerOnSilenceStarted -> {
                // Только отображаемое состояние: идёт 10-секундная тишина после
                // «Включения колонки». Состояние остаётся MicroSoundActive.
                if (!current.isPinnedAlarmPrelude || current.isSpeakerOnSilence) {
                    TransitionResult(current, emptyList())
                } else {
                    TransitionResult(current.copy(isSpeakerOnSilence = true), emptyList())
                }
            }
            event is CoordinatorEvent.MicroSoundCompleted || event is CoordinatorEvent.MicroSoundError -> {
                val warning = if (event is CoordinatorEvent.MicroSoundError) {
                    "Ошибка воспроизведения микрозвука: ${event.error?.message}"
                } else null
                completeMicroSoundOrPlayQueued(current, event, warning)
            }
            event is CoordinatorEvent.ChoppyPauseCompleted -> {
                // Если прерывистая пауза истекла во время воспроизведения микрозвука
                TransitionResult(
                    newState = current.copy(wasInChoppyPause = false, choppyPauseRemainingMs = 0L),
                    actions = emptyList()
                )
            }
            event is CoordinatorEvent.WatchdogTimeoutTick -> {
                if (event.currentTimestampMs - current.startedAtMs >= current.maxDurationMs) {
                    completeMicroSoundOrPlayQueued(
                        current = current,
                        event = event,
                        warning = "Сработала таймаут-страховка воспроизведения микрозвука",
                        isTimeout = true
                    )
                } else {
                    TransitionResult(current, emptyList())
                }
            }
            event is CoordinatorEvent.UserStop -> {
                TransitionResult(
                    newState = CoordinatorState.Idle,
                    actions = listOf(
                        CoordinatorAction.StopMicroSoundTrack,
                        CoordinatorAction.StopMusicPlayback
                    )
                )
            }
            // Любое нижестоящее событие отбрасывается
            event.priority.level < EventPriority.MICROSOUND.level -> {
                TransitionResult(
                    newState = current,
                    actions = listOf(
                        CoordinatorAction.LogMessage(
                            "Отклонено событие $event: воспроизводится микрозвук"
                        )
                    )
                )
            }
            else -> TransitionResult(current, emptyList())
        }
    }

    private fun completeMicroSoundOrPlayQueued(
        current: CoordinatorState.MicroSoundActive,
        event: CoordinatorEvent,
        warning: String? = null,
        isTimeout: Boolean = false
    ): TransitionResult {
        val actions = mutableListOf<CoordinatorAction>()
        if (warning != null) {
            actions.add(CoordinatorAction.LogMessage(warning))
        }
        if (isTimeout) {
            actions.add(CoordinatorAction.StopMicroSoundTrack)
        }

        val queued = current.queuedMicroSound
        // Этап 4: ожидающий микрозвук тоже не начинает играть в «Часы тишины»
        // (тишина могла начаться, пока звучал предыдущий звук). Он отбрасывается,
        // музыка возвращается обычным путём — звук не «залипает» в очереди.
        if (queued != null && isWithinQuietHours()) {
            actions.add(
                CoordinatorAction.LogMessage(
                    "Ожидающий микрозвук пропущен: сейчас «Часы тишины»",
                    isWarning = true
                )
            )
            return resumeMusicOrIdle(current.savedTrack, current.resumeMusicAfter, actions)
        }
        if (queued != null) {
            // Воспроизводим ожидающий из очереди
            actions.add(CoordinatorAction.PlayMicroSoundTrack(queued.soundUri, queued.gain))
            val now = queued.timestampMs
            val elapsed = (now - current.startedAtMs).coerceAtLeast(0L)
            val updatedRemainingPause = (current.choppyPauseRemainingMs - elapsed).coerceAtLeast(0L)
            return TransitionResult(
                newState = CoordinatorState.MicroSoundActive(
                    soundUri = queued.soundUri,
                    gain = queued.gain,
                    savedTrack = current.savedTrack,
                    resumeMusicAfter = current.resumeMusicAfter,
                    wasInChoppyPause = current.wasInChoppyPause && updatedRemainingPause > 0,
                    choppyPauseRemainingMs = updatedRemainingPause,
                    queuedMicroSound = null,
                    isPinnedAlarmPrelude = queued.isPinnedAlarmPrelude,
                    isFakePinnedMicrosound = queued.isFakePinnedMicrosound,
                    displayName = queued.displayName,
                    startedAtMs = now,
                    maxDurationMs = queued.maxDurationMs
                ),
                actions = actions
            )
        }

        // Очередь пуста: если микрозвук играл во время прерывистой паузы и пауза еще не закончилась.
        // С этапа 4 этот путь недостижим (окно тишины микрозвуком отменяется,
        // wasInChoppyPause всегда false); оставлен как защита от старых состояний.
        if (current.wasInChoppyPause && current.choppyPauseRemainingMs > 0) {
            val eventTime = when (event) {
                is CoordinatorEvent.MicroSoundCompleted -> if (event.timestampMs > 0L) event.timestampMs else null
                is CoordinatorEvent.WatchdogTimeoutTick -> event.currentTimestampMs
                else -> null
            }
            val elapsed = if (eventTime != null && eventTime >= current.startedAtMs) {
                (eventTime - current.startedAtMs).coerceAtLeast(0L)
            } else if (current.startedAtMs > 1_000_000_000L) {
                (System.currentTimeMillis() - current.startedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val remaining = (current.choppyPauseRemainingMs - elapsed).coerceAtLeast(0L)
            if (remaining > 0) {
                val resumeTime = eventTime ?: System.currentTimeMillis()
                actions.add(CoordinatorAction.StartChoppyPauseTimer(remaining))
                return TransitionResult(
                    newState = CoordinatorState.ChoppyPauseActive(
                        savedTrack = current.savedTrack,
                        resumePlaybackAfter = current.resumeMusicAfter,
                        startedAtMs = resumeTime,
                        maxDurationMs = remaining
                    ),
                    actions = actions
                )
            }
        }

        // Возврат к музыке (с той же позиции без нарастания) или в покой
        return resumeMusicOrIdle(current.savedTrack, current.resumeMusicAfter, actions)
    }

    private fun resumeMusicOrIdle(
        savedTrack: TrackEntity?,
        resume: Boolean,
        actions: MutableList<CoordinatorAction>
    ): TransitionResult {
        return if (savedTrack != null) {
            if (resume) {
                actions.add(CoordinatorAction.ResumeMusicPlayback)
                TransitionResult(
                    newState = CoordinatorState.MusicPlaying(currentTrack = savedTrack, isPaused = false),
                    actions = actions
                )
            } else {
                TransitionResult(
                    newState = CoordinatorState.MusicPlaying(currentTrack = savedTrack, isPaused = true),
                    actions = actions
                )
            }
        } else {
            actions.add(CoordinatorAction.StopMusicPlayback)
            TransitionResult(
                newState = CoordinatorState.Idle,
                actions = actions
            )
        }
    }

    companion object {
        /** Окно защиты от двойного нажатия stopCurrent (двойной тап/свайп + кнопка). */
        const val STOP_CURRENT_DUPLICATE_WINDOW_MS = 500L

        /**
         * Запас сверх длительности окна тишины перед таймаут-страховкой:
         * штатное завершение делает автомат сервиса, watchdog — только сбой.
         */
        const val CHOPPY_WATCHDOG_GRACE_MS = 5_000L
    }
}
