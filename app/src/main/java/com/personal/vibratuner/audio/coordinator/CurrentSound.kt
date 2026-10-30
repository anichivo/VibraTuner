package com.personal.vibratuner.audio.coordinator

/**
 * Вид ЕДИНСТВЕННОГО звука, который слышен прямо сейчас.
 *
 * Порядок перечисления не задаёт приоритет: приоритет по-прежнему определяется
 * состояниями [CoordinatorState] (микрозвук > прерывистая пауза > музыка).
 */
enum class CurrentSoundKind {
    /** Обычная музыка (ручной запуск, продолжение, следующий трек). */
    MUSIC,

    /** Обычный микрозвук по интервалу. */
    MICROSOUND,

    /** Реальное «Включение колонки» перед будильником (сам звук, без 10-секундной паузы). */
    SPEAKER_ON,

    /** Фейк-включение колонки (тот же звук по интервалу, без будильника). */
    FAKE_SPEAKER_ON,

    /** Музыка, запущенная будильником (сигнал будильника). */
    ALARM
}

/**
 * Снимок текущего звука для уведомления/UI.
 *
 * @property kind вид звука;
 * @property displayName уже известное приложению имя: название трека для музыки
 * и будильника, имя файла/микрозвука для микрозвуков и «Включения колонки».
 * Может быть `null`, если имя ещё не передано; это не влияет на playback.
 */
data class CurrentSound(
    val kind: CurrentSoundKind,
    val displayName: String? = null
)

/**
 * Действие остановки, соответствующее виду текущего звука.
 * Чистая функция «вид -> действие»: переходы выполняет координатор.
 */
enum class StopCurrentAction {
    /** Завершить микрозвук так же, как при естественном окончании. */
    STOP_MICROSOUND,

    /** Завершить звук «Включение колонки» и перейти к фиксированной паузе 10 с. */
    FINISH_SPEAKER_ON,

    /** Поставить музыку на паузу, сохранив очередь и позицию. */
    PAUSE_MUSIC,

    /** Завершить срабатывание будильника (без отключения будущих срабатываний). */
    STOP_ALARM
}

object StopCurrentPolicy {
    fun actionFor(kind: CurrentSoundKind): StopCurrentAction = when (kind) {
        CurrentSoundKind.MICROSOUND,
        CurrentSoundKind.FAKE_SPEAKER_ON -> StopCurrentAction.STOP_MICROSOUND

        CurrentSoundKind.SPEAKER_ON -> StopCurrentAction.FINISH_SPEAKER_ON
        CurrentSoundKind.MUSIC -> StopCurrentAction.PAUSE_MUSIC
        CurrentSoundKind.ALARM -> StopCurrentAction.STOP_ALARM
    }
}

/**
 * Чистое вычисление текущего звука из состояния координатора.
 *
 * @param alarmMusicActive музыка запущена будильником (флаг ведёт координатор,
 * он переживает паузу музыки на время микрозвука/окна тишины).
 *
 * Возвращает `null`, когда ничего не слышно: в покое, при музыке на паузе и во
 * время 10-секундной тишины после «Включения колонки». Окно тишины прерывистой
 * музыки (этап 4) НЕ даёт `null`: плеер продолжает играть, поэтому уведомление
 * и foreground-сервис остаются живыми (именно их потеря и «замораживала»
 * возобновление); при этом `AppAudibility` для окна равен false — системная
 * громкость не поднимается. Координатор опрашивает функцию после КАЖДОГО
 * перехода, поэтому состояние не может «залипнуть» на устаревшем значении.
 */
object CurrentSoundResolver {
    fun resolve(
        state: CoordinatorState,
        alarmMusicActive: Boolean = false
    ): CurrentSound? = when (state) {
        is CoordinatorState.Idle -> null
        // Предпрослушивание трека в окне выбора мелодии — не «звук приложения»:
        // уведомления, foreground и мини-плеера у него нет (Stage 2).
        is CoordinatorState.Previewing -> null
        is CoordinatorState.ChoppyPauseActive -> state.savedTrack?.let { track ->
            CurrentSound(
                kind = if (alarmMusicActive) {
                    CurrentSoundKind.ALARM
                } else {
                    CurrentSoundKind.MUSIC
                },
                displayName = track.title
            )
        }
        is CoordinatorState.MusicPlaying -> {
            if (state.isPaused) {
                null
            } else {
                CurrentSound(
                    kind = if (alarmMusicActive) {
                        CurrentSoundKind.ALARM
                    } else {
                        CurrentSoundKind.MUSIC
                    },
                    displayName = state.currentTrack?.title
                )
            }
        }
        is CoordinatorState.MicroSoundActive -> when {
            state.isPinnedAlarmPrelude && state.isSpeakerOnSilence -> null
            state.isPinnedAlarmPrelude -> CurrentSound(
                kind = CurrentSoundKind.SPEAKER_ON,
                displayName = state.displayName
            )
            state.isFakePinnedMicrosound -> CurrentSound(
                kind = CurrentSoundKind.FAKE_SPEAKER_ON,
                displayName = state.displayName
            )
            else -> CurrentSound(
                kind = CurrentSoundKind.MICROSOUND,
                displayName = state.displayName
            )
        }
    }
}
