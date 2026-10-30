package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Координатор: «Включение колонки» идёт через существующий путь микрозвука
 * (приоритет MICROSOUND, пауза музыки, таймаут-страховка). Ошибка звука
 * не должна блокировать будильник.
 */
class PinnedMicrosoundCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val alarmMusic = TrackEntity(id = 77, uri = "content://music/alarm.mp3", title = "Будильник")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun pinnedPreludeFromIdle_playsThroughExistingMicrosoundPath() {
        val actions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue("Флаг предзвука должен сохраниться", state.isPinnedAlarmPrelude)
        assertEquals("content://sounds/speaker-on.wav", state.soundUri)
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack && it.gain == 0.8f })
    }

    @Test
    fun pinnedPreludeDuringMusic_pausesMusicAndRemembersIt() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(alarmMusic))

        val actions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                gain = 0.7f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue(state.isPinnedAlarmPrelude)
        assertEquals(alarmMusic, state.savedTrack)
        assertTrue("Музыка должна ставиться на паузу", actions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }

    @Test
    fun alarmStartsAfterPinnedPreludeEnds() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        // В сервисе завершение приходит после 10-секундной паузы; здесь проверяем,
        // что после него координатор свободен и музыка будильника может стартовать.
        val completedActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(15_000L))
        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertTrue(completedActions.any { it is CoordinatorAction.StopMusicPlayback })

        // Будильник может стартовать сразу после окончания предзвука
        val alarmActions = coordinator.processEvent(CoordinatorEvent.UserPlay(alarmMusic))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(alarmActions.any { it is CoordinatorAction.StartMusicPlayback && it.track == alarmMusic })
    }

    @Test
    fun watchdog_forceCompletesHangingPinnedPrelude() {
        // Старт предзвука в 10 000 мс, maxDuration 35 000 мс → страховка на 45 000 мс
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/long-intro.wav",
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        val beforeTimeout = coordinator.processEvent(CoordinatorEvent.WatchdogTimeoutTick(44_999L))
        assertTrue("До watchdog звук продолжает играть", coordinator.state is CoordinatorState.MicroSoundActive)
        assertTrue(beforeTimeout.isEmpty())

        val atTimeout = coordinator.processEvent(CoordinatorEvent.WatchdogTimeoutTick(45_000L))
        assertFalse(coordinator.state is CoordinatorState.MicroSoundActive)
        assertTrue(atTimeout.any { it is CoordinatorAction.StopMicroSoundTrack })
        assertTrue(atTimeout.any { it is CoordinatorAction.LogMessage })
    }

    @Test
    fun pinnedPreludePreemptsRegularMicrosound_andSkipsFollowingMicrosounds() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/periodic.wav",
                gain = 0.6f,
                maxDurationMs = 15_000L,
                timestampMs = 5_000L
            )
        )

        val preemptActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                gain = 0.8f,
                maxDurationMs = 30_000L,
                timestampMs = 6_000L,
                isPinnedAlarmPrelude = true
            )
        )

        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue(state.isPinnedAlarmPrelude)
        assertEquals("content://sounds/speaker-on.wav", state.soundUri)
        assertTrue(
            "Старый микрозвук должен быть остановлен",
            preemptActions.any { it is CoordinatorAction.StopMicroSoundTrack }
        )
        assertTrue(
            "Предзвук должен запуститься вместо старого микрозвука",
            preemptActions.any { it is CoordinatorAction.PlayMicroSoundTrack && it.uri == "content://sounds/speaker-on.wav" }
        )

        val skippedActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/another.wav",
                gain = 0.7f,
                maxDurationMs = 15_000L,
                timestampMs = 8_000L
            )
        )

        assertTrue(
            "Во время предзвука другой микрозвук должен быть пропущен",
            skippedActions.any { it is CoordinatorAction.LogMessage && it.isWarning }
        )
        assertTrue(skippedActions.none { it is CoordinatorAction.PlayMicroSoundTrack })
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
    }

    @Test
    fun pinnedPreludeCompletedDuringPausedMusic_restoresPauseWithoutStartingMusic() {
        // Сценарий отмены последовательности (удаление/выключение будильника):
        // предзвук завершается обычным путём, а не событием UserStop, поэтому
        // музыка остаётся на паузе с сохранённой позицией, а не останавливается.
        coordinator.processEvent(CoordinatorEvent.UserPlay(alarmMusic))
        coordinator.processEvent(CoordinatorEvent.UserPause)
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        val actions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(20_000L))

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertTrue("Музыка должна остаться на паузе, как её оставил пользователь", state.isPaused)
        assertTrue(
            "Музыку нельзя запускать без команды пользователя",
            actions.none { it is CoordinatorAction.ResumeMusicPlayback }
        )
        assertTrue(
            "Музыка не должна уничтожаться (StopMusicPlayback) при отмене предзвука",
            actions.none { it is CoordinatorAction.StopMusicPlayback }
        )
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun pinnedPreludeError_doesNotBlockAlarm() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/deleted.wav",
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L,
                isPinnedAlarmPrelude = true
            )
        )

        // Файл недоступен / плеер упал: ошибка не оставляет координатор в звуке
        val errorActions = coordinator.processEvent(
            CoordinatorEvent.MicroSoundError(RuntimeException("Файл удалён"))
        )
        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertTrue(errorActions.any { it is CoordinatorAction.LogMessage })

        // Будильник на T стартует как обычно
        val alarmActions = coordinator.processEvent(CoordinatorEvent.UserPlay(alarmMusic))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(alarmActions.any { it is CoordinatorAction.StartMusicPlayback })
    }
}
