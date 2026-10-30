package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerMath
import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Чистые правила слайдеров эквалайзера: риски (ticks) и привязка к круглым
 * значениям при протяжке (snap-to-tick). Без Android, поэтому покрыты
 * JVM-тестами `EqualizerSlidersTest`.
 *
 * Привязка срабатывает ТОЛЬКО рядом с риской: если значение дальше
 * [SNAP_THRESHOLD_FRACTION] диапазона, слайдер остаётся непрерывным. Это
 * сохраняет плавную протяжку между рисками и даёт «магнит» на круглых числах.
 */
object SliderSnap {

    /** Порог привязки — 2,5 % диапазона слайдера. */
    const val SNAP_THRESHOLD_FRACTION = 0.025f

    /**
     * Равномерные риски от начала диапазона до конца с шагом [step].
     * Для 0..100 с шагом 20 — [0, 20, 40, 60, 80, 100] (6 рисок).
     */
    fun evenlySpacedTicks(
        range: ClosedFloatingPointRange<Float>,
        step: Float
    ): List<Float> {
        if (step <= 0f || range.endInclusive <= range.start) return listOf(range.start)
        val count = ((range.endInclusive - range.start) / step).roundToInt()
        return (0..count)
            .map { index -> (range.start + index * step).coerceAtMost(range.endInclusive) }
            .distinct()
    }

    /** Позиции рисок в долях дорожки 0..1 — для рисования. */
    fun fractions(
        ticks: List<Float>,
        range: ClosedFloatingPointRange<Float>
    ): List<Float> {
        val span = range.endInclusive - range.start
        if (span <= 0f) return emptyList()
        return ticks.map { tick -> ((tick - range.start) / span).coerceIn(0f, 1f) }
    }

    /**
     * Притягивает [value] к ближайшей риске, если расстояние до неё не больше
     * [thresholdFraction] диапазона; иначе возвращает значение без изменений.
     */
    fun snapToTicks(
        value: Float,
        ticks: List<Float>,
        range: ClosedFloatingPointRange<Float>,
        thresholdFraction: Float = SNAP_THRESHOLD_FRACTION
    ): Float {
        val clamped = value.coerceIn(range.start, range.endInclusive)
        if (ticks.isEmpty()) return clamped
        val nearest = ticks.minByOrNull { tick -> abs(tick - clamped) } ?: return clamped
        val threshold = (range.endInclusive - range.start) * thresholdFraction
        return if (abs(nearest - clamped) <= threshold) nearest else clamped
    }
}

/**
 * Описание слайдера: допустимый диапазон и круглые значения-риски.
 * Позиции рисок заранее пересчитаны в доли дорожки.
 */
data class EqualizerSliderSpec(
    val range: ClosedFloatingPointRange<Float>,
    val ticks: List<Float>
) {
    val tickFractions: List<Float> = SliderSnap.fractions(ticks, range)

    /** Значение с привязкой к рискам — единственный путь из UI в настройки. */
    fun snap(value: Float): Float = SliderSnap.snapToTicks(value, ticks, range)
}

/**
 * Слайдеры экрана эквалайзера. Диапазоны берутся из [EqualizerSettings],
 * чтобы пределы жили в одном месте; шаг рисок — круглые значения по смыслу
 * регулятора (проценты баса/ВЧ, сотни процентов усиления, скорости).
 */
object EqualizerSliders {

    /** Круглые скорости, к которым притягивается логарифмический слайдер. */
    val SPEED_VALUES: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)

    /** Шаг рисок баса/ВЧ: -100 .. 100 % (риски через 20 %, центр — 0). */
    const val TONE_TICK_STEP_PERCENT = 20f

    /** Шаг рисок усиления: 0, 100, 200, 300, 400, 500 %. */
    const val OVERBOOST_TICK_STEP_PERCENT = 100f

    /**
     * Симметричный диапазон тембра: 0 — нейтраль в центре, отрицательная
     * половина — срез до аппаратного минимума, положительная — подъём до
     * аппаратного максимума полос устройства.
     */
    private val toneRange =
        EqualizerSettings.TONE_MIN_PERCENT.toFloat()..EqualizerSettings.TONE_MAX_PERCENT.toFloat()

    private val overboostRange =
        EqualizerSettings.OVERBOOST_MIN_PERCENT.toFloat()..EqualizerSettings.OVERBOOST_MAX_PERCENT.toFloat()

    val BASS = EqualizerSliderSpec(
        range = toneRange,
        ticks = SliderSnap.evenlySpacedTicks(toneRange, TONE_TICK_STEP_PERCENT)
    )

    val TREBLE = EqualizerSliderSpec(
        range = toneRange,
        ticks = SliderSnap.evenlySpacedTicks(toneRange, TONE_TICK_STEP_PERCENT)
    )

    val OVERBOOST = EqualizerSliderSpec(
        range = overboostRange,
        ticks = SliderSnap.evenlySpacedTicks(overboostRange, OVERBOOST_TICK_STEP_PERCENT)
    )

    /** Слайдер скорости живёт в позиции 0..1 логарифмической шкалы EqualizerMath. */
    val SPEED = EqualizerSliderSpec(
        range = 0f..1f,
        ticks = SPEED_VALUES.map { speed -> EqualizerMath.sliderFromSpeed(speed) }
    )
}
