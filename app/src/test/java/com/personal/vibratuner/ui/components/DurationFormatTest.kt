package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистые правила форматирования длительности: «45 мин», «1 час» / «2 часа» /
 * «5 часов» / «24 часа», «1 ч 30 мин».
 */
class DurationFormatTest {

    @Test
    fun minutesUnderAnHourStayMinutes() {
        assertEquals(DurationLabel.Minutes(0), durationLabel(0))
        assertEquals(DurationLabel.Minutes(1), durationLabel(1))
        assertEquals(DurationLabel.Minutes(45), durationLabel(45))
        assertEquals(DurationLabel.Minutes(59), durationLabel(59))
    }

    @Test
    fun wholeHoursUseTheHourForm() {
        assertEquals(DurationLabel.Hours(1), durationLabel(60))
        assertEquals(DurationLabel.Hours(2), durationLabel(120))
        assertEquals(DurationLabel.Hours(5), durationLabel(300))
        assertEquals(DurationLabel.Hours(24), durationLabel(1440))
    }

    @Test
    fun hoursAndMinutesKeepBothParts() {
        assertEquals(DurationLabel.HoursMinutes(1, 1), durationLabel(61))
        assertEquals(DurationLabel.HoursMinutes(1, 30), durationLabel(90))
        assertEquals(DurationLabel.HoursMinutes(23, 59), durationLabel(1439))
        assertEquals(DurationLabel.HoursMinutes(24, 1), durationLabel(1441))
    }

    @Test
    fun hoursAreNeverRoundedToWholeHours() {
        // 119 минут — это «1 ч 59 мин», а не «2 часа»: округления нет.
        assertEquals(DurationLabel.HoursMinutes(1, 59), durationLabel(119))
    }

    @Test
    fun negativeMinutesAreTreatedAsZero() {
        assertEquals(DurationLabel.Minutes(0), durationLabel(-1))
        assertEquals(DurationLabel.Minutes(0), durationLabel(-90))
    }
}
