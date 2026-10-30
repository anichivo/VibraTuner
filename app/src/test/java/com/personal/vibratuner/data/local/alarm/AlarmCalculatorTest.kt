package com.personal.vibratuner.data.local.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmCalculatorTest {

    private val zoneId = ZoneId.of("UTC")

    // Базовая точка отсчета: Пятница, 2026-10-23 10:00:00 UTC
    private val baseFridayMillis = LocalDateTime.of(2026, 10, 23, 10, 0, 0)
        .atZone(zoneId).toInstant().toEpochMilli()

    @Test
    fun oneTimeAlarm_futureToday_triggersToday() {
        // Будильник на 14:30 сегодня (пятница)
        val trigger = AlarmCalculator.calculateNextTriggerMillis(
            hour = 14,
            minute = 30,
            daysOfWeek = emptySet(),
            nowMillis = baseFridayMillis,
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(2026, 10, 23, 14, 30, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expected, trigger)
    }

    @Test
    fun oneTimeAlarm_pastToday_crossesMidnightToTomorrow() {
        // Сейчас 10:00, будильник на 07:00 (уже прошел сегодня) -> должен сработать завтра (суббота 2026-10-24 07:00)
        val trigger = AlarmCalculator.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            daysOfWeek = emptySet(),
            nowMillis = baseFridayMillis,
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(2026, 10, 24, 7, 0, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expected, trigger)
    }

    @Test
    fun repeatingAlarm_todayMatchingAndFutureTime_triggersToday() {
        // Пятница и Понедельник, время 18:00. Сегодня пятница, 10:00 -> срабатывает сегодня в 18:00
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val trigger = AlarmCalculator.calculateNextTriggerMillis(
            hour = 18,
            minute = 0,
            daysOfWeek = days,
            nowMillis = baseFridayMillis,
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(2026, 10, 23, 18, 0, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expected, trigger)
    }

    @Test
    fun repeatingAlarm_todayMatchingButPastTime_triggersNextScheduledDay() {
        // Пятница и Понедельник, время 06:30. Сегодня пятница 10:00 (06:30 прошло) -> следующий Понедельник 2026-10-26 06:30
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val trigger = AlarmCalculator.calculateNextTriggerMillis(
            hour = 6,
            minute = 30,
            daysOfWeek = days,
            nowMillis = baseFridayMillis,
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(2026, 10, 26, 6, 30, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expected, trigger)
    }

    @Test
    fun repeatingAlarm_transitionThroughMidnight_triggersCorrectly() {
        // Сейчас пятница 23:45. Будильник на субботу в 00:15 (через 30 минут)
        val lateFridayMillis = LocalDateTime.of(2026, 10, 23, 23, 45, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        val days = setOf(DayOfWeek.SATURDAY)
        val trigger = AlarmCalculator.calculateNextTriggerMillis(
            hour = 0,
            minute = 15,
            daysOfWeek = days,
            nowMillis = lateFridayMillis,
            zoneId = zoneId
        )

        val expected = LocalDateTime.of(2026, 10, 24, 0, 15, 0)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expected, trigger)
        assertEquals(30 * 60 * 1000L, trigger - lateFridayMillis)
    }

    @Test
    fun parsingAndFormattingDaysOfWeek() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val formatted = AlarmCalculator.formatDaysOfWeek(days)
        assertEquals("1,3,5", formatted)

        val parsed = AlarmCalculator.parseDaysOfWeek(formatted)
        assertEquals(days, parsed)

        assertTrue(AlarmCalculator.parseDaysOfWeek("").isEmpty())
        assertTrue(AlarmCalculator.parseDaysOfWeek("   ").isEmpty())
    }
}
