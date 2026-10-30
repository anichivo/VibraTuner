package com.personal.vibratuner.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatterTest {

    @Test
    fun formatsZero() {
        assertEquals("00:00", PlaybackTimeFormatter.format(0L))
    }

    @Test
    fun formatsSeconds() {
        assertEquals("00:59", PlaybackTimeFormatter.format(59_000L))
        assertEquals("00:07", PlaybackTimeFormatter.format(7_400L))
    }

    @Test
    fun formatsMinutes() {
        assertEquals("01:00", PlaybackTimeFormatter.format(60_000L))
        assertEquals("59:59", PlaybackTimeFormatter.format(3_599_000L))
    }

    @Test
    fun formatsMoreThanOneHourAsMinutes() {
        assertEquals("60:00", PlaybackTimeFormatter.format(3_600_000L))
        assertEquals("65:03", PlaybackTimeFormatter.format(3_903_000L))
    }

    @Test
    fun negativePositionIsClampedToZero() {
        assertEquals("00:00", PlaybackTimeFormatter.format(-5_000L))
    }
}
