package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Финальная проверка «Часов тишины» в координаторе (этап 4): перед запуском
 * звука ни один микрозвук — обычный, фейк-включение или «Включение колонки» —
 * не начинает играть в тишину. Событие отклоняется с логом, состояние не меняется.
 */
class MicrosoundQuietHoursCoordinatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val date: LocalDate = LocalDate.of(2026, 1, 15)

    private fun at(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun coordinatorWithQuietHours(): PlaybackCoordinator {
        val coordinator = PlaybackCoordinator(zoneId = zone)
        coordinator.updateQuietHours(
            QuietHoursSettings(
                enabled = true,
                startMinuteOfDay = 23 * 60,
                endMinuteOfDay = 7 * 60
            )
        )
        return coordinator
    }

    private fun trigger(
        timestampMs: Long,
        isFake: Boolean = false,
        isPrelude: Boolean = false
    ) = CoordinatorEvent.TriggerMicroSound(
        soundUri = "content://sounds/test.wav",
        gain = 0.8f,
        maxDurationMs = 10_000L,
        timestampMs = timestampMs,
        isPinnedAlarmPrelude = isPrelude,
        isFakePinnedMicrosound = isFake
    )

    @Test
    fun ordinaryMicrosoundInQuietHours_isRejectedWithLog() {
        val coordinator = coordinatorWithQuietHours()

        val actions = coordinator.processEvent(trigger(at(23, 30)))

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertTrue(actions.single() is CoordinatorAction.LogMessage)
        assertFalse(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }

    @Test
    fun fakePinnedMicrosoundInQuietHours_isRejected() {
        val coordinator = coordinatorWithQuietHours()

        val actions = coordinator.processEvent(trigger(at(0, 30), isFake = true))

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertFalse(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }

    @Test
    fun speakerOnPreludeInQuietHours_isRejected_butAlarmPathUnaffected() {
        val coordinator = coordinatorWithQuietHours()

        val actions = coordinator.processEvent(trigger(at(23, 59), isPrelude = true))

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertFalse(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }

    @Test
    fun microsoundOutsideQuietHours_plays() {
        val coordinator = coordinatorWithQuietHours()

        val actions = coordinator.processEvent(trigger(at(12, 0)))

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
    }

    @Test
    fun quietHoursDisabled_microsoundPlays() {
        val coordinator = coordinatorWithQuietHours()
        coordinator.updateQuietHours(QuietHoursSettings(enabled = false))

        coordinator.processEvent(trigger(at(23, 30)))

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
    }
}
