package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity

/** Состояние воспроизведения, которое показывает единственное уведомление. */
enum class NotificationPlaybackStatus {
    /** Звук слышен прямо сейчас. */
    PLAYING,

    /**
     * Звука не слышно: ручная пауза музыки/сеанса или окно тишины прерывистой
     * музыки. Уведомление остаётся с кнопкой «Играть», а причину и время
     * возврата уточняют [PlaybackNotificationState.pauseReason] и
     * [PlaybackNotificationState.resumeAtMs].
     */
    PAUSED
}

/** Почему уведомление показывает паузу: от этого зависят заголовок/текст. */
enum class NotificationPauseReason {
    /** Пользователь поставил музыку на паузу сам — текст остаётся обычным. */
    USER,

    /**
     * Окно тишины прерывистой музыки: звук вернётся САМ, поэтому заголовок
     * объясняет причину, а вместо обычного подзаголовка показывается
     * обратный отсчёт до [PlaybackNotificationState.resumeAtMs].
     */
    CHOPPY
}

/** Кнопки единственного уведомления; новых кнопок, кроме этих, не бывает. */
enum class NotificationButton {
    /** Play/pause: идёт через координатор (`UserPause`/`UserResume`). */
    PLAY_PAUSE,

    /** Существующая «Остановить» (`stopCurrent()`). */
    STOP
}

/**
 * Готовое содержимое единственного уведомления воспроизведения.
 *
 * Модель намеренно отделена от Android: `PlaybackNotification` в сервисе только
 * форматирует строки из `strings.xml`, а ВСЁ решение «что показать» принимает
 * ОДНА чистая функция [PlaybackNotificationContent.from] по состоянию
 * координатора. С этапа 2 здесь же живёт «прерывистая пауза»: причина паузы
 * [pauseReason] и точный момент возврата музыки [resumeAtMs].
 *
 * @property kind вид звука: даёт подпись источника («Музыка», «Микрозвук», «Сеанс»);
 * @property trackTitle название трека/файла (может отсутствовать);
 * @property status играет или на паузе;
 * @property buttons кнопки в порядке показа;
 * @property playbackMode режим музыки (перемешивание/повтор) или `null`, если
 * режим обычный, звук не музыкальный или это сигнал сеанса: подзаголовок
 * показывает РЕЖИМ вместо общего слова «Музыка»;
 * @property pauseReason причина паузы (`null` — звук играет или это не пауза);
 * @property resumeAtMs wall-clock момент (мс), когда прерывистая пауза
 * закончится и музыка вернётся; `null` — обратного отсчёта нет.
 */
