package com.personal.vibratuner.audio.eq

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Чистая (без Android) математика эквалайзера: усиление перегруза, логарифмическая
 * шкала скорости и уровни частотных полос. Центральные частоты полос Android
 * приходят в миллигерцах, уровни — в миллибелах.
 */
object EqualizerMath {

    /** Доля диапазона слайдера (вокруг 1.0x), в пределах которой скорость фиксируется 1.0. */
    const val SLIDER_SNAP_FRACTION = 0.02f

    /**
     * Предохранительный предел `LoudnessEnhancer` — 2000 мБ (+20 дБ).
     * `LoudnessEnhancer.setTargetGain` принимает миллибелы, а очень большое
     * усиление на части прошивок даёт клиппинг или отказ эффекта. Формула
     * укладывается в предел вплоть до 900 %, поэтому максимум 500 % не
     * обрезается, а заведомо опасные значения (например, из будущих миграций
     * или ошибки ввода) никогда не попадут в эффект.
     */
    const val OVERBOOST_MAX_MILLIBELS = 2000

    /**
     * Усиление перегруза в миллибелах: `2000 * log10(1 + p/100)`,
     * с предохранительным пределом [OVERBOOST_MAX_MILLIBELS].
     * Для p <= 0 возвращается 0. +200% = x3 ≈ +9.5 дБ, +500% = x6 ≈ +15.6 дБ
     * (искажения допустимы и ожидаемы).
     */
    fun overboostGainMillibels(percent: Int): Int {
        if (percent <= 0) return 0
        return (2000.0 * log10(1.0 + percent / 100.0))
            .roundToInt()
            .coerceAtMost(OVERBOOST_MAX_MILLIBELS)
    }

    /**
     * Скорость по позиции слайдера `t` в `0..1`: логарифмическая шкала
     * `speed = 4^(2t-1)`. При `t = 0.5` — ровно 1.0x. Значения вблизи центра
     * (в пределах ~2% диапазона) фиксируются на 1.0.
     */
    fun speedFromSlider(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        if (abs(clamped - 0.5f) <= SLIDER_SNAP_FRACTION) return 1.0f
        return 4.0.pow(2.0 * clamped - 1.0).toFloat()
    }

    /**
     * Позиция слайдера `t` по скорости: обратная функция к [speedFromSlider].
     * Значения скорости вблизи 1.0 фиксируются на `t = 0.5` (симметрично снэпу).
     */
    fun sliderFromSpeed(speed: Float): Float {
        val clamped = speed.coerceIn(EqualizerSettings.SPEED_MIN, EqualizerSettings.SPEED_MAX)
        val t = ((log2(clamped.toDouble()) + 2.0) / 4.0).toFloat()
        return if (abs(t - 0.5f) <= SLIDER_SNAP_FRACTION) 0.5f else t
    }

    /**
     * Уровень ОДНОГО регулятора тембра в миллибелах. Процент симметричен:
     * `0` — нейтраль, `+100` — аппаратный максимум [maxLevel], `-100` —
     * аппаратный минимум [minLevel] (поэтому срез ограничен РЕАЛЬНЫМ
     * диапазоном `Equalizer.getBandLevelRange`, а не захардкоженными -15 дБ).
     * Значения за пределами -100..100 зажимаются. Работает и для вырожденного
     * диапазона (0..0): регулятор просто ничего не меняет.
     */
    fun toneOffsetMillibels(percent: Int, minLevel: Int, maxLevel: Int): Double {
        val clamped = percent.coerceIn(
            EqualizerSettings.TONE_MIN_PERCENT,
            EqualizerSettings.TONE_MAX_PERCENT
        )
        return if (clamped >= 0) {
            clamped / 100.0 * maxOf(maxLevel, 0)
        } else {
            clamped / 100.0 * maxOf(-minLevel, 0)
        }
    }

    /**
     * Уровни частотных полос в миллибелах для [bandCenterFreqsMilliHz].
     *
     * Бас — низкочастотная полка: полный уровень до ~250 Гц, линейный спад до 0
     * к ~1 кГц; ВЧ — высокочастотная полка: 0 до ~1 кГц, полный уровень от
     * ~4 кГц. Середина не затрагивается. Знак [bassPercent]/[treblePercent]
     * задаёт направление: отрицательные значения СРЕЗАЮТ полку, положительные
     * поднимают. Вклады суммируются и каждый уровень зажимается в
     * [minLevel]..[maxLevel] — в API никогда не уходит значение вне диапазона
     * устройства. Работает для любого числа полос (0, 1, 3, 5, 10).
     */
    fun bandLevelsMillibels(
        bassPercent: Int,
        treblePercent: Int,
        bandCenterFreqsMilliHz: IntArray,
        minLevel: Int,
        maxLevel: Int
    ): IntArray {
        if (bandCenterFreqsMilliHz.isEmpty()) return IntArray(0)
        val bassOffset = toneOffsetMillibels(bassPercent, minLevel, maxLevel)
        val trebleOffset = toneOffsetMillibels(treblePercent, minLevel, maxLevel)
        val levels = IntArray(bandCenterFreqsMilliHz.size)
        for (i in bandCenterFreqsMilliHz.indices) {
            val freqHz = bandCenterFreqsMilliHz[i] / 1000.0
            val raw = bassOffset * bassCurve(freqHz) + trebleOffset * trebleCurve(freqHz)
            levels[i] = raw.roundToInt().coerceIn(minLevel, maxLevel)
        }
        return levels
    }

    // --- Кривые (линейная интерполяция по частоте в герцах) ---

    private const val BASS_BOOST_FULL_HZ = 250.0
    private const val BASS_BOOST_ZERO_HZ = 1000.0
    private const val TREBLE_ZERO_HZ = 1000.0
    private const val TREBLE_FULL_HZ = 4000.0

    /** 1.0 на 250 Гц и ниже, линейный спад до 0 к 1000 Гц. */
    private fun bassCurve(freqHz: Double): Double = when {
        freqHz <= BASS_BOOST_FULL_HZ -> 1.0
        freqHz >= BASS_BOOST_ZERO_HZ -> 0.0
        else -> (BASS_BOOST_ZERO_HZ - freqHz) / (BASS_BOOST_ZERO_HZ - BASS_BOOST_FULL_HZ)
    }

    /** 0 на 1000 Гц и ниже, линейный подъём до 1.0 к 4000 Гц. */
    private fun trebleCurve(freqHz: Double): Double = when {
        freqHz <= TREBLE_ZERO_HZ -> 0.0
        freqHz >= TREBLE_FULL_HZ -> 1.0
        else -> (freqHz - TREBLE_ZERO_HZ) / (TREBLE_FULL_HZ - TREBLE_ZERO_HZ)
    }
}
