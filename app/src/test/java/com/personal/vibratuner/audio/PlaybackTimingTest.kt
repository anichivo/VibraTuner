package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.eq.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистые тесты перевода медиавремя <-> реальное время при скорости эквалайзера.
 * Всё, что пользователь задал в минутах/секундах, скоростью НЕ растягивается;
 * сюда попадают только значения, выведенные из длительности/позиции трека.
 */
class PlaybackTimingTest {

    @Test
    fun disabledEqualizer_isAlwaysOneXEvenWithStoredSpeed() {
        val settings = EqualizerSettings(enabled = false, speed = 4.0f)

        assertEquals(1.0f, PlaybackTiming.effectiveSpeed(settings), 0.001f)
        assertEquals(10_000L, PlaybackTiming.wallClockMs(10_000L, settings))
    }

    @Test
    fun enabledEqualizer_usesStoredSpeed() {
        val settings = EqualizerSettings(enabled = true, speed = 2.0f)

        assertEquals(2.0f, PlaybackTiming.effectiveSpeed(settings), 0.001f)
        assertEquals(5_000L, PlaybackTiming.wallClockMs(10_000L, settings))
    }

    @Test
    fun wallClock_isMediaDividedBySpeed() {
        assertEquals(40_000L, PlaybackTiming.wallClockMs(10_000L, 0.25f))
        assertEquals(10_000L, PlaybackTiming.wallClockMs(10_000L, 1.0f))
        assertEquals(5_000L, PlaybackTiming.wallClockMs(10_000L, 2.0f))
        assertEquals(2_500L, PlaybackTiming.wallClockMs(10_000L, 4.0f))
    }

    @Test
    fun wallClock_roundsToWholeMilliseconds() {
        // 1000 / 3 = 333.33...
        assertEquals(333L, PlaybackTiming.wallClockMs(1_000L, 3.0f))
        // 1000 / 0.25 = 4000
        assertEquals(1_000L, PlaybackTiming.wallClockMs(250L, 0.25f))
    }

    @Test
    fun wallClock_handlesNonPositiveMedia() {
        assertEquals(0L, PlaybackTiming.wallClockMs(0L, 2.0f))
        assertEquals(0L, PlaybackTiming.wallClockMs(-500L, 2.0f))
    }

    @Test
    fun invalidSpeed_fallsBackToOneX() {
        assertEquals(1.0f, PlaybackTiming.sanitizeSpeed(0f), 0.001f)
        assertEquals(1.0f, PlaybackTiming.sanitizeSpeed(-2f), 0.001f)
        assertEquals(1.0f, PlaybackTiming.sanitizeSpeed(Float.NaN), 0.001f)
        assertEquals(10_000L, PlaybackTiming.wallClockMs(10_000L, 0f))
    }

    @Test
    fun speedOutsideSpec_isClampedToSpecRange() {
        assertEquals(4.0f, PlaybackTiming.sanitizeSpeed(99f), 0.001f)
        assertEquals(0.25f, PlaybackTiming.sanitizeSpeed(0.01f), 0.001f)
        assertEquals(2_500L, PlaybackTiming.wallClockMs(10_000L, 99f))
        assertEquals(40_000L, PlaybackTiming.wallClockMs(10_000L, 0.01f))
    }

    @Test
    fun preludeLead_growsAtSlowSpeedAndShrinksAtFastSpeed() {
        // Медиадлительность звука «Включение колонки» 12 500 мс.
        val media = 12_500L

        assertEquals(50_000L, PlaybackTiming.wallClockMs(media, 0.25f))
        assertEquals(12_500L, PlaybackTiming.wallClockMs(media, 1.0f))
        assertEquals(3_125L, PlaybackTiming.wallClockMs(media, 4.0f))
    }

    @Test
    fun previousThreshold_usesWallClockNotMediaPosition() {
        // 3 секунды реального звука при 2x — это 6 секунд медиапозиции.
        assertEquals(3_000L, PlaybackTiming.wallClockMs(6_000L, 2.0f))
        assertEquals(3_000L, PlaybackTiming.wallClockMs(750L, 0.25f))
    }
}
