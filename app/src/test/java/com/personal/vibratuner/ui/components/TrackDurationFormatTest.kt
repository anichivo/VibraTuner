package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Длительность ТРЕКА (мс) для строк списков и мини-плеера: секунды
 * отбрасываются, трек короче минуты — «1 мин», целые часы — «1 час»/
 * «2 часа»/«21 час»/«22 часа», часы и минуты — «1 час 3 мин».
 */
class TrackDurationFormatTest {

    @Test
    fun zeroAndNegativeMeanUnknown() {
        assertNull(trackDurationLabel(0L))
        assertNull(trackDurationLabel(-1L))
        assertNull(trackDurationLabel(-60_000L))
    }

    @Test
    fun aTrackShorterThanAMinuteStillShowsOneMinute() {
        assertEquals(TrackDurationLabel.Minutes(1), trackDurationLabel(1L))
        assertEquals(TrackDurationLabel.Minutes(1), trackDurationLabel(59_000L))
        assertEquals(TrackDurationLabel.Minutes(1), trackDurationLabel(59_999L))
        assertEquals(TrackDurationLabel.Minutes(1), trackDurationLabel(60_000L))
    }

    @Test
    fun secondsAreFlooredNotRounded() {
        // 3599 секунд = 59 минут 59 секунд -> «59 мин», а не «60 мин».
        assertEquals(TrackDurationLabel.Minutes(59), trackDurationLabel(3_599_000L))
        assertEquals(TrackDurationLabel.HoursMinutes(1, 59), trackDurationLabel(7_199_999L))
    }

    @Test
    fun exactHoursUseOnlyTheHourForm() {
        assertEquals(TrackDurationLabel.Hours(1), trackDurationLabel(3_600_000L))
        assertEquals(TrackDurationLabel.Hours(21), trackDurationLabel(75_600_000L))
        assertEquals(TrackDurationLabel.Hours(22), trackDurationLabel(79_200_000L))
    }

    @Test
    fun hoursAndMinutesKeepBothParts() {
        assertEquals(TrackDurationLabel.HoursMinutes(1, 1), trackDurationLabel(3_660_000L))
        assertEquals(TrackDurationLabel.HoursMinutes(2, 48), trackDurationLabel(10_080_000L))
    }
}
