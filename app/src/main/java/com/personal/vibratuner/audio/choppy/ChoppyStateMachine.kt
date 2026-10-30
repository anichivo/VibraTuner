package com.personal.vibratuner.audio.choppy

/**
 * Валидатор параметров прерывистой музыки.
 * Единственная проверка — техническая: интервал X > 0 и пауза Y > 0.
 * Соотношение X и Y НЕ ограничено: цикл «играть X, потом пауза Y» корректен при
 * любых значениях, в том числе при Y >= X (часть 2 фидбека).
 */
object ChoppyValidator {
    fun validate(playMinutes: Int, pauseMinutes: Int): Boolean {
        return playMinutes > 0 && pauseMinutes > 0
    }
}

/**
 * Состояния автомата прерывистой музыки.
 */
sealed interface ChoppyState {
    object Disabled : ChoppyState
    data class Playing(val elapsedPlayMs: Long) : ChoppyState
    data class PausedByUser(val elapsedPlayMs: Long) : ChoppyState
    data class InChoppyPause(val elapsedPauseMs: Long) : ChoppyState
}

/**
 * Входные события автомата прерывистой музыки.
 */
sealed interface ChoppyEvent {
    data class Configure(
        val enabled: Boolean,
        val playIntervalMs: Long,
        val pauseIntervalMs: Long
    ) : ChoppyEvent

    object PlaybackStarted : ChoppyEvent
    object PlaybackPaused : ChoppyEvent
    object PlaybackStopped : ChoppyEvent
    data class TimeTick(val deltaMs: Long) : ChoppyEvent
}

/**
 * Действия, инициируемые автоматом прерывистой музыки.
 */
sealed interface ChoppyAction {
    data class TriggerPause(val durationMs: Long) : ChoppyAction
    object ResumePlayback : ChoppyAction
}

/**
 * Детерминированный конечный автомат (State Machine) прерывистой музыки.
 *
 * Инварианты:
 * 1. Таймер воспроизведения тикает ТОЛЬКО во время активного воспроизведения музыки.
 * 2. При ручной паузе таймер замораживается и сохраняет накопленное время.
 * 3. По достижении интервала X (playIntervalMs) инициируется ОКНО ТИШИНЫ
 *    длительностью Y (плеер при этом не ставится на паузу — сервис глушит
 *    усиление, см. `ChoppySilentWindow`).
 * 4. По истечении Y (pauseIntervalMs) звук возвращается с той же позиции, а таймер X сбрасывается.
 */
class ChoppyStateMachine(
    private var playIntervalMs: Long = 6 * 60 * 1000L,
    private var pauseIntervalMs: Long = 1 * 60 * 1000L,
    private var isEnabled: Boolean = false
) {
    var state: ChoppyState = if (isEnabled) ChoppyState.PausedByUser(0L) else ChoppyState.Disabled
        private set

    fun processEvent(event: ChoppyEvent): List<ChoppyAction> {
        val (newState, actions) = transition(state, event)
        state = newState
        return actions
    }

    private fun transition(
        current: ChoppyState,
        event: ChoppyEvent
    ): Pair<ChoppyState, List<ChoppyAction>> {
        return when (event) {
            is ChoppyEvent.Configure -> {
                isEnabled = event.enabled
                playIntervalMs = event.playIntervalMs
                pauseIntervalMs = event.pauseIntervalMs
                if (!isEnabled) {
                    ChoppyState.Disabled to emptyList()
                } else if (current is ChoppyState.Disabled) {
                    ChoppyState.PausedByUser(0L) to emptyList()
                } else {
                    current to emptyList()
                }
            }
            is ChoppyEvent.PlaybackStopped -> {
                if (isEnabled) ChoppyState.PausedByUser(0L) to emptyList() else ChoppyState.Disabled to emptyList()
            }
            is ChoppyEvent.PlaybackPaused -> {
                when (current) {
                    is ChoppyState.Playing -> ChoppyState.PausedByUser(current.elapsedPlayMs) to emptyList()
                    else -> current to emptyList()
                }
            }
            is ChoppyEvent.PlaybackStarted -> {
                when (current) {
                    is ChoppyState.PausedByUser -> ChoppyState.Playing(current.elapsedPlayMs) to emptyList()
                    is ChoppyState.Disabled -> if (isEnabled) ChoppyState.Playing(0L) to emptyList() else ChoppyState.Disabled to emptyList()
                    else -> current to emptyList()
                }
            }
            is ChoppyEvent.TimeTick -> {
                if (!isEnabled) return ChoppyState.Disabled to emptyList()
                when (current) {
                    is ChoppyState.Playing -> {
                        val newPlay = current.elapsedPlayMs + event.deltaMs
                        if (newPlay >= playIntervalMs) {
                            ChoppyState.InChoppyPause(0L) to listOf(ChoppyAction.TriggerPause(pauseIntervalMs))
                        } else {
                            ChoppyState.Playing(newPlay) to emptyList()
                        }
                    }
                    is ChoppyState.InChoppyPause -> {
                        val newPause = current.elapsedPauseMs + event.deltaMs
                        if (newPause >= pauseIntervalMs) {
                            ChoppyState.Playing(0L) to listOf(ChoppyAction.ResumePlayback)
                        } else {
                            ChoppyState.InChoppyPause(newPause) to emptyList()
                        }
                    }
                    is ChoppyState.PausedByUser -> {
                        // Таймер идёт ТОЛЬКО во время воспроизведения
                        current to emptyList()
                    }
                    is ChoppyState.Disabled -> ChoppyState.Disabled to emptyList()
                }
            }
        }
    }
}
