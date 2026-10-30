package com.personal.vibratuner.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Длительность сигнала будильника: хранение в минутах, ввод двумя колёсами
 * (часы 0..24, минуты 0..59), диапазон 1 минута .. 24 часа; 0 ч 0 мин
 * зажимается до 1 минуты, при 24 часах минуты блокируются на 0.
 */
class AlarmDurationTest {

    @Test
    fun clampsStoredMinutesToAllowedRange() {
        assertEquals(1, AlarmDuration.clampStoredMinutes(0))
        assertEquals(1, AlarmDuration.clampStoredMinutes(-30))
        assertEquals(2, AlarmDuration.clampStoredMinutes(2))
        assertEquals(180, AlarmDuration.clampStoredMinutes(180))
        assertEquals(1440, AlarmDuration.clampStoredMinutes(1440))
        assertEquals(1440, AlarmDuration.clampStoredMinutes(1500))
    }

    @Test
    fun convertsStoredMinutesToWheelValues() {
        assertEquals(0, AlarmDuration.wheelHours(30))
        assertEquals(30, AlarmDuration.wheelMinutes(30))
        assertEquals(1, AlarmDuration.wheelHours(90))
        assertEquals(30, AlarmDuration.wheelMinutes(90))
        assertEquals(23, AlarmDuration.wheelHours(1439))
        assertEquals(59, AlarmDuration.wheelMinutes(1439))
        // 24 часа — ровно 24 ч 0 мин, верхняя граница без «00:00».
        assertEquals(24, AlarmDuration.wheelHours(1440))
        assertEquals(0, AlarmDuration.wheelMinutes(1440))
        // Legacy-значения > 1440 показываются зажатыми, данные не меняются.
        assertEquals(24, AlarmDuration.wheelHours(1500))
        assertEquals(0, AlarmDuration.wheelMinutes(1500))
        // Значение меньше минуты показывается как 1 минута.
        assertEquals(0, AlarmDuration.wheelHours(0))
        assertEquals(1, AlarmDuration.wheelMinutes(0))
    }

    @Test
    fun convertsWheelValuesToStoredMinutes() {
        assertEquals(2, AlarmDuration.fromWheels(0, 2))
        assertEquals(30, AlarmDuration.fromWheels(0, 30))
        assertEquals(60, AlarmDuration.fromWheels(1, 0))
        assertEquals(90, AlarmDuration.fromWheels(1, 30))
        assertEquals(1439, AlarmDuration.fromWheels(23, 59))
        assertEquals(1440, AlarmDuration.fromWheels(24, 0))
    }

    @Test
    fun zeroHoursAndMinutesAreClampedToOneMinute() {
        assertEquals(1, AlarmDuration.fromWheels(0, 0))
        assertEquals(1, AlarmDuration.fromWheels(-5, -5))
    }

    @Test
    fun twentyFourHoursAlwaysDropMinutes() {
        assertEquals(1440, AlarmDuration.fromWheels(24, 30))
        assertEquals(1440, AlarmDuration.fromWheels(25, 30))
    }

    @Test
    fun minuteWheelIsDisabledOnlyAtTwentyFourHours() {
        assertFalse(AlarmDuration.isMinuteWheelDisabled(0))
        assertFalse(AlarmDuration.isMinuteWheelDisabled(23))
        assertTrue(AlarmDuration.isMinuteWheelDisabled(24))
        assertTrue(AlarmDuration.isMinuteWheelDisabled(25))
    }

    @Test
    fun validatesStoredMinutesWithoutChangingTheDataModel() {
        assertTrue(AlarmDuration.isValidMinutes(1))
        assertTrue(AlarmDuration.isValidMinutes(1440))
        assertFalse(AlarmDuration.isValidMinutes(0))
        assertFalse(AlarmDuration.isValidMinutes(1441))
    }

    @Test
    fun defaultIsThirtyMinutes() {
        assertEquals(30, AlarmDuration.DEFAULT_MINUTES)
    }

    @Test
    fun everyWheelCombinationProducesAValidStoredValue() {
        for (hours in 0..AlarmDuration.MAX_HOURS) {
            for (minutes in 0..59) {
                assertTrue(
                    "hours=$hours minutes=$minutes",
                    AlarmDuration.isValidMinutes(AlarmDuration.fromWheels(hours, minutes))
                )
            }
        }
    }
}
