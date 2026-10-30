package com.personal.vibratuner.audio.microsound

import java.time.Instant
import java.time.ZoneId

/**
 * Собственное окно работы микрозвука: «с HH:mm до HH:mm».
 *
 * Время хранится в минутах от полуночи (0..1439), поэтому переход через полночь
 * выражается обычным сравнением: 22:00–06:00 — это [22:00, 24:00) и [00:00, 06:00).
 * Границы: НАЧАЛО включительно, КОНЕЦ НЕ включается — так же, как у «Часов тишины».
 *
 * Окно НЕОБЯЗАТЕЛЬНО: `null` (у сущности/настроек) означает «весь день», то есть
 * ровно прежнее поведение. Начало и конец не могут совпадать: такое окно
 * бессмысленно и трактуется как отсутствующее (см. [sanitize]).
 */
data class MicrosoundWindow(
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE_OF_DAY,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE_OF_DAY
) {
    val startHour: Int get() = normalizeMinute(startMinuteOfDay) / 60
    val startMinute: Int get() = normalizeMinute(startMinuteOfDay) % 60
    val endHour: Int get() = normalizeMinute(endMinuteOfDay) / 60
    val endMinute: Int get() = normalizeMinute(endMinuteOfDay) % 60

    /** Начало и конец не совпадают (с учётом приведения к суткам). */
    val isValid: Boolean
        get() = normalizeMinute(startMinuteOfDay) != normalizeMinute(endMinuteOfDay)

    /**
     * Попадает ли [minuteOfDay] в окно: начало включительно, конец не включается.
     * Некорректное окно (начало == конец) не ограничивает ничего.
     */
    fun containsMinuteOfDay(minuteOfDay: Int): Boolean {
        if (!isValid) return true
        val current = normalizeMinute(minuteOfDay)
        val start = normalizeMinute(startMinuteOfDay)
        val end = normalizeMinute(endMinuteOfDay)
        return if (start < end) {
            current >= start && current < end
        } else {
            // Переход через полночь: [start, 24:00) или [00:00, end)
            current >= start || current < end
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 1440

        /** Значения по умолчанию при первом включении окна: 09:00–21:00. */
        const val DEFAULT_START_MINUTE_OF_DAY = 9 * 60
        const val DEFAULT_END_MINUTE_OF_DAY = 21 * 60

        /**
         * Окно из хранилища: `null`, если хотя бы одно значение не задано
         * (окно выключено) или значения некорректны (совпадают / вне суток).
         * Битое сохранённое значение НИКОГДА не роняет планировщик: микрозвук
         * просто работает весь день, как до появления окон.
         */
        fun sanitize(startMinuteOfDay: Int?, endMinuteOfDay: Int?): MicrosoundWindow? {
            if (startMinuteOfDay == null || endMinuteOfDay == null) return null
            if (startMinuteOfDay !in 0 until MINUTES_PER_DAY) return null
            if (endMinuteOfDay !in 0 until MINUTES_PER_DAY) return null
            val window = MicrosoundWindow(startMinuteOfDay, endMinuteOfDay)
            return if (window.isValid) window else null
        }

        private fun normalizeMinute(value: Int): Int {
            val remainder = value % MINUTES_PER_DAY
            return if (remainder < 0) remainder + MINUTES_PER_DAY else remainder
        }
    }
}

/**
 * Чистые функции окна микрозвука (без Android-зависимостей, покрыты юнит-тестами):
 * проверка «сейчас внутри окна» и обрезка рассчитанного времени срабатывания.
 *
 * Часовой пояс передаётся параметром (по умолчанию — системный на момент
 * планирования), чтобы результат был воспроизводим в тестах.
 */
object MicrosoundWindowCalculator {

    /**
     * Находится ли момент [epochMs] внутри окна. Окно `null` (выключено) или
     * некорректное окно не ограничивают ничего — возвращается `true`.
     */
    fun isWithinWindow(
        epochMs: Long,
        window: MicrosoundWindow?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (window == null || !window.isValid) return true
        val localTime = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalTime()
        return window.containsMinuteOfDay(localTime.hour * 60 + localTime.minute)
    }

    /**
     * Обрезает время срабатывания по окну микрозвука:
     * - время внутри окна возвращается без изменений (интервал и его случайность
     *   сохраняются, окно только дополнительно ограничивает);
     * - время вне окна переносится на НАЧАЛО следующего окна;
     * - окно `null`/некорректное ничего не меняет.
     *
     * Если начало окна сегодня уже прошло (или совпало с кандидатом — начало
     * включительно, такой кандидат попал бы внутрь окна), берётся начало
     * следующих суток. Случайного разброса здесь нет: первое срабатывание
     * в окне ставится ровно на его начало.
     */
    fun clipToWindow(
        candidateEpochMs: Long,
        window: MicrosoundWindow?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (window == null || !window.isValid) return candidateEpochMs
        if (isWithinWindow(candidateEpochMs, window, zoneId)) return candidateEpochMs

        val candidate = Instant.ofEpochMilli(candidateEpochMs).atZone(zoneId)
        var windowStart = candidate.toLocalDate()
            .atTime(window.startHour, window.startMinute)
            .atZone(zoneId)
        if (!windowStart.toInstant().isAfter(candidate.toInstant())) {
            windowStart = windowStart.plusDays(1)
        }
        return windowStart.toInstant().toEpochMilli()
    }
}
