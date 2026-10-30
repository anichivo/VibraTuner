package com.personal.vibratuner.audio.microsound

import com.personal.vibratuner.data.local.alarm.AlarmCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Чистые тесты расчёта старта последовательности «Включение колонки»:
 * sequenceStart = T − (длительность звука + 10 с).
 */
class PinnedMicrosoundCalculatorTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(utc)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun sequenceStartsBySoundDurationPlusTenSecondsBeforeAlarm() {
        val alarmTime = 1_000_000L
        val duration = 12_500L

        assertEquals(
            alarmTime - duration - PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS,
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = alarmTime,
                speakerOnDurationMs = duration,
                nowMs = 100_000L
            )
        )
    }

    @Test
    fun sequenceStartInPastButAlarmInFuture_StartsImmediately() {
        val now = 100_000L
        val alarmTime = now + 4_000L
        val duration = 30_000L

        assertEquals(
            now,
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = alarmTime,
                speakerOnDurationMs = duration,
                nowMs = now
            )
        )
    }

    @Test
    fun alarmAlreadyStarted_doesNotSchedulePrelude() {
        val now = 100_000L
        assertNull(
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = now,
                speakerOnDurationMs = 5_000L,
                nowMs = now
            )
        )
        assertNull(
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = now - 1L,
                speakerOnDurationMs = 5_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun unknownDuration_doesNotSchedulePrelude() {
        assertNull(
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = 500_000L,
                speakerOnDurationMs = 0L,
                nowMs = 100_000L
            )
        )
        assertNull(
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = 500_000L,
                speakerOnDurationMs = -1L,
                nowMs = 100_000L
            )
        )
    }

    @Test
    fun enormousDuration_startsImmediatelyWithoutOverflow() {
        val now = 100_000L
        assertEquals(
            now,
            PinnedMicrosoundCalculator.calculateSequenceStartMillis(
                alarmTriggerMs = 500_000L,
                speakerOnDurationMs = Long.MAX_VALUE,
                nowMs = now
            )
        )
    }

    @Test
    fun sequenceCrossesMidnightToPreviousDay() {
        // Сейчас воскресенье 23:50 UTC, однократный будильник в 00:00 → следующий день 00:00
        val now = epoch(2026, 1, 4, 23, 50, 0)
        val alarmTime = AlarmCalculator.calculateNextTriggerMillis(
            hour = 0,
            minute = 0,
            daysOfWeek = emptySet(),
            nowMillis = now,
            zoneId = utc
        )
        assertEquals(epoch(2026, 1, 5, 0, 0, 0), alarmTime)

        val sequenceStart = PinnedMicrosoundCalculator.calculateSequenceStartMillis(
            alarmTriggerMs = alarmTime,
            speakerOnDurationMs = 5_000L,
            nowMs = now
        )

        // 23:59:45 предыдущего дня (4 января), а не 5 января
        assertEquals(epoch(2026, 1, 4, 23, 59, 45), sequenceStart)
    }

    @Test
    fun weekdayAlarmSequenceStaysOnTheSameDayBeforeAlarm() {
        // Сейчас воскресенье 4 января 2026, 12:00 UTC; будильник только по понедельникам в 07:00
        val now = epoch(2026, 1, 4, 12, 0, 0)
        val alarmTime = AlarmCalculator.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            nowMillis = now,
            zoneId = utc
        )
        assertEquals(epoch(2026, 1, 5, 7, 0, 0), alarmTime)

        val sequenceStart = PinnedMicrosoundCalculator.calculateSequenceStartMillis(
            alarmTriggerMs = alarmTime,
            speakerOnDurationMs = 5_000L,
            nowMs = now
        )
        assertEquals(epoch(2026, 1, 5, 6, 59, 45), sequenceStart)
    }

    @Test
    fun shortWaitUntilAlarm_holdsSilence() {
        // Штатный случай: пауза 10 с заканчивается ровно в T.
        assertTrue(PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(1L))
        assertTrue(PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(10_000L))
        assertTrue(
            PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(
                PinnedMicrosoundCalculator.MAX_SILENCE_HOLD_MS
            )
        )
    }

    @Test
    fun tooLongWaitUntilAlarm_endsSequenceInsteadOfBlockingMicrosounds() {
        // Файл короче метаданных или часы сдвинулись: до T далеко.
        // Держать приоритет MICROSOUND часами нельзя — музыку запустит будильник.
        assertFalse(
            PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(
                PinnedMicrosoundCalculator.MAX_SILENCE_HOLD_MS + 1L
            )
        )
        assertFalse(PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(3_600_000L))
        assertFalse("Без остатка ждать нечего", PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(0L))
        assertFalse(
            "Отрицательный остаток — будильник уже наступил",
            PinnedMicrosoundCalculator.shouldHoldSilenceUntilAlarm(-5_000L)
        )
    }
}
