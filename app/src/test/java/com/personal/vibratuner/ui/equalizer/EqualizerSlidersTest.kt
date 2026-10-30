package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerMath
import com.personal.vibratuner.audio.eq.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая логика рисок и snap-to-tick: без Android и без Compose.
 * Проверяет диапазоны, число/порядок рисок и порог привязки (2,5 % диапазона).
 */
class EqualizerSlidersTest {

    private val ruPercent = 0f..100f

    @Test
    fun evenlySpacedTicks_startEndAndStep() {
        assertEquals(
            listOf(0f, 20f, 40f, 60f, 80f, 100f),
            SliderSnap.evenlySpacedTicks(ruPercent, 20f)
        )
        assertEquals(
            listOf(0f, 100f, 200f, 300f, 400f, 500f),
            SliderSnap.evenlySpacedTicks(0f..500f, 100f)
        )
    }

    @Test
    fun evenlySpacedTicks_areClampedToRangeAndNeverEmpty() {
        assertEquals(listOf(0f, 30f, 60f, 90f), SliderSnap.evenlySpacedTicks(0f..100f, 30f))
        assertEquals(listOf(5f), SliderSnap.evenlySpacedTicks(5f..5f, 10f))
        assertEquals(listOf(5f), SliderSnap.evenlySpacedTicks(5f..10f, 0f))
    }

    @Test
    fun fractions_spanWholeTrackAndStayMonotonic() {
        val fractions = SliderSnap.fractions(listOf(0f, 20f, 100f), ruPercent)
        assertEquals(listOf(0f, 0.2f, 1f), fractions)
        assertTrue("Деления не убывают", fractions.zipWithNext().all { it.first <= it.second })
        assertTrue("Все доли внутри дорожки", fractions.all { it in 0f..1f })
    }

    @Test
    fun snapToTicks_pullsOnlyValuesNearATick() {
        val ticks = SliderSnap.evenlySpacedTicks(ruPercent, 20f)
        // Порог 2,5 % диапазона = 2,5 процентных пункта.
        assertEquals(20f, SliderSnap.snapToTicks(22f, ticks, ruPercent), 0f)
        assertEquals(20f, SliderSnap.snapToTicks(22.5f, ticks, ruPercent), 0f)
        assertEquals("Дальше порога значение не меняется", 23f, SliderSnap.snapToTicks(23f, ticks, ruPercent), 0f)
        assertEquals(40f, SliderSnap.snapToTicks(42f, ticks, ruPercent), 0f)
        assertEquals(100f, SliderSnap.snapToTicks(99f, ticks, ruPercent), 0f)
    }

    @Test
    fun snapToTicks_thresholdScalesWithRangeWidth() {
        val overboost = 0f..500f
        val ticks = SliderSnap.evenlySpacedTicks(overboost, 100f)
        // 2,5 % от 500 = 12,5: 108 -> 100, 120 остаётся как есть.
        assertEquals(100f, SliderSnap.snapToTicks(108f, ticks, overboost), 0f)
        assertEquals(120f, SliderSnap.snapToTicks(120f, ticks, overboost), 0f)
        assertEquals(500f, SliderSnap.snapToTicks(488f, ticks, overboost), 0f)
    }

    @Test
    fun snapToTicks_handlesEmptyTicksAndOutOfRangeValues() {
        assertEquals(42f, SliderSnap.snapToTicks(42f, emptyList(), ruPercent), 0f)
        assertEquals(0f, SliderSnap.snapToTicks(-10f, emptyList(), ruPercent), 0f)
        assertEquals(100f, SliderSnap.snapToTicks(150f, emptyList(), ruPercent), 0f)
    }

    @Test
    fun specsUseSettingsRangesAndFiveToTenTicks() {
        assertEquals(
            EqualizerSettings.TONE_MIN_PERCENT.toFloat()..EqualizerSettings.TONE_MAX_PERCENT.toFloat(),
            EqualizerSliders.BASS.range
        )
        assertEquals(
            EqualizerSettings.TONE_MIN_PERCENT.toFloat()..EqualizerSettings.TONE_MAX_PERCENT.toFloat(),
            EqualizerSliders.TREBLE.range
        )
        assertEquals(
            0f..EqualizerSettings.OVERBOOST_MAX_PERCENT.toFloat(),
            EqualizerSliders.OVERBOOST.range
        )
        assertEquals(0f..1f, EqualizerSliders.SPEED.range)

        listOf(
            EqualizerSliders.BASS,
            EqualizerSliders.TREBLE,
            EqualizerSliders.OVERBOOST,
            EqualizerSliders.SPEED
        ).forEach { spec ->
            assertTrue("Рисок должно быть 5..11, а не ${spec.ticks.size}", spec.ticks.size in 5..11)
            assertEquals(0f, spec.tickFractions.first(), 0.0001f)
            assertEquals(1f, spec.tickFractions.last(), 0.0001f)
            assertTrue(spec.tickFractions.zipWithNext().all { it.first <= it.second })
        }
    }

    @Test
    fun toneSlidersAreSymmetricAroundNeutralZero() {
        val bass = EqualizerSliders.BASS
        assertEquals(
            "Нейтраль 0 стоит ровно в центре дорожки",
            0.5f,
            bass.tickFractions[bass.ticks.indexOf(0f)],
            0.0001f
        )
        assertEquals("Тембр симметричен", -bass.range.endInclusive, bass.range.start, 0f)
        assertEquals("-100 % — полный срез", -100f, bass.range.start, 0f)
        assertEquals("100 % — полный подъём", 100f, bass.range.endInclusive, 0f)
        assertEquals(
            listOf(-100f, -80f, -60f, -40f, -20f, 0f, 20f, 40f, 60f, 80f, 100f),
            bass.ticks
        )
        assertEquals("Снап к нейтрали", 0f, bass.snap(1.5f), 0f)
        assertEquals("Снап к полному срезу", -100f, bass.snap(-99f), 0f)
        assertEquals("Срез не зажимается в ноль", -50f, bass.snap(-50f), 0f)
    }

    @Test
    fun overboostSpecCoversNewFiveHundredMaximum() {
        assertEquals(500f, EqualizerSliders.OVERBOOST.range.endInclusive, 0f)
        assertEquals(500f, EqualizerSliders.OVERBOOST.ticks.last(), 0f)
        assertEquals(300f, EqualizerSliders.OVERBOOST.snap(305f), 0f)
        assertEquals("Снап не ограничивает максимум: 500 достижим", 500f, EqualizerSliders.OVERBOOST.snap(500f), 0f)
    }

    @Test
    fun speedSnapsToRoundValuesOnLogarithmicScale() {
        val spec = EqualizerSliders.SPEED
        val oneX = EqualizerMath.sliderFromSpeed(1.0f)
        assertEquals(oneX, spec.snap(oneX + 0.02f), 0.0001f)
        assertEquals(
            "Дальше порога позиция остаётся непрерывной",
            oneX + 0.04f,
            spec.snap(oneX + 0.04f),
            0.0001f
        )
        assertEquals(0f, spec.snap(0.01f), 0.0001f)
        assertEquals(1f, spec.snap(0.99f), 0.0001f)
        assertEquals(1.0f, EqualizerMath.speedFromSlider(spec.snap(oneX)), 0.0001f)
    }
}
