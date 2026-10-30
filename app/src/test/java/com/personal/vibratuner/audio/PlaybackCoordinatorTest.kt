package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.coordinator.CoordinatorAction
import com.personal.vibratuner.audio.coordinator.CoordinatorEvent
import com.personal.vibratuner.audio.coordinator.CoordinatorState
import com.personal.vibratuner.audio.coordinator.PlaybackCoordinator
import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val sampleTrack1 = TrackEntity(id = 1, uri = "content://audio/1", title = "Трек 1")
    private val sampleTrack2 = TrackEntity(id = 2, uri = "content://audio/2", title = "Трек 2")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(CoordinatorState.Idle, coordinator.state)
    }

    @Test
    fun userPlay_transitionsToMusicPlaying_andEmitsStartPlayback() {
        val actions = coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(sampleTrack1, state.currentTrack)
        assertEquals(false, state.isPaused)

        assertEquals(1, actions.size)
        assertTrue(actions.first() is CoordinatorAction.StartMusicPlayback)
        assertEquals(sampleTrack1, (actions.first() as CoordinatorAction.StartMusicPlayback).track)
    }

    @Test
    fun userPauseAndResume_togglesPausedState() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        val pauseActions = coordinator.processEvent(CoordinatorEvent.UserPause)
        assertTrue((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertEquals(1, pauseActions.size)
        assertTrue(pauseActions.first() is CoordinatorAction.PauseMusicPlayback)

        val resumeActions = coordinator.processEvent(CoordinatorEvent.UserResume)
        assertEquals(false, (coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertEquals(1, resumeActions.size)
        assertTrue(resumeActions.first() is CoordinatorAction.ResumeMusicPlayback)
    }

    @Test
    fun userStop_resetsToIdle_andEmitsStopPlayback() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        val stopActions = coordinator.processEvent(CoordinatorEvent.UserStop)

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertEquals(1, stopActions.size)
        assertTrue(stopActions.first() is CoordinatorAction.StopMusicPlayback)
    }

    @Test
    fun trackEnded_emitsPlayNextTrack_forContinuousLoop() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        val actions = coordinator.processEvent(CoordinatorEvent.TrackEnded)

        assertEquals(1, actions.size)
        assertTrue(actions.first() is CoordinatorAction.PlayNextTrack)
    }

    @Test
    fun unavailableTrackError_logsAndAdvancesToNextTrackWithoutCrashing() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        val error = RuntimeException("Файл не найден")
        val actions = coordinator.processEvent(CoordinatorEvent.TrackError(sampleTrack1, error))

        // Координатор остаётся в живом состоянии, не зависает
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)

        // Действия: лог ошибки и переход к следующему треку
        assertEquals(2, actions.size)
        assertTrue(actions[0] is CoordinatorAction.LogMessage)
        assertTrue(actions[1] is CoordinatorAction.PlayNextTrack)
    }

    @Test
    fun microSoundPreemptsMusic_andResumesAfterCompletion() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        // Срабатывание микрозвука (наивысший приоритет)
        val triggerActions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sound/beep",
                gain = 0.8f,
                maxDurationMs = 5000L,
                timestampMs = 1000L
            )
        )

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        val microState = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals(sampleTrack1, microState.savedTrack)
        assertTrue(microState.resumeMusicAfter)

        // Должно приостановить музыку и воспроизвести микрозвук
        assertEquals(2, triggerActions.size)
        assertTrue(triggerActions[0] is CoordinatorAction.PauseMusicPlayback)
        assertTrue(triggerActions[1] is CoordinatorAction.PlayMicroSoundTrack)

        // Попытка пользовательского действия (MUSIC) во время микрозвука отклоняется
        val rejectedActions = coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack2))
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertEquals(1, rejectedActions.size)
        assertTrue(rejectedActions.first() is CoordinatorAction.LogMessage)

        // Завершение микрозвука -> возврат к музыке
        val completeActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted)
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        val resumedState = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(sampleTrack1, resumedState.currentTrack)
        assertEquals(false, resumedState.isPaused)
        assertTrue(completeActions.any { it is CoordinatorAction.ResumeMusicPlayback })
    }

    @Test
    fun userSeek_whilePlaying_movesPositionWithoutRestartingPlayback() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(42_000L))

        assertEquals(1, actions.size)
        val seek = actions.first() as CoordinatorAction.SeekTo
        assertEquals(42_000L, seek.positionMs)
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertFalse((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
    }

    @Test
    fun userSeek_whilePaused_doesNotStartMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(CoordinatorEvent.UserPause)

        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(10_000L))

        assertEquals(1, actions.size)
        assertTrue(actions.first() is CoordinatorAction.SeekTo)
        assertTrue(actions.none { it is CoordinatorAction.StartMusicPlayback })
        assertTrue(actions.none { it is CoordinatorAction.ResumeMusicPlayback })
        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertTrue(state.isPaused)
    }

    @Test
    fun userSeek_duringMicrosound_isRejectedAndDoesNotStartMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sound/beep",
                gain = 0.8f,
                maxDurationMs = 5000L,
                timestampMs = 1000L
            )
        )

        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(15_000L))

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertEquals(1, actions.size)
        assertTrue(actions.first() is CoordinatorAction.LogMessage)
        assertTrue(actions.none { it is CoordinatorAction.SeekTo })
        assertTrue(actions.none { it is CoordinatorAction.StartMusicPlayback })
        assertTrue(actions.none { it is CoordinatorAction.ResumeMusicPlayback })
    }

    @Test
    fun userSeek_duringSilentWindow_endsWindowAndSeeks() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 3000L,
                timestampMs = 1000L,
                positionMs = 2000L
            )
        )

        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(15_000L))

        // Окно тишины закрывается (усиление не остаётся нулевым), seek применяется:
        // пользовательское действие важнее тишины.
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(actions.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(actions.any { it is CoordinatorAction.SeekTo && it.positionMs == 15_000L })
        assertTrue(actions.none { it is CoordinatorAction.StartMusicPlayback })
    }

    @Test
    fun userSeek_inIdle_isIgnoredAndDoesNotStartMusic() {
        val actions = coordinator.processEvent(CoordinatorEvent.UserSeek(15_000L))

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertTrue(actions.none { it is CoordinatorAction.SeekTo })
        assertTrue(actions.none { it is CoordinatorAction.StartMusicPlayback })
    }

    @Test
    fun watchdogTimeout_recoversFromStuckSilentWindow() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        // Открываем окно тишины
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 3000L,
                timestampMs = 1000L,
                positionMs = 1500L
            )
        )
        assertTrue(coordinator.state is CoordinatorState.ChoppyPauseActive)

        // Тик таймера до истечения срока + запаса - состояние остаётся
        val tickBefore = coordinator.processEvent(
            CoordinatorEvent.WatchdogTimeoutTick(
                currentTimestampMs = 1000L + 3000L + PlaybackCoordinator.CHOPPY_WATCHDOG_GRACE_MS - 1
            )
        )
        assertTrue(coordinator.state is CoordinatorState.ChoppyPauseActive)
        assertTrue(tickBefore.isEmpty())

        // Тик страховки после срока + запаса (автомат завис) - окно возвращает звук
        val tickAfter = coordinator.processEvent(
            CoordinatorEvent.WatchdogTimeoutTick(
                currentTimestampMs = 1000L + 3000L + PlaybackCoordinator.CHOPPY_WATCHDOG_GRACE_MS
            )
        )
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertEquals(sampleTrack1, (coordinator.state as CoordinatorState.MusicPlaying).currentTrack)
        assertTrue(tickAfter.any { it is CoordinatorAction.UnmuteMusicPlayback })
        assertTrue(tickAfter.any { it is CoordinatorAction.SeekTo && it.positionMs == 1500L })
        assertTrue(tickAfter.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(tickAfter.any { it is CoordinatorAction.LogMessage })
    }
}
