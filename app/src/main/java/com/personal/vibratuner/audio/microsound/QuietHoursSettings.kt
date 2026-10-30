package com.personal.vibratuner.audio.microsound

/**
 * Глобальные «Часы тишины»: в указанном интервале обычные микрозвуки не играют.
 * Время хранится как минуты от полуночи (0..1439), что упрощает переход через полночь.
 */
data class QuietHoursSettings(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE_OF_DAY,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE_OF_DAY
) {
    val startHour: Int get() = startMinuteOfDay / 60
    val startMinute: Int get() = startMinuteOfDay % 60
    val endHour: Int get() = endMinuteOfDay / 60
    val endMinute: Int get() = endMinuteOfDay % 60

    /** Начало и конец не могут совпадать: такой интервал не имеет смысла. */
    val isValid: Boolean get() = startMinuteOfDay != endMinuteOfDay

    companion object {
        const val DEFAULT_START_MINUTE_OF_DAY = 23 * 60
        const val DEFAULT_END_MINUTE_OF_DAY = 7 * 60
    }
}
