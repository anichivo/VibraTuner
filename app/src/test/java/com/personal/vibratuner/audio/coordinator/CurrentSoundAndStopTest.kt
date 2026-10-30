package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * currentSound (единственный слышимый звук) и stopCurrent (остановка как завершение,
 * а не отключение задачи). Проверяется чистое состояние координатора.
 */
class CurrentSoundAndStopTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val track = TrackEntity(id = 1, uri = "content://music/1", title = "Песня")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun currentSound_isNullInIdleAndMusicDuringSilentWindow() {
        assertNull(coordinator.currentSound.value)

        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        assertEquals(CurrentSound(CurrentSoundKind.MUSIC, "Песня"), coordinator.currentSound.value)

        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 5_000L
            )
        )
        // Окно тишины НЕ убирает звук из currentSound: плеер продолжает играть
        // (усиление 0), а уведомление и foreground-сервис должны остаться живыми.
        assertEquals(
            "Окно тишины продолжает показывать музыку в уведомлении",
            CurrentSound(CurrentSoundKind.MUSIC, "Песня"),
            coordinator.currentSound.value
        )
        assertFalse("Но приложение не слышно", coordinator.appIsAudible.value)
    }

    @Test
    fun currentSound_describesEveryKindAndIsNullDuringSpeakerOnSilence() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                displayName = "Звонок",
                timestampMs = 2_000L
            )
        )
        assertEquals(
            CurrentSound(CurrentSoundKind.MICROSOUND, "Звонок"),
            coordinator.currentSound.value
        )

        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)

        coordinator.processEvent(CoordinatorEvent.UserPause)
        assertNull("Музыка на паузе не слышна", coordinator.currentSound.value)

        coordinator.processEvent(CoordinatorEvent.UserResume)
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                displayName = "Колонка",
                isPinnedAlarmPrelude = true,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L
            )
        )
        assertEquals(
            CurrentSound(CurrentSoundKind.SPEAKER_ON, "Колонка"),
            coordinator.currentSound.value
        )

        coordinator.processEvent(CoordinatorEvent.SpeakerOnSilenceStarted)
        assertNull(
            "Во время 10-секундной паузы «Включения колонки» звука нет",
            coordinator.currentSound.value
        )

        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(20_000L))
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun currentSound_fakeMicrosoundHasOwnKind() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                displayName = "Колонка",
                isFakePinnedMicrosound = true,
                timestampMs = 1_000L
            )
        )
        assertEquals(
            CurrentSound(CurrentSoundKind.FAKE_SPEAKER_ON, "Колонка"),
            coordinator.currentSound.value
        )
    }

    @Test
    fun currentSound_alarmIsAlarmUntilManualResume() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track, isAlarm = true))
        assertEquals(CurrentSoundKind.ALARM, coordinator.currentSound.value?.kind)

        coordinator.processEvent(CoordinatorEvent.UserPause)
        assertNull(coordinator.currentSound.value)

        // Ручное возобновление больше не считается сигналом будильника
        coordinator.processEvent(CoordinatorEvent.UserResume)
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun currentSound_alarmMusicResumeIsAlarm_notManualMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(CoordinatorEvent.UserPause)

        coordinator.processEvent(CoordinatorEvent.AlarmMusicResume)

        assertEquals(CurrentSoundKind.ALARM, coordinator.currentSound.value?.kind)
    }

    @Test
    fun currentSound_survivesMicrosoundInterruptionOfAlarmMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track, isAlarm = true))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )
        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))
        assertEquals(
            "После микрозвука сигнал будильника продолжается",
            CurrentSoundKind.ALARM,
            coordinator.currentSound.value?.kind
        )
    }

    @Test
    fun stopCurrent_withNothingPlaying_isSafeNoOp() {
        val actions = coordinator.stopCurrent(nowMs = 1_000L)

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertEquals(1, actions.size)
        assertTrue(actions.single() is CoordinatorAction.LogMessage)
    }

    @Test
    fun stopCurrent_microsoundCompletesLikeNaturalEnd_andRestoresMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertTrue(actions.any { it is CoordinatorAction.StopMicroSoundTrack })
        assertTrue(actions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertFalse((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun stopCurrent_fakeMicrosoundCompletesAsUsually_andRestoresMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                isFakePinnedMicrosound = true,
                timestampMs = 2_000L
            )
        )

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertTrue(actions.any { it is CoordinatorAction.StopMicroSoundTrack })
        assertTrue(actions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun stopCurrent_speakerOn_startsFixedPause_andKeepsPreludeState() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                isPinnedAlarmPrelude = true,
                timestampMs = 2_000L
            )
        )

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertTrue(actions.any { it is CoordinatorAction.StopMicroSoundTrack })
        assertTrue(actions.any { it is CoordinatorAction.StartSpeakerOnPostPause })
        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue("Алгоритм/будильник не отменяются", state.isPinnedAlarmPrelude)
        assertTrue("Звук больше не слышен", state.isSpeakerOnSilence)
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun stopCurrent_duringSpeakerOnSilence_isNoOp() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                isPinnedAlarmPrelude = true,
                timestampMs = 2_000L
            )
        )
        coordinator.processEvent(CoordinatorEvent.SpeakerOnSilenceStarted)

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertEquals(1, actions.size)
        assertTrue(actions.single() is CoordinatorAction.LogMessage)
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun stopCurrent_musicPausesWithoutChangingKindOfPlaybackPath() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertTrue(actions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertTrue((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun stopCurrent_alarmCompletesOccurrenceWithoutDisablingSchedules() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track, isAlarm = true))

        val actions = coordinator.stopCurrent(nowMs = 3_000L)

        assertTrue(actions.any { it is CoordinatorAction.StopMusicPlayback })
        assertTrue(
            "Сервис должен перепланировать будущие срабатывания, а не отключить будильник",
            actions.any { it is CoordinatorAction.CompleteAlarmOccurrence }
        )
        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun stopCurrent_secondTapWithinWindow_doesNotPauseResumedMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )
        coordinator.stopCurrent(nowMs = 3_000L)

        val second = coordinator.stopCurrent(
            nowMs = 3_000L + PlaybackCoordinator.STOP_CURRENT_DUPLICATE_WINDOW_MS / 2
        )

        assertEquals(1, second.size)
        assertTrue(second.single() is CoordinatorAction.LogMessage)
        assertFalse((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun stopPolicy_mapsEveryKindToAnAction() {
        assertEquals(
            StopCurrentAction.STOP_MICROSOUND,
            StopCurrentPolicy.actionFor(CurrentSoundKind.MICROSOUND)
        )
        assertEquals(
            StopCurrentAction.STOP_MICROSOUND,
            StopCurrentPolicy.actionFor(CurrentSoundKind.FAKE_SPEAKER_ON)
        )
        assertEquals(
            StopCurrentAction.FINISH_SPEAKER_ON,
            StopCurrentPolicy.actionFor(CurrentSoundKind.SPEAKER_ON)
        )
        assertEquals(
            StopCurrentAction.PAUSE_MUSIC,
            StopCurrentPolicy.actionFor(CurrentSoundKind.MUSIC)
        )
        assertEquals(
            StopCurrentAction.STOP_ALARM,
            StopCurrentPolicy.actionFor(CurrentSoundKind.ALARM)
        )
    }
}
