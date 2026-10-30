package com.personal.vibratuner.audio.eq

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Последний ИЗМЕРЕННЫЙ диапазон уровней полос устройства в миллибелах — то,
 * что вернул `Equalizer.getBandLevelRange()`. Публикуется ЕДИНСТВЕННЫМ
 * владельцем эффектов ([EqualizerEngine]) и читается экраном только для
 * отображения (подпись в дБ и границы симметричного регулятора).
 *
 * Сами уровни в эффект по-прежнему передаёт движок, зажимая их в этот же
 * диапазон. Здесь нет ни воспроизведения, ни второго источника настроек:
 * это только пассивная информация о возможностях устройства.
 *
 * До первого запуска/измерения действует [DEFAULT] — типичный диапазон
 * Android ±15 дБ. Он НЕ ограничивает срез: проценты отсчитываются от реального
 * диапазона устройства, как только движок его увидел.
 */
object EqualizerBandRange {

    /** Типичный диапазон Android (±1500 мБ = ±15 дБ) до первого измерения. */
    val DEFAULT: IntRange = -1500..1500

    private val _millibels = MutableStateFlow(DEFAULT)

    /** Живой диапазон полос устройства в миллибелах (min..max). */
    val millibels: StateFlow<IntRange> = _millibels.asStateFlow()

    /**
     * Сохраняет измеренный диапазон; вырожденный/некорректный (пустой или
     * `first >= last`) игнорируется, чтобы подписи не сломались.
     */
    fun publish(measured: IntRange) {
        if (measured.last <= measured.first) return
        _millibels.value = measured
    }
}
