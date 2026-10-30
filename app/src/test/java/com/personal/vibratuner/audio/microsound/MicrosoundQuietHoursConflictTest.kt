package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Тесты доминирования «Часов тишины» над собственным окном микрозвука (этап 4).
 * Сценарии из задания + границы (касание концов, полночь, идентичные интервалы).
 */
class MicrosoundQuietHoursConflictTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val date: LocalDate = LocalDate.of(2026, 1, 15)

    private fun at(hour: Int, minute: Int, dayOffset: Long = 0L): Long =
        date.plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun minutes(hour: Int, minute: Int): Int = hour * 60 + minute

    private fun quiet(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): QuietHoursSettings =
        QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(startHour, startMinute),
            endMinuteOfDay = minutes(endHour, endMinute)
        )

    private fun window(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): MicrosoundWindow =
        MicrosoundWindow(
            startMinuteOfDay = minutes(startHour, startMinute),
            endMinuteOfDay = minutes(endHour, endMinute)
        )

    private fun conflict(
        quietHours: QuietHoursSettings,
        window: MicrosoundWindow?
    ): MicrosoundQuietHoursConflict =
        MicrosoundQuietHoursConflictCalculator.conflict(quietHours, window)

    // --- Сценарии из задания -------------------------------------------------

    @Test
    fun windowInsideQuietHours_isFull() {
        // Тишина 21:00–10:00, окно 23:15–04:00 целиком внутри неё.
        assertEquals(
            MicrosoundQuietHoursConflict.FULL,
            conflict(
                quiet(21, 0, 10, 0),
                window(23, 15, 4, 0)
            )
        )
    }

    @Test
    fun windowPartlyOverlappingQuietHours_isPartial() {
        // Окно 20:00–08:00: свободно только 20:00–21:00.
        assertEquals(
            MicrosoundQuietHoursConflict.PARTIAL,
            conflict(
                quiet(21, 0, 10, 0),
                window(20, 0, 8, 0)
            )
        )
    }

    @Test
    fun windowCompletelyOutsideQuietHours_isNone() {
        // 10:00–21:00 — ровно дополнение к тишине 21:00–10:00.
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(
                quiet(21, 0, 10, 0),
                window(10, 0, 21, 0)
            )
        )
    }

    @Test
    fun identicalIntervals_areFull() {
        assertEquals(
            MicrosoundQuietHoursConflict.FULL,
            conflict(
                quiet(21, 0, 10, 0),
                window(21, 0, 10, 0)
            )
        )
    }

    @Test
    fun morningWindowPartlyInsideQuietHours_isPartial() {
        // 09:00–11:00: 09:00–10:00 ещё тишина, 10:00–11:00 свободно.
        assertEquals(
            MicrosoundQuietHoursConflict.PARTIAL,
            conflict(
                quiet(21, 0, 10, 0),
                window(9, 0, 11, 0)
            )
        )
    }

    @Test
    fun windowStartingExactlyAtQuietEnd_isNone() {
        // Касание границ не является пересечением: 10:00 уже не тишина.
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(
                quiet(21, 0, 10, 0),
                window(10, 0, 12, 0)
            )
        )
    }

    @Test
    fun disabledQuietHours_isNone() {
        val disabled = quiet(21, 0, 10, 0).copy(enabled = false)
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(disabled, window(23, 15, 4, 0))
        )
    }

    @Test
    fun noWindow_isNone() {
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(quiet(21, 0, 10, 0), null)
        )
    }

    // --- Дополнительные границы ---------------------------------------------

    @Test
    fun invalidQuietHours_areTreatedAsDisabled() {
        val invalid = QuietHoursSettings(
            enabled = true,
            startMinuteOfDay = minutes(10, 0),
            endMinuteOfDay = minutes(10, 0)
        )
        assertEquals(MicrosoundQuietHoursConflict.NONE, conflict(invalid, window(9, 0, 11, 0)))
    }

    @Test
    fun invalidWindow_isTreatedAsAllDay() {
        val invalid = window(10, 0, 10, 0)
        assertEquals(MicrosoundQuietHoursConflict.NONE, conflict(quiet(21, 0, 10, 0), invalid))
    }

    @Test
    fun normalQuietHoursAndCrossingWindow_isPartial() {
        // Тишина 22:00–23:00, окно 21:00–06:00 с переходом через полночь:
        // пересекаются 22:00–23:00, остальное окно свободно.
        assertEquals(
            MicrosoundQuietHoursConflict.PARTIAL,
            conflict(quiet(22, 0, 23, 0), window(21, 0, 6, 0))
        )
    }

    @Test
    fun normalQuietHoursAndCrossingWindowEndingAtQuietStart_isNone() {
        // Тишина 13:00–14:00, окно 06:00–13:00 заканчивается ровно там,
        // где начинается тишина, поэтому пересечения нет.
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(quiet(13, 0, 14, 0), window(6, 0, 13, 0))
        )
    }

    @Test
    fun touchingEndsInsideCrossingIntervals_areNotOverlap() {
        // Тишина 22:00–06:00, окно 06:00–22:00 — полное дополнение.
        assertEquals(
            MicrosoundQuietHoursConflict.NONE,
            conflict(quiet(22, 0, 6, 0), window(6, 0, 22, 0))
        )
    }

    @Test
    fun symmetricPartialOverlap_isDetected() {
        // Окно шире и «заезжает» в тишину с двух сторон.
        assertEquals(
            MicrosoundQuietHoursConflict.PARTIAL,
            conflict(quiet(22, 0, 6, 0), window(5, 0, 23, 0))
        )
    }

    // --- Ближайший момент, когда микрозвук может играть ----------------------

    @Test
    fun fullConflict_hasNoPlayableMoment() {
        assertNull(
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = at(23, 15),
                quietHours = quiet(21, 0, 10, 0),
                window = window(23, 15, 4, 0),
                zoneId = zone
            )
        )
    }

    @Test
    fun partialConflict_eveningCandidate_movesToNextWindowStart() {
        // Окно 20:00–08:00, тишина 21:00–10:00: ближайшая свободная точка — 20:00.
        assertEquals(
            at(20, 0, dayOffset = 1L),
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = at(23, 0),
                quietHours = quiet(21, 0, 10, 0),
                window = window(20, 0, 8, 0),
                zoneId = zone
            )
        )
    }

    @Test
    fun partialConflict_nightCandidate_movesToSameDayWindowStart() {
        assertEquals(
            at(20, 0),
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = at(3, 0),
                quietHours = quiet(21, 0, 10, 0),
                window = window(20, 0, 8, 0),
                zoneId = zone
            )
        )
    }

    @Test
    fun candidateInsideWindowAndOutsideQuietHours_isUnchanged() {
        assertEquals(
            at(11, 0),
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = at(11, 0),
                quietHours = quiet(21, 0, 10, 0),
                window = window(10, 0, 21, 0),
                zoneId = zone
            )
        )
    }

    @Test
    fun noWindow_candidateInQuietHours_movesToQuietEnd() {
        assertEquals(
            at(10, 0, dayOffset = 1L),
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = at(23, 0),
                quietHours = quiet(21, 0, 10, 0),
                window = null,
                zoneId = zone
            )
        )
    }

    @Test
    fun disabledQuietHours_candidateIsUnchanged() {
        // 23:30 уже внутри окна 23:15–04:00, поэтому обрезка ничего не меняет.
        val candidate = at(23, 30)
        assertEquals(
            candidate,
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = candidate,
                quietHours = quiet(21, 0, 10, 0).copy(enabled = false),
                window = window(23, 15, 4, 0),
                zoneId = zone
            )
        )
    }
}
