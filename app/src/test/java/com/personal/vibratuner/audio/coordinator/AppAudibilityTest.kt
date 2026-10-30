package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единственный сигнал слышимости для SystemVolumeController (часть 3).
 * Проверяется по фактическим переходам координатора, без Android runtime.
 */
class AppAudibilityTest {

    private val track = TrackEntity(id = 1, uri = "content://music/1", title = "Песня")

    @Test
    fun appIsAudibleFollowsPlaybackTransitions() {
        val coordinator = PlaybackCoordinator()
        assertFalse("Покой: не слышно", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        assertTrue("Музыка играет (включая старт/буферизацию)", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.UserPause)
        assertFalse("Пауза пользователя: не слышно", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.UserResume)
        assertTrue(coordinator.appIsAudible.value)

        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(durationMs = 60_000L, timestampMs = 1_000L)
        )
        assertFalse("Прерывистая пауза: не слышно", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.ChoppyPauseCompleted)
        assertTrue("Музыка вернулась", coordinator.appIsAudible.value)
    }

    @Test
    fun microsoundAndSpeakerOnSilence() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )
        assertTrue("Микрозвук слышен", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))
        assertTrue("После микрозвука музыка снова слышна", coordinator.appIsAudible.value)

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                isPinnedAlarmPrelude = true,
                maxDurationMs = 35_000L,
                timestampMs = 10_000L
            )
        )
        assertTrue("Звук «Включение колонки» слышен", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.SpeakerOnSilenceStarted)
        assertFalse("10-секундная тишина: не слышно", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(20_000L))
        assertTrue("Музыка будильника вернулась", coordinator.appIsAudible.value)
    }

    @Test
    fun stopAndResetMakeAppInaudible() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        assertTrue(coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.UserStop)
        assertFalse("Остановка: не слышно", coordinator.appIsAudible.value)

        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.reset()
        assertFalse("reset() сервиса: не слышно", coordinator.appIsAudible.value)
    }
}
