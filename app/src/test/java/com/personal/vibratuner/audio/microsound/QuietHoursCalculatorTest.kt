package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class QuietHoursCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val date: LocalDate = LocalDate.of(2026, 1, 15)

    private fun at(hour: Int, minute: Int, dayOffset: Long = 0L): Long =
        date.plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun minutes(hour: Int, minute: Int): Int = hour * 60 + minute

    @Test
    fun normalRange_insideAndOutside() {
        val start = minutes(9, 0)
        val end = minutes(17, 0)

        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(9, 0), start, end))
        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(12, 0), start, end))
        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(16, 59), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(17, 0), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(8, 59), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(0, 0), start, end))
    }

    @Test
    fun crossingMidnight_insideAndOutside() {
        val start = minutes(22, 0)
        val end = minutes(8, 0)

        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(22, 0), start, end))
        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(23, 59), start, end))
        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(0, 0), start, end))
        assertTrue(QuietHoursCalculator.isWithinQuietHours(minutes(7, 59), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(8, 0), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(12, 0), start, end))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(21, 59), start, end))
    }

    @Test
    fun startEqualsEnd_isRejectedAsQuiet() {
        val start = minutes(10, 0)
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(10, 0), start, start))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(minutes(0, 0), start, start))
        assertFalse(QuietHoursSettings(startMinuteOfDay = start, endMinuteOfDay = start).isValid)
    }

    @Test
    fun minuteOfDayIsNormalized() {
        // 25:00 == 01:00 внутри интервала 00:00–02:00
        assertTrue(QuietHoursCalculator.isWithinQuietHours(25 * 60, 0, 2 * 60))
        // -60 == 23:00 внутри интервала 22:00–08:00
        assertTrue(QuietHoursCalculator.isWithinQuietHours(-60, 22 * 60, 8 * 60))
    }

    @Test
    fun epochCheckRespectsEnabledFlag() {
        val quietTime = at(23, 30)
        val enabled = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )

        assertTrue(QuietHoursCalculator.isWithinQuietHours(quietTime, enabled, zone))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(quietTime, enabled.copy(enabled = false), zone))
        assertFalse(QuietHoursCalculator.isWithinQuietHours(quietTime, enabled.copy(startMinuteOfDay = 23 * 60, endMinuteOfDay = 23 * 60), zone))
    }

    @Test
    fun shiftInsideNormalRange_movesToQuietEndPlusOffset() {
        val settings = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(13, 0),
            endMinuteOfDay = minutes(14, 0)
        )
        val trigger = at(13, 30)

        val shifted = QuietHoursCalculator.shiftIfWithinQuietHours(
            triggerEpochMs = trigger,
            settings = settings,
            minIntervalMinutes = 30,
            maxIntervalMinutes = 30,
            random = Random(1),
            zoneId = zone
        )

        assertEquals(at(14, 30), shifted)
    }

    @Test
    fun shiftInsideCrossingMidnightEvening_movesToNextMorning() {
        val settings = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )
        val trigger = at(23, 0)

        val shifted = QuietHoursCalculator.shiftIfWithinQuietHours(
            triggerEpochMs = trigger,
            settings = settings,
            minIntervalMinutes = 60,
            maxIntervalMinutes = 60,
            random = Random(1),
            zoneId = zone
        )

        assertEquals(at(9, 0, dayOffset = 1L), shifted)
    }

    @Test
    fun shiftInsideCrossingMidnightNight_movesToSameMorning() {
        val settings = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )
        val trigger = at(2, 0)

        val shifted = QuietHoursCalculator.shiftIfWithinQuietHours(
            triggerEpochMs = trigger,
            settings = settings,
            minIntervalMinutes = 60,
            maxIntervalMinutes = 60,
            random = Random(1),
            zoneId = zone
        )

        assertEquals(at(9, 0), shifted)
    }

    @Test
    fun shiftAtQuietEnd_doesNotChange() {
        val settings = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )
        val trigger = at(8, 0)

        assertEquals(
            trigger,
            QuietHoursCalculator.shiftIfWithinQuietHours(
                triggerEpochMs = trigger,
                settings = settings,
                minIntervalMinutes = 60,
                maxIntervalMinutes = 120,
                random = Random(1),
                zoneId = zone
            )
        )
    }

    @Test
    fun shiftUsesRandomOffsetWithinMinMax() {
        val settings = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )
        val trigger = at(23, 0)
        val quietEnd = at(8, 0, dayOffset = 1L)

        for (seed in 1..50) {
            val shifted = QuietHoursCalculator.shiftIfWithinQuietHours(
                triggerEpochMs = trigger,
                settings = settings,
                minIntervalMinutes = 30,
                maxIntervalMinutes = 120,
                random = Random(seed),
                zoneId = zone
            )
            val offsetMinutes = (shifted - quietEnd) / 60_000L
            assertTrue("Сдвиг $offsetMinutes мин должен быть >= 30", offsetMinutes >= 30L)
            assertTrue("Сдвиг $offsetMinutes мин должен быть <= 120", offsetMinutes <= 120L)
        }
    }

    @Test
    fun disabledQuietHours_areNotApplied() {
        val settings = QuietHoursSettings(
            enabled = false,
            startMinuteOfDay = minutes(22, 0),
            endMinuteOfDay = minutes(8, 0)
        )
        val trigger = at(23, 0)

        assertEquals(
            trigger,
            QuietHoursCalculator.shiftIfWithinQuietHours(
                triggerEpochMs = trigger,
                settings = settings,
                minIntervalMinutes = 30,
                maxIntervalMinutes = 60,
                random = Random(1),
                zoneId = zone
            )
        )
    }

    @Test
    fun defaultsAreOffAnd23To07() {
        val defaults = QuietHoursSettings()
        assertFalse(defaults.enabled)
        assertEquals(23 * 60, defaults.startMinuteOfDay)
        assertEquals(7 * 60, defaults.endMinuteOfDay)
        assertTrue(defaults.isValid)
    }
}
