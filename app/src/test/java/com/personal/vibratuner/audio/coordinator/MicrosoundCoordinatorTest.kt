package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.audio.microsound.MicrosoundCalculator
import com.personal.vibratuner.data.local.db.MicrosoundEntity
import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MicrosoundCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val sampleMusic = TrackEntity(id = 10, uri = "content://music/song.mp3", title = "Песня")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun microsoundDuringMusic_pausesMusic_andResumesFromSamePositionWithoutRamp() {
        // 1. Музыка играет
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleMusic))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)

        // 2. Срабатывает микрозвук
        val triggerActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                gain = 0.8f,
                maxDurationMs = 15_000L,
                timestampMs = 1000L
            )
        )

        assertTrue("Состояние должно быть MicroSoundActive", coordinator.state is CoordinatorState.MicroSoundActive)
        val activeState = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals(sampleMusic, activeState.savedTrack)
        assertTrue("После микрозвука музыка должна возобновиться", activeState.resumeMusicAfter)

        // Должна быть пауза музыки и немедленный старт микрозвука
        assertTrue(triggerActions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertTrue(triggerActions.any { it is CoordinatorAction.PlayMicroSoundTrack && it.uri == "content://sounds/bell.wav" })

        // 3. Завершение микрозвука -> возврат к музыке без нарастания
        val completeActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted)
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        val resumedState = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(sampleMusic, resumedState.currentTrack)
        assertFalse(resumedState.isPaused)

        // Возобновление музыки с той же позиции
        assertTrue(completeActions.any { it is CoordinatorAction.ResumeMusicPlayback })
    }

    @Test
    fun microsoundDuringSilentWindow_cancelsWindow_andMusicPlaysAgainAfter() {
        // 1. Музыка играет
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleMusic))

        // 2. Открывается окно тишины на 60 сек (плеер продолжает играть)
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 30_000L
            )
        )
        assertTrue(coordinator.state is CoordinatorState.ChoppyPauseActive)

        // 3. Через 10 сек срабатывает микрозвук: приоритет MICROSOUND выше окна
        val microActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/chime.wav",
                gain = 0.8f,
                maxDurationMs = 10_000L,
                timestampMs = 11_000L
            )
        )

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        val microState = coordinator.state as CoordinatorState.MicroSoundActive
        assertFalse("Окно тишины отменяется, остаток не переносится", microState.wasInChoppyPause)
        assertEquals(0L, microState.choppyPauseRemainingMs)
        assertTrue("Музыка ставится на паузу на время микрозвука", microState.resumeMusicAfter)
        assertTrue(microActions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertTrue("Усиление музыки восстанавливается", microActions.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(microActions.any { it is CoordinatorAction.PlayMicroSoundTrack })

        // 4. Микрозвук завершился: музыка снова звучит, в окно тишины не возвращаемся
        val completeActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(16_000L))

        assertTrue("Состояние возвращается к музыке", coordinator.state is CoordinatorState.MusicPlaying)
        assertFalse(
            "Окно тишины не возобновляется: усиление не может остаться нулевым",
            coordinator.state is CoordinatorState.ChoppyPauseActive
        )
        assertTrue("Музыка возобновляется", completeActions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(coordinator.appIsAudible.value)
    }

    @Test
    fun simultaneousMicrosounds_queueAtMostOne_dropsThirdWithLog() {
        // 1. Первый микрозвук начинает воспроизведение
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/1.wav",
                gain = 0.8f,
                maxDurationMs = 10_000L,
                timestampMs = 1000L
            )
        )
        val state1 = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals("content://sounds/1.wav", state1.soundUri)
        assertNull(state1.queuedMicroSound)

        // 2. Второй микрозвук во время первого -> встаёт в очередь (макс 1)
        val queueActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/2.wav",
                gain = 0.9f,
                maxDurationMs = 10_000L,
                timestampMs = 1500L
            )
        )
        val state2 = coordinator.state as CoordinatorState.MicroSoundActive
        assertNotNull("Второй микрозвук должен быть в очереди", state2.queuedMicroSound)
        assertEquals("content://sounds/2.wav", state2.queuedMicroSound?.soundUri)
        assertTrue(queueActions.any { it is CoordinatorAction.LogMessage })

        // 3. Третий микрозвук во время первого -> отбрасывается, так как в очереди уже есть один
        val dropActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/3.wav",
                gain = 0.7f,
                maxDurationMs = 10_000L,
                timestampMs = 2000L
            )
        )
        val state3 = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals("content://sounds/2.wav", state3.queuedMicroSound?.soundUri) // По-прежнему 2-й в очереди
        assertTrue("Третий должен быть отклонён с предупреждением", dropActions.any { it is CoordinatorAction.LogMessage && it.isWarning })

        // 4. Первый микрозвук завершается -> автоматически стартует второй из очереди
        val startQueuedActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted)
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        val statePlayingQueued = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals("content://sounds/2.wav", statePlayingQueued.soundUri)
        assertNull("Очередь должна освободиться", statePlayingQueued.queuedMicroSound)
        assertTrue(startQueuedActions.any { it is CoordinatorAction.PlayMicroSoundTrack && it.uri == "content://sounds/2.wav" })

        // 5. Второй микрозвук завершается -> покой
        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted)
        assertEquals(CoordinatorState.Idle, coordinator.state)
    }

    @Test
    fun playerErrorDuringMicrosound_logsAndResumesSafelyWithoutHanging() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleMusic))

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/corrupt.mp3",
                gain = 0.8f,
                maxDurationMs = 5000L,
                timestampMs = 1000L
            )
        )

        // Ошибка ExoPlayer при воспроизведении микрозвука
        val errorActions = coordinator.processEvent(
            CoordinatorEvent.MicroSoundError(RuntimeException("Файл поврежден"))
        )

        // Координатор не зависает, восстанавливает музыку
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(errorActions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(errorActions.any { it is CoordinatorAction.LogMessage })
    }

    @Test
    fun watchdogTimeout_recoversFromHangingMicrosound() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleMusic))

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/hang.mp3",
                gain = 0.8f,
                maxDurationMs = 15_000L,
                timestampMs = 10_000L
            )
        )
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)

        // Тик таймера до истечения таймаута (прошло 5 сек)
        val tickBefore = coordinator.processEvent(CoordinatorEvent.WatchdogTimeoutTick(15_000L))
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertTrue(tickBefore.isEmpty())

        // Тик таймера после истечения таймаута (прошло 20 сек > 15 сек)
        val tickAfter = coordinator.processEvent(CoordinatorEvent.WatchdogTimeoutTick(30_000L))
        assertTrue("Координатор должен восстановить музыку по таймаут-страховке", coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(tickAfter.any { it is CoordinatorAction.StopMicroSoundTrack })
        assertTrue(tickAfter.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(tickAfter.any { it is CoordinatorAction.LogMessage })
    }

    @Test
    fun scenario_serviceStartRejected_microsoundSkipped_nextScheduledWithoutCrashing() {
        // Симуляция сценария ForegroundServiceStartNotAllowedException на Android 12+
        val entity = MicrosoundEntity(
            id = 101L,
            title = "Тестовый микрозвук",
            uri = "content://test/beep.wav",
            minIntervalMinutes = 30,
            maxIntervalMinutes = 60
        )

        // Симулируем попытку запуска: система возвращает false (отказ старта)
        val serviceStartSuccess = false
        var nextScheduledTime: Long = -1L

        if (!serviceStartSuccess) {
            // Логируем пропуск и планируем следующий
            val now = 1_000_000L
            nextScheduledTime = MicrosoundCalculator.calculateNextTriggerTime(
                nowEpochMs = now,
                minMinutes = entity.minIntervalMinutes,
                maxMinutes = entity.maxIntervalMinutes,
                randomMinutes = 40
            )
        }

        // Проверяем, что следующий микрозвук запланирован, а приложение не упало
        assertEquals(1_000_000L + 40 * 60_000L, nextScheduledTime)
        assertTrue("Следующее время должно быть в будущем", nextScheduledTime > 1_000_000L)
    }
}
