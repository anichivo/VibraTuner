package com.personal.vibratuner.data.local.alarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Чистая математическая и календарная логика расчёта времени срабатывания будильников.
 * Поддерживает однократные будильники и будильники по дням недели, включая переход через полночь.
 */
object AlarmCalculator {

    /**
     * Парсинг строки дней недели ("1,2,3,4,5") в набор DayOfWeek.
     */
    fun parseDaysOfWeek(daysStr: String): Set<DayOfWeek> {
        if (daysStr.isBlank()) return emptySet()
        return daysStr.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .map { DayOfWeek.of(it) }
            .toSet()
    }

    /**
     * Форматирование набора DayOfWeek в строку вида "1,2,3,4,5".
     */
    fun formatDaysOfWeek(days: Set<DayOfWeek>): String {
        return days.map { it.value }.sorted().joinToString(",")
    }

    /**
     * Рассчитывает точное время следующего срабатывания будильника (epoch millis).
     *
     * @param hour Час (0..23)
     * @param minute Минута (0..59)
     * @param daysOfWeek Набор дней недели (пусто = однократно)
     * @param nowMillis Текущее системное время в мс
     * @param zoneId Временная зона
     */
    fun calculateNextTriggerMillis(
        hour: Int,
        minute: Int,
        daysOfWeek: Set<DayOfWeek>,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val targetTime = LocalTime.of(hour, minute, 0, 0)

        if (daysOfWeek.isEmpty()) {
            // Однократный будильник:
            // Если время сегодня еще в будущем — сегодня; если уже наступило или прошло — завтра
            val todayTarget = LocalDateTime.of(now.toLocalDate(), targetTime)
            return if (todayTarget.isAfter(now)) {
                todayTarget.atZone(zoneId).toInstant().toEpochMilli()
            } else {
                todayTarget.plusDays(1).atZone(zoneId).toInstant().toEpochMilli()
            }
        }

        // Будильник по дням недели:
        // Ищем ближайший подходящий день недели (начиная с сегодняшнего дня, смещение 0..7 дней)
        for (dayOffset in 0..7) {
            val candidateDate = now.toLocalDate().plusDays(dayOffset.toLong())
            if (candidateDate.dayOfWeek in daysOfWeek) {
                val candidateDateTime = LocalDateTime.of(candidateDate, targetTime)
                if (candidateDateTime.isAfter(now)) {
                    return candidateDateTime.atZone(zoneId).toInstant().toEpochMilli()
                }
            }
        }

        // Если не сработал ни один в пределах недели — ровно через 7 дней
        return LocalDateTime.of(now.toLocalDate().plusDays(7), targetTime)
            .atZone(zoneId).toInstant().toEpochMilli()
    }
}
