package com.personal.vibratuner.audio.microsound

import com.personal.vibratuner.data.local.db.MicrosoundEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Чистая математика собственного окна работы микрозвука (Stage 3):
 * границы, переход через полночь, «весь день», смена суток и обрезка
 * рассчитанного времени срабатывания. Без Android-зависимостей.
 */
class MicrosoundWindowTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 3, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun local(epochMs: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()

    // --- containsMinuteOfDay -------------------------------------------------

    @Test
    fun containsStartInclusiveAndEndExclusive() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        assertTrue(window.containsMinuteOfDay(11 * 60 + 20))
        assertFalse(window.containsMinuteOfDay(11 * 60 + 19))
        assertTrue(window.containsMinuteOfDay(17 * 60 - 1))
        assertFalse(window.containsMinuteOfDay(17 * 60))
        assertFalse(window.containsMinuteOfDay(3 * 60))
    }

    @Test
    fun containsCrossesMidnight() {
        val window = MicrosoundWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        assertTrue(window.containsMinuteOfDay(22 * 60))
        assertTrue(window.containsMinuteOfDay(23 * 60 + 59))
        assertTrue(window.containsMinuteOfDay(0))
        assertTrue(window.containsMinuteOfDay(5 * 60 + 59))
        assertFalse(window.containsMinuteOfDay(6 * 60))
        assertFalse(window.containsMinuteOfDay(12 * 60))
    }

    @Test
    fun windowWithEqualBoundsIsInvalidAndNeverRestricts() {
        val window = MicrosoundWindow(startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60)
        assertFalse(window.isValid)
        assertTrue(window.containsMinuteOfDay(3 * 60))
        assertTrue(window.containsMinuteOfDay(9 * 60))
    }

    @Test
    fun windowWithEqualBoundsAcrossMidnightIsInvalidToo() {
        // 00:00 и 24:00 — одно и то же время суток.
        assertFalse(MicrosoundWindow(startMinuteOfDay = 0, endMinuteOfDay = 1440).isValid)
    }

    // --- isWithinWindow ------------------------------------------------------

    @Test
    fun isWithinWindowUsesLocalTimeOfCandidate() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        assertTrue(MicrosoundWindowCalculator.isWithinWindow(at(5, 12, 0), window, zone))
        assertFalse(MicrosoundWindowCalculator.isWithinWindow(at(5, 18, 0), window, zone))
    }

    @Test
    fun noWindowMeansAllDay() {
        assertTrue(MicrosoundWindowCalculator.isWithinWindow(at(5, 3, 0), null, zone))
        assertEquals(
            at(5, 3, 0),
            MicrosoundWindowCalculator.clipToWindow(at(5, 3, 0), null, zone)
        )
    }

    // --- clipToWindow --------------------------------------------------------

    @Test
    fun candidateInsideWindowIsKept() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        val candidate = at(5, 12, 0)
        assertEquals(
            candidate,
            MicrosoundWindowCalculator.clipToWindow(candidate, window, zone)
        )
    }

    @Test
    fun candidateExactlyAtStartIsKept() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        val candidate = at(5, 11, 20)
        assertEquals(
            candidate,
            MicrosoundWindowCalculator.clipToWindow(candidate, window, zone)
        )
    }

    @Test
    fun candidateExactlyAtEndMovesToStartOfNextDayWindow() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        val clipped = MicrosoundWindowCalculator.clipToWindow(at(5, 17, 0), window, zone)
        assertEquals(LocalDateTime.of(2026, 3, 6, 11, 20), local(clipped))
    }

    @Test
    fun candidateBeforeStartMovesToTodayWindowStart() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        val clipped = MicrosoundWindowCalculator.clipToWindow(at(5, 8, 0), window, zone)
        assertEquals(LocalDateTime.of(2026, 3, 5, 11, 20), local(clipped))
    }

    @Test
    fun candidateAfterEndMovesToNextDayWindowStartChangeOfDay() {
        val window = MicrosoundWindow(startMinuteOfDay = 11 * 60 + 20, endMinuteOfDay = 17 * 60)
        val clipped = MicrosoundWindowCalculator.clipToWindow(at(5, 23, 30), window, zone)
        assertEquals(LocalDateTime.of(2026, 3, 6, 11, 20), local(clipped))
    }

    @Test
    fun candidateBeforeCrossMidnightWindowMovesToEveningOfSameDay() {
        val window = MicrosoundWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        val clipped = MicrosoundWindowCalculator.clipToWindow(at(5, 12, 0), window, zone)
        assertEquals(LocalDateTime.of(2026, 3, 5, 22, 0), local(clipped))
    }

    @Test
    fun candidateAfterCrossMidnightWindowEndMovesToNextEvening() {
        // 06:00 — уже НЕ окно (конец не включается), ближайшее начало — вечер
        // этих же суток, а не следующие.
        val window = MicrosoundWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        val clipped = MicrosoundWindowCalculator.clipToWindow(at(5, 6, 0), window, zone)
        assertEquals(LocalDateTime.of(2026, 3, 5, 22, 0), local(clipped))
    }

    @Test
    fun candidateInsideCrossMidnightWindowIsKept() {
        val window = MicrosoundWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        assertEquals(
            at(5, 2, 0),
            MicrosoundWindowCalculator.clipToWindow(at(5, 2, 0), window, zone)
        )
        assertEquals(
            at(5, 23, 0),
            MicrosoundWindowCalculator.clipToWindow(at(5, 23, 0), window, zone)
        )
    }

    @Test
    fun invalidStoredWindowDoesNotChangeCandidate() {
        val broken = MicrosoundWindow(startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60)
        assertTrue(MicrosoundWindowCalculator.isWithinWindow(at(5, 3, 0), broken, zone))
        assertEquals(
            at(5, 3, 0),
            MicrosoundWindowCalculator.clipToWindow(at(5, 3, 0), broken, zone)
        )
    }

    // --- sanitize / хранимые значения ---------------------------------------

    @Test
    fun sanitizeMissingColumnsMeansNoWindow() {
        assertNull(MicrosoundWindow.sanitize(null, null))
        assertNull(MicrosoundWindow.sanitize(600, null))
        assertNull(MicrosoundWindow.sanitize(null, 1200))
    }

    @Test
    fun sanitizeRejectsEqualAndOutOfRangeValues() {
        assertNull(MicrosoundWindow.sanitize(540, 540))
        assertNull(MicrosoundWindow.sanitize(-1, 600))
        assertNull(MicrosoundWindow.sanitize(600, 1440))
    }

    @Test
    fun sanitizeKeepsValidWindowIncludingMidnightCrossing() {
        assertEquals(
            MicrosoundWindow(680, 1020),
            MicrosoundWindow.sanitize(680, 1020)
        )
        assertEquals(
            MicrosoundWindow(22 * 60, 6 * 60),
            MicrosoundWindow.sanitize(22 * 60, 6 * 60)
        )
    }

    @Test
    fun defaultWindowConstantsMatchUiDefaults() {
        assertEquals(9 * 60, MicrosoundWindow.DEFAULT_START_MINUTE_OF_DAY)
        assertEquals(21 * 60, MicrosoundWindow.DEFAULT_END_MINUTE_OF_DAY)
    }

    // --- сущность Room ------------------------------------------------------

    @Test
    fun entityWithoutColumnsHasNoWindow() {
        val entity = MicrosoundEntity(title = "t", uri = "content://a")
        assertNull(entity.window)
        assertNull(entity.windowStartMinutes)
        assertNull(entity.windowEndMinutes)
    }

    @Test
    fun entityWindowComesFromColumns() {
        val entity = MicrosoundEntity(
            title = "t",
            uri = "content://a",
            windowStartMinutes = 11 * 60 + 20,
            windowEndMinutes = 17 * 60
        )
        assertEquals(MicrosoundWindow(680, 1020), entity.window)
    }

    @Test
    fun entityWithBrokenWindowBehavesAsAllDay() {
        val entity = MicrosoundEntity(
            title = "t",
            uri = "content://a",
            windowStartMinutes = 600,
            windowEndMinutes = 600
        )
        assertNull(entity.window)
    }
}
