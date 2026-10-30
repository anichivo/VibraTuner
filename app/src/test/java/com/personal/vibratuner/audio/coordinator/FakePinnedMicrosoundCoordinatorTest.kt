package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Фейк-включение колонки идёт через существующий путь микрозвука:
 * a) во время реальной последовательности фейк пропускается с логом;
 * b) реальная последовательность останавливает фейк и начинается с начала;
 * c) в остальных случаях фейк ведёт себя как обычный микрозвук.
 */
class FakePinnedMicrosoundCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val music = TrackEntity(id = 5, uri = "content://music/song.mp3", title = "Песня")
    private val speakerOnUri = "content://sounds/speaker-on.wav"

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    private fun triggerRealPrelude(timestampMs: Long): List<CoordinatorAction> {
        return coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = speakerOnUri,
                gain = 0.8f,
                maxDurationMs = 35_000L,
                timestampMs = timestampMs,
                isPinnedAlarmPrelude = true
            )
        )
    }

    private fun triggerFake(timestampMs: Long): List<CoordinatorAction> {
        return coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = speakerOnUri,
                gain = 0.8f,
                maxDurationMs = 40_000L,
                timestampMs = timestampMs,
                isFakePinnedMicrosound = true
            )
        )
    }

    @Test
    fun fakeDuringRealPreludeOrItsPause_isSkippedWithLogWithoutPlayback() {
        triggerRealPrelude(1_000L)

        // Во время 10-секундной паузы координатор остаётся в том же состоянии
        // MicroSoundActive с isPinnedAlarmPrelude = true, поэтому проверка та же.
        val actions = triggerFake(20_000L)

        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue("Реальная последовательность не прерывается", state.isPinnedAlarmPrelude)
        assertEquals(speakerOnUri, state.soundUri)
        assertTrue(
            "Пропуск фейка должен быть записан в лог",
            actions.any { it is CoordinatorAction.LogMessage && it.isWarning }
        )
        assertTrue(
            "Фейк не должен начинать воспроизведение",
            actions.none { it is CoordinatorAction.PlayMicroSoundTrack }
        )
        assertTrue(
            "Реальный звук не должен останавливаться",
            actions.none { it is CoordinatorAction.StopMicroSoundTrack }
        )
    }

    @Test
    fun fakeDuringSpeakerOnSilence_isSkippedToo() {
        // Пауза 10 с — это то же состояние MicroSoundActive с isPinnedAlarmPrelude,
        // поэтому фейк не должен звучать и во время тишины перед будильником.
        triggerRealPrelude(1_000L)
        coordinator.processEvent(CoordinatorEvent.SpeakerOnSilenceStarted)

        val actions = triggerFake(15_000L)

        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue(state.isPinnedAlarmPrelude)
        assertTrue(state.isSpeakerOnSilence)
        assertTrue(actions.any { it is CoordinatorAction.LogMessage && it.isWarning })
        assertTrue(actions.none { it is CoordinatorAction.PlayMicroSoundTrack })
        assertNull(coordinator.currentSound.value)
    }

    @Test
    fun realPreludeDuringFake_stopsFakeImmediatelyAndStartsRealFromBeginning() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(music))
        triggerFake(1_000L)

        val beforeState = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue("Фейк должен играть", beforeState.isFakePinnedMicrosound)
        assertFalse(beforeState.isPinnedAlarmPrelude)

        val actions = triggerRealPrelude(3_000L)

        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue("Реальный звук начинает звучать", state.isPinnedAlarmPrelude)
        assertFalse("Флаг фейка сбрасывается", state.isFakePinnedMicrosound)
        assertEquals(speakerOnUri, state.soundUri)
        assertEquals("Музыка запоминается для возврата", music, state.savedTrack)

        val stopIndex = actions.indexOfFirst { it is CoordinatorAction.StopMicroSoundTrack }
        val playIndex = actions.indexOfFirst {
            it is CoordinatorAction.PlayMicroSoundTrack && it.uri == speakerOnUri
        }
        assertTrue("Фейк должен быть остановлен", stopIndex >= 0)
        assertTrue("Реальный звук должен быть запущен", playIndex >= 0)
        assertTrue("Реальный звук запускается сразу после остановки фейка", playIndex > stopIndex)
        assertTrue(
            "Конфликт должен быть записан в лог",
            actions.any {
                it is CoordinatorAction.LogMessage &&
                    it.isWarning &&
                    it.message.contains("фейк", ignoreCase = true)
            }
        )
    }

    @Test
    fun musicResumesAfterRealPreludePreemptedFake() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(music))
        triggerFake(1_000L)
        triggerRealPrelude(3_000L)

        // Реальный звук доиграл (в сервисе — после паузы 10 с)
        val completed = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(50_000L))

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(music, state.currentTrack)
        assertFalse("Музыка не должна остаться на паузе", state.isPaused)
        assertTrue(completed.any { it is CoordinatorAction.ResumeMusicPlayback })
    }

    @Test
    fun fakeDuringMusic_playsLikeOrdinaryMicrosoundAndResumesMusic() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(music))

        val startActions = triggerFake(1_000L)
        val activeState = coordinator.state as CoordinatorState.MicroSoundActive
        assertTrue(activeState.isFakePinnedMicrosound)
        assertEquals(music, activeState.savedTrack)
        assertTrue(startActions.any { it is CoordinatorAction.PauseMusicPlayback })
        assertTrue(startActions.any { it is CoordinatorAction.PlayMicroSoundTrack })

        val completed = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(12_000L))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertTrue(completed.any { it is CoordinatorAction.ResumeMusicPlayback })
    }

    @Test
    fun ordinaryMicrosoundQueuedBehindFakeStartsAfterFake() {
        triggerFake(1_000L)

        // Обычный микрозвук во время фейка встаёт в очередь
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/chime.wav",
                maxDurationMs = 10_000L,
                timestampMs = 2_000L
            )
        )
        val withQueue = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals(
            "Фейк продолжает играть, второй звук в очереди",
            speakerOnUri,
            withQueue.soundUri
        )

        // Фейк доиграл — запускается звук из очереди (уже не фейк)
        val queuedActions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))
        val queuedState = coordinator.state as CoordinatorState.MicroSoundActive
        assertFalse(queuedState.isFakePinnedMicrosound)
        assertEquals("content://sounds/chime.wav", queuedState.soundUri)
        assertTrue(queuedActions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }
}
