package com.personal.vibratuner.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Конец сигнала: старт + длительность с переходом через полночь; ровно 24 часа
 * дают время старта. Формат — 24-часовой «HH:mm».
 */
class TimeOfDayFormatTest {

    @Test
    fun endTimeStaysInsideTheSameDay() {
        assertEquals(17 * 60, endTimeMinutesOfDay(16 * 60, 60))
        assertEquals(16 * 60 + 30, endTimeMinutesOfDay(16 * 60, 30))
        assertEquals(23 * 60 + 59, endTimeMinutesOfDay(23 * 60, 59))
    }

    @Test
    fun endTimeWrapsAroundMidnight() {
        // 23:00 + 1 ч 30 мин = 00:30.
        assertEquals(30, endTimeMinutesOfDay(23 * 60, 90))
        assertEquals(0, endTimeMinutesOfDay(23 * 60 + 30, 30))
        assertEquals(5, endTimeMinutesOfDay(23 * 60 + 55, 10))
    }

    @Test
    fun twentyFourHoursReturnTheStartTime() {
        assertEquals(7 * 60, endTimeMinutesOfDay(7 * 60, 24 * 60))
        assertEquals(0, endTimeMinutesOfDay(0, 24 * 60))
        assertEquals(23 * 60 + 59, endTimeMinutesOfDay(23 * 60 + 59, 24 * 60))
    }

    @Test
    fun endTimeIsFormattedAsTwentyFourHourTime() {
        assertEquals(
            "00:30",
            formatTimeOfDay(endTimeMinutesOfDay(23 * 60, 90), Locale.US)
        )
        assertEquals("17:00", formatTimeOfDay(17 * 60, Locale.US))
        assertEquals("00:05", formatTimeOfDay(5, Locale.US))
        assertEquals("23:59", formatTimeOfDay(23 * 60 + 59, Locale.US))
    }

    @Test
    fun timeOfDayIsNormalizedToTheDay() {
        // 24:00 — это 00:00, отрицательные значения тоже приводятся к суткам.
        assertEquals("00:00", formatTimeOfDay(24 * 60, Locale.US))
        assertEquals("23:00", formatTimeOfDay(-60, Locale.US))
    }
}
