package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одинаковая проверка интервалов для обычных микрозвуков и фейк-включения колонки.
 */
class MicrosoundIntervalRuleTest {

    @Test
    fun validBoundsAreAccepted() {
        assertTrue(MicrosoundIntervalRule.isValid(1, 1))
        assertTrue(MicrosoundIntervalRule.isValid(60, 120))
        assertTrue(MicrosoundIntervalRule.isValid(10_080, 10_080))
    }

    @Test
    fun nonPositiveBoundsAreRejected() {
        assertFalse(MicrosoundIntervalRule.isValid(0, 120))
        assertFalse(MicrosoundIntervalRule.isValid(60, 0))
        assertFalse(MicrosoundIntervalRule.isValid(-1, 120))
        assertFalse(MicrosoundIntervalRule.isValid(60, -5))
    }

    @Test
    fun minGreaterThanMaxIsRejected() {
        assertFalse(MicrosoundIntervalRule.isValid(120, 60))
    }

    @Test
    fun boundsAboveLimitAreRejected() {
        assertFalse(MicrosoundIntervalRule.isValid(10_081, 10_081))
        assertFalse(MicrosoundIntervalRule.isValid(60, 10_081))
    }

    @Test
    fun sanitizeKeepsValidPair() {
        assertEquals(60 to 120, MicrosoundIntervalRule.sanitize(60, 120))
        assertEquals(1 to 10_080, MicrosoundIntervalRule.sanitize(1, 10_080))
    }

    @Test
    fun sanitizeClampsSingleBrokenValue() {
        assertEquals(1 to 120, MicrosoundIntervalRule.sanitize(0, 120))
        assertEquals(60 to 10_080, MicrosoundIntervalRule.sanitize(60, 50_000))
    }

    @Test
    fun sanitizeReplacesInvertedPairWithDefaults() {
        assertEquals(
            MicrosoundIntervalRule.DEFAULT_MIN_INTERVAL_MINUTES to
                MicrosoundIntervalRule.DEFAULT_MAX_INTERVAL_MINUTES,
            MicrosoundIntervalRule.sanitize(120, 60)
        )
    }
}
