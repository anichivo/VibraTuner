package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единая точка решений о конфликте реального «Включения колонки» и фейка.
 */
class SpeakerOnConflictPolicyTest {

    private val music = TrackEntity(id = 1, uri = "content://music/song.mp3", title = "Песня")

    @Test
    fun fakeMustBeSkipped_onlyDuringRealPrelude() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(music))
        assertFalse(
            "Вне реальной последовательности фейк не пропускается",
            SpeakerOnConflictPolicy.fakeMustBeSkipped(coordinator.state, incomingIsFake = true)
        )

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                maxDurationMs = 35_000L,
                timestampMs = 1_000L,
                isPinnedAlarmPrelude = true
            )
        )
        assertTrue(
            "Во время реальной последовательности фейк пропускается",
            SpeakerOnConflictPolicy.fakeMustBeSkipped(coordinator.state, incomingIsFake = true)
        )
        assertFalse(
            "Обычный микрозвук решается другой веткой координатора",
            SpeakerOnConflictPolicy.fakeMustBeSkipped(coordinator.state, incomingIsFake = false)
        )
    }

    @Test
    fun realPreemptsFake_onlyWhileFakeIsPlaying() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(music))

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                maxDurationMs = 10_000L,
                timestampMs = 1_000L
            )
        )
        assertFalse(
            "Обычный микрозвук — не фейк, отдельного решения нет",
            SpeakerOnConflictPolicy.realPreemptsFake(coordinator.state)
        )

        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(2_000L))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/speaker-on.wav",
                maxDurationMs = 10_000L,
                timestampMs = 3_000L,
                isFakePinnedMicrosound = true
            )
        )
        assertTrue(
            "Пока играет фейк, реальная последовательность его вытесняет",
            SpeakerOnConflictPolicy.realPreemptsFake(coordinator.state)
        )
    }
}
