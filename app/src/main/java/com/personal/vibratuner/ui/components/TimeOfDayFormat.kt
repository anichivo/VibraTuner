package com.personal.vibratuner.ui.components

import java.util.Locale

/** Минут в сутках: переход через полночь считается по этому модулю. */
const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * Момент окончания сигнала: время старта (в минутах от полуночи) + длительность
 * в минутах с переходом через полночь. Ровно 24 часа дают то же время, что и
 * старт (сутки — полный круг). Чистая функция, покрыта юнит-тестами
 * (`TimeOfDayFormatTest`).
 */
fun endTimeMinutesOfDay(startMinuteOfDay: Int, durationMinutes: Int): Int {
    val start = normalizeMinuteOfDay(startMinuteOfDay)
    return (start + durationMinutes.coerceAtLeast(0)) % MINUTES_PER_DAY
}

/**
 * Время суток в 24-часовом формате «HH:mm» — том же, в котором показывается
 * время старта будильника в расписании и в диалоге выбора времени.
 * Чистая функция: локаль передаётся параметром (по умолчанию — системная).
 */
fun formatTimeOfDay(minuteOfDay: Int, locale: Locale = Locale.getDefault()): String {
    val normalized = normalizeMinuteOfDay(minuteOfDay)
    return String.format(locale, "%02d:%02d", normalized / 60, normalized % 60)
}

/** Приводит минуты от полуночи к диапазону 0..1439 (в т.ч. отрицательные). */
private fun normalizeMinuteOfDay(minuteOfDay: Int): Int {
    val normalized = minuteOfDay % MINUTES_PER_DAY
    return if (normalized < 0) normalized + MINUTES_PER_DAY else normalized
}
