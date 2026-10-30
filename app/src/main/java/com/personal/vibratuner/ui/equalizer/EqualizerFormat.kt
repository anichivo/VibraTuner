package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerMath
import com.personal.vibratuner.audio.eq.EqualizerSettings
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Подписи значений регуляторов эквалайзера — чистые функции без Android,
 * поэтому покрыты JVM-тестами (`EqualizerFormatTest`).
 *
 * «%», «+» и «x» здесь — символы форматирования значения, а не переводимый
 * текст (как двоеточие в `formatTimeOfDay`): слова интерфейса живут в
 * `strings.xml`. Единица дБ тоже приходит из `strings.xml` параметром
 * [tone] и НИКОГДА не пишется здесь литералом.
 */
object EqualizerFormat {

    /**
     * «0 дБ», «+6 дБ», «-8 дБ» — уровень баса/ВЧ. Процент симметричен
     * (-100..100), а дБ считаются от РЕАЛЬНОГО диапазона полос устройства
     * [bandRangeMillibels], поэтому подпись честная на любом телефоне.
     */
    fun tone(percent: Int, bandRangeMillibels: IntRange, unit: String): String {
        val decibels = toneDecibels(percent, bandRangeMillibels)
        val sign = if (decibels > 0) "+" else ""
        return "$sign$decibels $unit"
    }

    /**
     * Уровень одного регулятора тембра в децибелах (округление до целого).
     * Маппинг процентов в миллибелы — ТОЛЬКО
     * [EqualizerMath.toneOffsetMillibels], чтобы экран и движок считали
     * одинаково.
     */
    fun toneDecibels(percent: Int, bandRangeMillibels: IntRange): Int =
        (EqualizerMath.toneOffsetMillibels(
            percent = percent,
            minLevel = bandRangeMillibels.first,
            maxLevel = bandRangeMillibels.last
        ) / 100.0).roundToInt()

    /** «+120%» — дополнительное усиление громкости (0..500%). */
    fun overboost(percent: Int): String {
        val safe = percent.coerceIn(
            EqualizerSettings.OVERBOOST_MIN_PERCENT,
            EqualizerSettings.OVERBOOST_MAX_PERCENT
        )
        return "+$safe%"
    }

    /**
     * «x1», «x1,5», «x0,25», «x4» — множитель скорости с десятичной запятой
     * для русской локали (дробная часть — не больше двух знаков, нули
     * отбрасываются).
     */
    fun speed(speed: Float, locale: Locale = Locale.getDefault()): String {
        val safe = when {
            speed.isNaN() -> EqualizerSettings.DEFAULT_SPEED
            else -> speed.coerceIn(EqualizerSettings.SPEED_MIN, EqualizerSettings.SPEED_MAX)
        }
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val format = DecimalFormat("0.##", symbols)
        return "x${format.format(safe.toDouble())}"
    }
}
