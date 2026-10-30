package com.personal.vibratuner.audio.eq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqualizerMathTest {

    // --- Усиление перегруза (overboost) ---

    @Test
    fun overboostGain_isZeroAtZeroAndNegative() {
        assertEquals(0, EqualizerMath.overboostGainMillibels(0))
        assertEquals(0, EqualizerMath.overboostGainMillibels(-10))
    }

    @Test
    fun overboostGain_atHundredAndTwoHundred() {
        assertEquals(602, EqualizerMath.overboostGainMillibels(100))
        assertEquals(954, EqualizerMath.overboostGainMillibels(200))
    }

    @Test
    fun overboostGain_atNewFiveHundredMaximum() {
        // 2000 * log10(6) = 1556 мБ ≈ +15.6 дБ.
        assertEquals(1556, EqualizerMath.overboostGainMillibels(500))
        assertTrue(
            "Максимум 500% остаётся ниже предохранительного предела",
            EqualizerMath.overboostGainMillibels(500) < EqualizerMath.OVERBOOST_MAX_MILLIBELS
        )
    }

    @Test
    fun overboostGain_isClampedToSafeLoudnessEnhancerLimit() {
        assertEquals(
            EqualizerMath.OVERBOOST_MAX_MILLIBELS,
            EqualizerMath.overboostGainMillibels(100_000)
        )
        assertEquals(
            EqualizerMath.OVERBOOST_MAX_MILLIBELS,
            EqualizerMath.overboostGainMillibels(Int.MAX_VALUE)
        )
    }

    @Test
    fun overboostGain_isMonotonic() {
        var previous = EqualizerMath.overboostGainMillibels(1)
        for (p in 2..500) {
            val current = EqualizerMath.overboostGainMillibels(p)
            assertTrue("gain($p)=$current < gain(${p - 1})=$previous", current >= previous)
            previous = current
        }
    }

    // --- Шкала скорости (slider <-> speed) ---

    @Test
    fun speedFromSlider_endpoints() {
        assertEquals(0.25f, EqualizerMath.speedFromSlider(0f), 0.001f)
        assertEquals(4.0f, EqualizerMath.speedFromSlider(1f), 0.001f)
    }

    @Test
    fun speedFromSlider_middleIsExactlyOne() {
        assertEquals(1.0f, EqualizerMath.speedFromSlider(0.5f), 0.0f)
    }

    @Test
    fun speedFromSlider_snapsToOneNearMiddle() {
        assertEquals(1.0f, EqualizerMath.speedFromSlider(0.49f), 0.0f)
        assertEquals(1.0f, EqualizerMath.speedFromSlider(0.51f), 0.0f)
    }

    @Test
    fun speedFromSlider_doesNotSnapOutsideBand() {
        val speed = EqualizerMath.speedFromSlider(0.47f)
        assertTrue(abs(speed - 1.0f) > 0.01f)
        assertEquals(0.920f, speed, 0.01f)
    }

    @Test
    fun sliderFromSpeed_endpointsAndMiddle() {
        assertEquals(0.0f, EqualizerMath.sliderFromSpeed(0.25f), 0.001f)
        assertEquals(1.0f, EqualizerMath.sliderFromSpeed(4.0f), 0.001f)
        assertEquals(0.5f, EqualizerMath.sliderFromSpeed(1.0f), 0.001f)
    }

    @Test
    fun sliderAndSpeed_roundTrip() {
        val samples = listOf(0.0f, 0.1f, 0.2f, 0.3f, 0.7f, 0.8f, 0.9f, 1.0f)
        for (t in samples) {
            val roundTripped = EqualizerMath.sliderFromSpeed(EqualizerMath.speedFromSlider(t))
            assertEquals(t, roundTripped, 0.001f)
        }
    }

    // --- Уровни частотных полос (симметричный тембр) ---

    private val fiveBand = intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)

    @Test
    fun bandLevels_fiveBandBassOnlyFull() {
        assertArrayEquals(
            intArrayOf(1500, 1500, 180, 0, 0),
            EqualizerMath.bandLevelsMillibels(100, 0, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandBassCutFull() {
        assertArrayEquals(
            intArrayOf(-1500, -1500, -180, 0, 0),
            EqualizerMath.bandLevelsMillibels(-100, 0, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandTrebleOnlyFull() {
        assertArrayEquals(
            intArrayOf(0, 0, 0, 1300, 1500),
            EqualizerMath.bandLevelsMillibels(0, 100, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandTrebleCutFull() {
        assertArrayEquals(
            intArrayOf(0, 0, 0, -1300, -1500),
            EqualizerMath.bandLevelsMillibels(0, -100, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandBassAndTrebleFull() {
        assertArrayEquals(
            intArrayOf(1500, 1500, 180, 1300, 1500),
            EqualizerMath.bandLevelsMillibels(100, 100, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandBassCutAndTrebleCutFull() {
        assertArrayEquals(
            intArrayOf(-1500, -1500, -180, -1300, -1500),
            EqualizerMath.bandLevelsMillibels(-100, -100, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_fiveBandZero() {
        assertArrayEquals(
            intArrayOf(0, 0, 0, 0, 0),
            EqualizerMath.bandLevelsMillibels(0, 0, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_halfValuesAreHalfOfHardwareRange() {
        assertArrayEquals(
            intArrayOf(750, 750, 90, 0, 0),
            EqualizerMath.bandLevelsMillibels(50, 0, fiveBand, -1500, 1500)
        )
        assertArrayEquals(
            intArrayOf(-750, -750, -90, 0, 0),
            EqualizerMath.bandLevelsMillibels(-50, 0, fiveBand, -1500, 1500)
        )
    }

    @Test
    fun bandLevels_useActualAsymmetricHardwareRange() {
        // Телефон с подъёмом +1500 и срезом -1000: полный срез = -1000, не -1500.
        assertArrayEquals(
            intArrayOf(1500),
            EqualizerMath.bandLevelsMillibels(100, 0, intArrayOf(60_000), -1000, 1500)
        )
        assertArrayEquals(
            intArrayOf(-1000),
            EqualizerMath.bandLevelsMillibels(-100, 0, intArrayOf(60_000), -1000, 1500)
        )
        assertArrayEquals(
            intArrayOf(-500),
            EqualizerMath.bandLevelsMillibels(-50, 0, intArrayOf(60_000), -1000, 1500)
        )
    }

    @Test
    fun bandLevels_anyBandCount() {
        assertEquals(
            0,
            EqualizerMath.bandLevelsMillibels(100, 100, IntArray(0), -1500, 1500).size
        )

        assertArrayEquals(
            intArrayOf(1500),
            EqualizerMath.bandLevelsMillibels(100, 0, intArrayOf(100_000), -1500, 1500)
        )

        val threeBand = intArrayOf(100_000, 1_000_000, 10_000_000)
        assertArrayEquals(
            intArrayOf(1500, 0, 0),
            EqualizerMath.bandLevelsMillibels(100, 0, threeBand, -1500, 1500)
        )

        val tenBand = intArrayOf(
            40_000, 100_000, 250_000, 500_000, 1_000_000,
            2_000_000, 4_000_000, 8_000_000, 12_000_000, 16_000_000
        )
        assertEquals(
            10,
            EqualizerMath.bandLevelsMillibels(100, 100, tenBand, -1500, 1500).size
        )
    }

    @Test
    fun bandLevels_clampsToHardwareRange() {
        // Проценты выше 100 зажимаются в 100: срез больше аппаратного минимума
        // не запрашивается и в API не уходит.
        assertArrayEquals(
            intArrayOf(1500),
            EqualizerMath.bandLevelsMillibels(300, 0, intArrayOf(60_000), -1500, 1500)
        )
        assertArrayEquals(
            intArrayOf(-1500),
            EqualizerMath.bandLevelsMillibels(-300, 0, intArrayOf(60_000), -1500, 1500)
        )
        assertArrayEquals(
            intArrayOf(1500),
            EqualizerMath.bandLevelsMillibels(0, 300, intArrayOf(14_000_000), -1500, 1500)
        )
        assertArrayEquals(
            intArrayOf(-1500),
            EqualizerMath.bandLevelsMillibels(0, -300, intArrayOf(14_000_000), -1500, 1500)
        )
    }

    @Test
    fun bandLevels_boostAndCutAreSymmetricAroundNeutral() {
        val freqs = intArrayOf(60_000, 14_000_000)
        for (percent in listOf(20, 40, 60, 80, 100)) {
            val boost = EqualizerMath.bandLevelsMillibels(percent, percent, freqs, -1500, 1500)
            val cut = EqualizerMath.bandLevelsMillibels(-percent, -percent, freqs, -1500, 1500)
            assertEquals("+$percent и -$percent симметричны (60 Гц)", boost[0], -cut[0])
            assertEquals("+$percent и -$percent симметричны (14 кГц)", boost[1], -cut[1])
        }
    }

    @Test
    fun bandLevels_bassGainGrowsMonotonicallyWithPercent() {
        val freqs = intArrayOf(60_000)
        var previous = EqualizerMath.bandLevelsMillibels(0, 0, freqs, -1500, 1500)[0]
        for (p in 1..100) {
            val current = EqualizerMath.bandLevelsMillibels(p, 0, freqs, -1500, 1500)[0]
            assertTrue(current >= previous)
            previous = current
        }
    }

    @Test
    fun bandLevels_trebleBoostGrowsMonotonicallyWithPercent() {
        val freqs = intArrayOf(14_000_000)
        var previous = EqualizerMath.bandLevelsMillibels(0, 0, freqs, -1500, 1500)[0]
        for (p in 1..100) {
            val current = EqualizerMath.bandLevelsMillibels(0, p, freqs, -1500, 1500)[0]
            assertTrue(current >= previous)
            previous = current
        }
    }

    @Test
    fun bandLevels_bassCutDeepensMonotonicallyWithNegativePercent() {
        val freqs = intArrayOf(60_000)
        var previous = EqualizerMath.bandLevelsMillibels(0, 0, freqs, -1500, 1500)[0]
        for (p in 1..100) {
            val current = EqualizerMath.bandLevelsMillibels(-p, 0, freqs, -1500, 1500)[0]
            assertTrue("Срез на -$p% не глубже среза на -${p - 1}%", current <= previous)
            previous = current
        }
        assertEquals("Полный срез достигает аппаратного минимума", -1500, previous)
    }

    // --- Уровень одного регулятора тембра ---

    @Test
    fun toneOffset_mapsPercentToHardwareRange() {
        assertEquals(0.0, EqualizerMath.toneOffsetMillibels(0, -1500, 1500), 0.0)
        assertEquals(1500.0, EqualizerMath.toneOffsetMillibels(100, -1500, 1500), 0.0)
        assertEquals(-1500.0, EqualizerMath.toneOffsetMillibels(-100, -1500, 1500), 0.0)
        assertEquals(750.0, EqualizerMath.toneOffsetMillibels(50, -1500, 1500), 0.0)
        assertEquals(-600.0, EqualizerMath.toneOffsetMillibels(-50, -1200, 1500), 0.0)
    }

    @Test
    fun toneOffset_clampsPercentAndHandlesDegenerateRange() {
        assertEquals(1500.0, EqualizerMath.toneOffsetMillibels(999, -1500, 1500), 0.0)
        assertEquals(-1500.0, EqualizerMath.toneOffsetMillibels(-999, -1500, 1500), 0.0)
        assertEquals(0.0, EqualizerMath.toneOffsetMillibels(100, 0, 0), 0.0)
        assertEquals(0.0, EqualizerMath.toneOffsetMillibels(-100, 0, 0), 0.0)
    }
}
