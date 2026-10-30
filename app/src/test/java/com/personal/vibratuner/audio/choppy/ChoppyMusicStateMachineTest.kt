package com.personal.vibratuner.audio.choppy

import com.personal.vibratuner.audio.coordinator.CoordinatorAction
import com.personal.vibratuner.audio.coordinator.CoordinatorEvent
import com.personal.vibratuner.audio.coordinator.CoordinatorState
import com.personal.vibratuner.audio.coordinator.CurrentSound
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.PlaybackCoordinator
import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoppyMusicStateMachineTest {

    // --- 1. Валидация: только техническая проверка X > 0 и Y > 0 ---

    @Test
    fun validation_validIntervals_returnsTrue() {
        assertTrue(ChoppyValidator.validate(playMinutes = 6, pauseMinutes = 1))
        assertTrue(ChoppyValidator.validate(playMinutes = 10, pauseMinutes = 5))
        assertTrue(ChoppyValidator.validate(playMinutes = 2, pauseMinutes = 1))
        // Часть 2 фидбека: ограничение «пауза короче интервала» снято.
        assertTrue(ChoppyValidator.validate(playMinutes = 2, pauseMinutes = 2))
        assertTrue(ChoppyValidator.validate(playMinutes = 3, pauseMinutes = 5))
        assertTrue(ChoppyValidator.validate(playMinutes = 1, pauseMinutes = 999))
        assertTrue(ChoppyValidator.validate(playMinutes = 999, pauseMinutes = 1))
    }

    @Test
    fun validation_nonPositiveIntervals_returnsFalse() {
        // Технически необходимый минимум: оба значения больше нуля.
        assertFalse(ChoppyValidator.validate(playMinutes = 0, pauseMinutes = 0))
        assertFalse(ChoppyValidator.validate(playMinutes = 0, pauseMinutes = 5))
        assertFalse(ChoppyValidator.validate(playMinutes = 5, pauseMinutes = 0))
        assertFalse(ChoppyValidator.validate(playMinutes = -1, pauseMinutes = 1))
        assertFalse(ChoppyValidator.validate(playMinutes = 5, pauseMinutes = -1))
    }

    // --- 2. Таймер тикает ТОЛЬКО во время воспроизведения ---

    @Test
    fun timer_runsOnlyDuringActivePlayback() {
        val playMs = 60_000L
        val pauseMs = 10_000L
        val machine = ChoppyStateMachine(playIntervalMs = playMs, pauseIntervalMs = pauseMs, isEnabled = true)

        // Изначально воспроизведение не начато (PausedByUser)
        assertTrue(machine.state is ChoppyState.PausedByUser)

        // Тикаем 5 секунд в режиме паузы: таймер не накапливается
        val actionsWhilePaused = machine.processEvent(ChoppyEvent.TimeTick(5000L))
        assertTrue(actionsWhilePaused.isEmpty())
        val stateWhilePaused = machine.state as ChoppyState.PausedByUser
        assertEquals(0L, stateWhilePaused.elapsedPlayMs)

        // Начинаем воспроизведение
        machine.processEvent(ChoppyEvent.PlaybackStarted)
        assertTrue(machine.state is ChoppyState.Playing)

        // Тикаем 20 секунд: время накапливается
        machine.processEvent(ChoppyEvent.TimeTick(20_000L))
        assertEquals(20_000L, (machine.state as ChoppyState.Playing).elapsedPlayMs)

        // Пользователь ставит на паузу
        machine.processEvent(ChoppyEvent.PlaybackPaused)
        assertTrue(machine.state is ChoppyState.PausedByUser)
        assertEquals(20_000L, (machine.state as ChoppyState.PausedByUser).elapsedPlayMs)

        // Во время паузы пользователя проходит 10 секунд: таймер заморожен
        machine.processEvent(ChoppyEvent.TimeTick(10_000L))
        assertEquals(20_000L, (machine.state as ChoppyState.PausedByUser).elapsedPlayMs)

        // Возобновляем воспроизведение: время продолжается с 20 секунд
        machine.processEvent(ChoppyEvent.PlaybackStarted)
        assertEquals(20_000L, (machine.state as ChoppyState.Playing).elapsedPlayMs)

        machine.processEvent(ChoppyEvent.TimeTick(15_000L))
        assertEquals(35_000L, (machine.state as ChoppyState.Playing).elapsedPlayMs)
    }

    // --- 3. Срабатывание паузы X -> Y и последующее возобновление ---

    @Test
    fun reachingPlayInterval_triggersPause_andThenResumes() {
        val playMs = 60_000L
        val pauseMs = 10_000L
        val machine = ChoppyStateMachine(playIntervalMs = playMs, pauseIntervalMs = pauseMs, isEnabled = true)

        machine.processEvent(ChoppyEvent.PlaybackStarted)

        // Тикаем до 50 секунд: пауза еще не наступила
        val actions1 = machine.processEvent(ChoppyEvent.TimeTick(50_000L))
        assertTrue(actions1.isEmpty())

        // Еще 10 секунд: достигаем 60 секунд -> инициируется TriggerPause
        val actions2 = machine.processEvent(ChoppyEvent.TimeTick(10_000L))
        assertEquals(1, actions2.size)
        assertTrue(actions2[0] is ChoppyAction.TriggerPause)
        assertEquals(pauseMs, (actions2[0] as ChoppyAction.TriggerPause).durationMs)
        assertTrue(machine.state is ChoppyState.InChoppyPause)

        // Во время паузы проходит 5 секунд
        val actionsDuringPause = machine.processEvent(ChoppyEvent.TimeTick(5_000L))
        assertTrue(actionsDuringPause.isEmpty())
        assertTrue(machine.state is ChoppyState.InChoppyPause)

        // Проходит еще 5 секунд: истекает длительность паузы 10 сек -> ResumePlayback
        val actionsResume = machine.processEvent(ChoppyEvent.TimeTick(5_000L))
        assertEquals(1, actionsResume.size)
        assertTrue(actionsResume[0] is ChoppyAction.ResumePlayback)
        assertTrue(machine.state is ChoppyState.Playing)
        // Таймер X сбросился в 0 для следующего цикла
        assertEquals(0L, (machine.state as ChoppyState.Playing).elapsedPlayMs)
    }

    // --- 3.1. Часть 2 фидбека: пауза ДЛИННЕЕ интервала и пауза РАВНА интервалу ---

    @Test
    fun pauseLongerThanPlayInterval_stillCycles() {
        val playMs = 10_000L
        val pauseMs = 30_000L
        val machine = ChoppyStateMachine(playIntervalMs = playMs, pauseIntervalMs = pauseMs, isEnabled = true)

        machine.processEvent(ChoppyEvent.PlaybackStarted)

        // X истёк -> пауза ровно на Y (а не на X)
        val trigger = machine.processEvent(ChoppyEvent.TimeTick(playMs))
        assertEquals(1, trigger.size)
        assertTrue(trigger[0] is ChoppyAction.TriggerPause)
        assertEquals(pauseMs, (trigger[0] as ChoppyAction.TriggerPause).durationMs)
        assertTrue(machine.state is ChoppyState.InChoppyPause)

        // Пауза не заканчивается раньше Y
        assertTrue(machine.processEvent(ChoppyEvent.TimeTick(pauseMs - 1)).isEmpty())
        assertTrue(machine.state is ChoppyState.InChoppyPause)

        // Последний тик -> возобновление
        val resume = machine.processEvent(ChoppyEvent.TimeTick(1L))
        assertEquals(1, resume.size)
        assertTrue(resume[0] is ChoppyAction.ResumePlayback)
        assertTrue(machine.state is ChoppyState.Playing)

        // Второй цикл повторяется: снова X, затем Y
        val secondTrigger = machine.processEvent(ChoppyEvent.TimeTick(playMs))
        assertEquals(1, secondTrigger.size)
        assertTrue(secondTrigger[0] is ChoppyAction.TriggerPause)
    }

    @Test
    fun pauseEqualToPlayInterval_stillCycles() {
        val playMs = 5_000L
        val pauseMs = 5_000L
        val machine = ChoppyStateMachine(playIntervalMs = playMs, pauseIntervalMs = pauseMs, isEnabled = true)

        machine.processEvent(ChoppyEvent.PlaybackStarted)
        val trigger = machine.processEvent(ChoppyEvent.TimeTick(playMs))
        assertTrue(trigger[0] is ChoppyAction.TriggerPause)
        assertTrue(machine.state is ChoppyState.InChoppyPause)

        val resume = machine.processEvent(ChoppyEvent.TimeTick(pauseMs))
        assertTrue(resume[0] is ChoppyAction.ResumePlayback)
        assertTrue(machine.state is ChoppyState.Playing)
    }

    // --- 4. Интеграция с PlaybackCoordinator: окно тишины вместо паузы плеера ---

    @Test
    fun silentWindowInCoordinator_mutesGainWithoutPausingPlayer() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")

        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))

        val startActions = coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 42_000L
            )
        )

        val window = coordinator.state as CoordinatorState.ChoppyPauseActive
        assertEquals(testTrack, window.savedTrack)
        assertEquals("Позиция старта окна запомнена", 42_000L, window.savedPositionMs)
        assertTrue(
            "Плеер НЕ ставится на паузу: только усиление 0",
            startActions.any {
                it is CoordinatorAction.MuteMusicForSilentWindow &&
                    it.positionMs == 42_000L && it.durationMs == 60_000L
            }
        )
        assertTrue(
            "Паузы плеера при старте окна нет",
            startActions.none { it is CoordinatorAction.PauseMusicPlayback }
        )
        assertEquals(
            "Уведомление продолжает показывать музыку (сервис остаётся foreground)",
            CurrentSound(CurrentSoundKind.MUSIC, "Тестовый трек"),
            coordinator.currentSound.value
        )
        assertFalse("При этом приложение не слышно", coordinator.appIsAudible.value)
    }

    @Test
    fun silentWindowCompleted_restoresGainAndSeeksBackToSavedPosition() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 42_000L
            )
        )

        val endActions = coordinator.processEvent(CoordinatorEvent.ChoppyPauseCompleted)

        val playing = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(testTrack, playing.currentTrack)
        assertFalse("Музыка продолжает играть", playing.isPaused)
        assertTrue(
            "Усиление восстанавливается",
            endActions.any { it is CoordinatorAction.UnmuteMusicPlayback }
        )
        assertTrue(
            "Возврат к позиции, на которой трек стал тихим",
            endActions.any { it is CoordinatorAction.SeekTo && it.positionMs == 42_000L }
        )
        assertTrue(
            "Музыка снова звучит",
            endActions.any { it is CoordinatorAction.ResumeMusicPlayback }
        )
        assertEquals(
            CurrentSound(CurrentSoundKind.MUSIC, "Тестовый трек"),
            coordinator.currentSound.value
        )
        assertTrue(coordinator.appIsAudible.value)
    }

    @Test
    fun microsoundDuringSilentWindow_winsAndWindowIsCancelledWithGainRestored() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 10_000L
            )
        )

        val microActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/chime.wav",
                gain = 0.8f,
                maxDurationMs = 10_000L,
                timestampMs = 11_000L
            )
        )

        val microState = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals(testTrack, microState.savedTrack)
        assertTrue("После микрозвука музыка должна снова звучать", microState.resumeMusicAfter)
        assertFalse("Окно тишины отменено, остаток не переносится", microState.wasInChoppyPause)
        assertEquals(0L, microState.choppyPauseRemainingMs)
        assertTrue(
            "Перед микрозвуком музыка ставится на паузу",
            microActions.any { it is CoordinatorAction.PauseMusicPlayback }
        )
        assertTrue(
            "Нулевое усиление окна снимается",
            microActions.any { it is CoordinatorAction.UnmuteMusicPlayback }
        )
        assertTrue(
            "Микрозвук играет по приоритету",
            microActions.any { it is CoordinatorAction.PlayMicroSoundTrack }
        )

        val afterMicrosound = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(21_000L))
        val playing = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(testTrack, playing.currentTrack)
        assertFalse("Музыка возобновляется сразу после микрозвука", playing.isPaused)
        assertTrue(afterMicrosound.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertFalse(
            "В окно тишины после микрозвука музыка не возвращается",
            coordinator.state is CoordinatorState.ChoppyPauseActive
        )
    }

    @Test
    fun trackEndedDuringSilentWindow_doesNotLeaveMusicSilentForever() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 600_000L,
                timestampMs = 1_000L,
                positionMs = 5_000L
            )
        )

        val actions = coordinator.processEvent(CoordinatorEvent.TrackEnded)

        assertTrue(
            "Конец трека закрывает окно и запрашивает следующий трек",
            actions.any { it is CoordinatorAction.UnmuteMusicPlayback } &&
                actions.any { it is CoordinatorAction.PlayNextTrack }
        )
        assertFalse(coordinator.state is CoordinatorState.ChoppyPauseActive)
    }

    @Test
    fun userPauseDuringSilentWindow_restoresGainAndPausesMusic() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 7_000L
            )
        )

        val actions = coordinator.processEvent(CoordinatorEvent.UserPause)

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertTrue("Пользовательская пауза применяется сразу", state.isPaused)
        assertTrue(actions.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(actions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertFalse(coordinator.state is CoordinatorState.ChoppyPauseActive)
    }

    @Test
    fun userResumeDuringSilentWindow_endsWindowAndKeepsMusicAudible() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 7_000L
            )
        )
        coordinator.processEvent(CoordinatorEvent.UserPause)
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 2_000L,
                positionMs = 7_000L
            )
        )

        val resumeActions = coordinator.processEvent(CoordinatorEvent.UserResume)

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertFalse(state.isPaused)
        assertTrue(resumeActions.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(resumeActions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(coordinator.appIsAudible.value)
    }

    @Test
    fun stopCurrentDuringSilentWindow_restoresGainAndPausesMusic() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 9_000L
            )
        )

        val actions = coordinator.stopCurrent(nowMs = 5_000L)

        assertTrue(
            "Кнопка «Остановить» в уведомлении не оставляет нулевое усиление",
            actions.any { it is CoordinatorAction.UnmuteMusicPlayback }
        )
        assertTrue(actions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertFalse(coordinator.state is CoordinatorState.ChoppyPauseActive)
        assertNull("Музыка на паузе не слышна", coordinator.currentSound.value)
    }

    @Test
    fun silentWindowWatchdog_restoresGainAfterStuckWindow() {
        val coordinator = PlaybackCoordinator()
        val testTrack = TrackEntity(id = 1L, title = "Тестовый трек", artist = "Исполнитель", uri = "content://music/1")
        coordinator.processEvent(CoordinatorEvent.UserPlay(testTrack))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 10_000L,
                timestampMs = 1_000L,
                positionMs = 3_000L
            )
        )

        val beforeGrace = coordinator.processEvent(
            CoordinatorEvent.WatchdogTimeoutTick(
                currentTimestampMs = 1_000L + 10_000L +
                    PlaybackCoordinator.CHOPPY_WATCHDOG_GRACE_MS - 1
            )
        )
        assertTrue(beforeGrace.isEmpty())
        assertTrue(coordinator.state is CoordinatorState.ChoppyPauseActive)

        val afterGrace = coordinator.processEvent(
            CoordinatorEvent.WatchdogTimeoutTick(
                currentTimestampMs = 1_000L + 10_000L +
                    PlaybackCoordinator.CHOPPY_WATCHDOG_GRACE_MS
            )
        )
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(afterGrace.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(afterGrace.any { it is CoordinatorAction.SeekTo && it.positionMs == 3_000L })
        assertTrue(afterGrace.any { it is CoordinatorAction.ResumeMusicPlayback })
    }
}
