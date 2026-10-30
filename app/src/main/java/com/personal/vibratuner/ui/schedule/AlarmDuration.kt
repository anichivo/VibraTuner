package com.personal.vibratuner.ui.schedule

/**
 * Длительность сигнала будильника: в БД и в `AlarmEditResult` она по-прежнему
 * хранится в МИНУТАХ (схема Room не менялась), а в редакторе вводится двумя
 * колёсами: часы 0..24 и минуты 0..59 (Part 2).
 *
 * Допустимый диапазон — от 1 минуты до 24 часов включительно (1..1440 минут).
 * При 24 часах колесо минут принудительно стоит на 0 и заблокировано, а сумма
 * 0 ч 0 мин зажимается до 1 минуты. Благодаря этому любое подтверждённое
 * значение корректно и ошибка диапазона в UI не возникает. Значения вне
 * диапазона из старых данных для показа зажимаются.
 *
 * Все правила — чистые функции без Android-ресурсов, покрыты
 * `AlarmDurationTest`.
 */
object AlarmDuration {

    const val MIN_MINUTES: Int = 1
    const val MAX_HOURS: Int = 24
    const val MAX_MINUTES: Int = MAX_HOURS * 60
    const val MINUTES_PER_HOUR: Int = 60

    /** Значение по умолчанию для нового будильника — 30 минут. */
    const val DEFAULT_MINUTES: Int = 30

    /** Зажимает сохранённые минуты в допустимый диапазон (для показа в UI). */
    fun clampStoredMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** Часы для колеса длительности (0..24) по сохранённым минутам. */
    fun wheelHours(minutes: Int): Int =
        clampStoredMinutes(minutes) / MINUTES_PER_HOUR

    /** Минуты для колеса (0..59) по сохранённым минутам; у 24 часов всегда 0. */
    fun wheelMinutes(minutes: Int): Int =
        clampStoredMinutes(minutes) % MINUTES_PER_HOUR

    /** При 24 часах колесо минут заблокировано на 0. */
    fun isMinuteWheelDisabled(hours: Int): Boolean = hours >= MAX_HOURS

    /**
     * Длительность в минутах по выбору колёс. Часы 24 всегда дают 0 минут;
     * итог 0 ч 0 мин зажимается до [MIN_MINUTES] (1 минута), сверху — 1440.
     */
    fun fromWheels(hours: Int, minutes: Int): Int {
        val safeHours = hours.coerceIn(0, MAX_HOURS)
        val safeMinutes = if (safeHours == MAX_HOURS) 0 else minutes.coerceIn(0, 59)
        return (safeHours * MINUTES_PER_HOUR + safeMinutes)
            .coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    /** Допустима ли сохранённая длительность (защитная проверка перед сохранением). */
    fun isValidMinutes(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES
}