data class PlaybackNotificationState(
    val kind: CurrentSoundKind,
    val trackTitle: String?,
    val status: NotificationPlaybackStatus,
    val buttons: List<NotificationButton>,
    val playbackMode: PlaybackMode? = null,
    val pauseReason: NotificationPauseReason? = null,
    val resumeAtMs: Long? = null
) {
    /** true — звук слышен (кнопка показывает «Пауза»). */
    val isPlaying: Boolean get() = status == NotificationPlaybackStatus.PLAYING

    /** true — уведомление умеет ставить звук на паузу и продолжать его. */
    val canPlayPause: Boolean get() = NotificationButton.PLAY_PAUSE in buttons

    /** true — в уведомлении есть существующая кнопка «Остановить». */
    val canStop: Boolean get() = NotificationButton.STOP in buttons

    /** Название без пустых значений: пустая строка равносильна отсутствию. */
    val titleOrNull: String? get() = trackTitle?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Единственный маппер «состояние координатора -> содержимое уведомления».
 *
 * Возвращает `null`, когда уведомления быть НЕ ДОЛЖНО: покой, предпрослушивание
 * мелодии и 10-секундная тишина после «Включения колонки». Музыка НА ПАУЗЕ
 * даёт непустое состояние: иначе уведомление исчезало бы вместе с кнопкой
 * «Играть» и продолжить трек из шторки было бы невозможно.
 */
object PlaybackNotificationContent {

    fun from(
        state: CoordinatorState,
        alarmMusicActive: Boolean = false,
        forceNull: Boolean = false,
        playbackMode: PlaybackMode = PlaybackMode.OFF
    ): PlaybackNotificationState? {
        // Ошибка/окончание музыки гасят уведомление так же, как currentSound,
        // но не трогают реально звучащий микрозвук.
        if (forceNull && state !is CoordinatorState.MicroSoundActive) return null
        return when (state) {
            is CoordinatorState.Idle -> null
            // Предпрослушивание — не звук приложения: уведомления у него нет.
            is CoordinatorState.Previewing -> null
            is CoordinatorState.MusicPlaying -> music(
                track = state.currentTrack,
                isPaused = state.isPaused,
                isAlarm = alarmMusicActive,
                playbackMode = playbackMode
            )
            // Окно тишины прерывистой музыки: плеер продолжает играть, но звука
            // НЕТ, поэтому уведомление честно показывает паузу, объясняет её
            // причину и отдаёт точное время возврата музыки (обратный отсчёт).
            // Кнопка при этом — «Играть»: нажатие возвращает звук немедленно.
            is CoordinatorState.ChoppyPauseActive -> music(
                track = state.savedTrack,
                isPaused = true,
                isAlarm = alarmMusicActive,
                playbackMode = playbackMode,
                pauseReason = if (state.resumePlaybackAfter) {
                    NotificationPauseReason.CHOPPY
                } else {
                    NotificationPauseReason.USER
                },
                resumeAtMs = state.resumeAtMs.takeIf { state.resumePlaybackAfter }
            )
            is CoordinatorState.MicroSoundActive -> microsound(state)
        }
    }

    private fun music(
        track: TrackEntity?,
        isPaused: Boolean,
        isAlarm: Boolean,
        playbackMode: PlaybackMode,
        pauseReason: NotificationPauseReason? = null,
        resumeAtMs: Long? = null
    ): PlaybackNotificationState = PlaybackNotificationState(
        kind = if (isAlarm) CurrentSoundKind.ALARM else CurrentSoundKind.MUSIC,
        trackTitle = track?.title,
        status = if (isPaused) {
            NotificationPlaybackStatus.PAUSED
        } else {
            NotificationPlaybackStatus.PLAYING
        },
        buttons = listOf(NotificationButton.PLAY_PAUSE, NotificationButton.STOP),
        // Подпись режима не нужна, когда заголовок объясняет причину паузы.
        playbackMode = if (pauseReason == NotificationPauseReason.CHOPPY) {
            null
        } else {
            playbackMode.takeIf { !isAlarm && it != PlaybackMode.OFF }
        },
        // Ручная пауза тоже получает причину USER: заголовок/текст от неё не
        // меняются, но состояние остаётся явным для следующего этапа/тестов.
        pauseReason = if (isPaused) pauseReason ?: NotificationPauseReason.USER else null,
        resumeAtMs = resumeAtMs
    )

    private fun microsound(state: CoordinatorState.MicroSoundActive): PlaybackNotificationState? {
        // 10-секундная тишина перед сигналом: звука нет, уведомления тоже.
        if (state.isSpeakerOnSilence) return null
        return PlaybackNotificationState(
            kind = when {
                state.isPinnedAlarmPrelude -> CurrentSoundKind.SPEAKER_ON
                state.isFakePinnedMicrosound -> CurrentSoundKind.FAKE_SPEAKER_ON
                else -> CurrentSoundKind.MICROSOUND
            },
            trackTitle = state.displayName,
            status = NotificationPlaybackStatus.PLAYING,
            // Пауза микрозвука не предусмотрена: событие музыки координатор
            // отклонит по приоритету, поэтому кнопки play/pause у него нет.
            buttons = listOf(NotificationButton.STOP)
        )
    }
}
