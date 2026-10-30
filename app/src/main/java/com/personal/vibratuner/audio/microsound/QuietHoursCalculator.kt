package com.personal.vibratuner.audio.microsound

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Чистые функции «Часов тишины».
 *
 * Границы: начало включительно, конец не включается (в 08:00 тишина уже закончилась).
 * Поддерживаются обычные интервалы и интервалы с переходом через полночь.
 */
object QuietHoursCalculator {

    const val MINUTES_PER_DAY = 1440

    /**
     * Находится ли время [minuteOfDay] внутри интервала тишины.
     * Некорректный интервал (start == end) тишиной не считается.
     */
    fun isWithinQuietHours(
        minuteOfDay: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int
    ): Boolean {
        val current = normalizeMinute(minuteOfDay)
        val start = normalizeMinute(startMinuteOfDay)
        val end = normalizeMinute(endMinuteOfDay)
        if (start == end) return false
        return if (start < end) {
            current >= start && current < end
        } else {
            // Переход через полночь: [start, 24:00) или [00:00, end)
            current >= start || current < end
        }
    }

    /**
     * Находится ли момент [epochMs] внутри включённых часов тишины.
     */
    fun isWithinQuietHours(
        epochMs: Long,
        settings: QuietHoursSettings,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!settings.enabled || !settings.isValid) return false
        val localTime = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalTime()
        return isWithinQuietHours(
            minuteOfDay = localTime.hour * 60 + localTime.minute,
            startMinuteOfDay = settings.startMinuteOfDay,
            endMinuteOfDay = settings.endMinuteOfDay
        )
    }

    /**
     * Момент, когда заканчивается интервал тишины, в который попадает
     * [triggerEpochMs]. Возвращается БЛИЖАЙШИЙ конец строго после момента
     * (на следующих сутках, если сегодняшний уже прошёл). Если момент не
     * внутри тишины, результат всё равно считается по ближайшему концу — этим
     * пользуется только вызывающий код, который заранее проверил тишину.
     */
    fun quietEndAfter(
        triggerEpochMs: Long,
        settings: QuietHoursSettings,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val trigger = Instant.ofEpochMilli(triggerEpochMs).atZone(zoneId)
        var quietEnd = trigger.toLocalDate()
            .atTime(settings.endHour, settings.endMinute)
            .atZone(zoneId)
        if (!quietEnd.toInstant().isAfter(trigger.toInstant())) {
            quietEnd = quietEnd.plusDays(1)
        }
        return quietEnd.toInstant().toEpochMilli()
    }

    /**
     * Если случайное время срабатывания попало в часы тишины, сдвигает его
     * на конец часов тишины плюс случайный интервал min..max минут.
     * В остальных случаях возвращает исходное время.
     */
    fun shiftIfWithinQuietHours(
        triggerEpochMs: Long,
        settings: QuietHoursSettings,
        minIntervalMinutes: Int,
        maxIntervalMinutes: Int,
        random: Random = Random.Default,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (!isWithinQuietHours(triggerEpochMs, settings, zoneId)) {
            return triggerEpochMs
        }

        val quietEnd = quietEndAfter(triggerEpochMs, settings, zoneId)
        val offsetMinutes = MicrosoundCalculator.calculateRandomIntervalMinutes(
            minMinutes = minIntervalMinutes,
            maxMinutes = maxIntervalMinutes,
            random = random
        )
        return quietEnd + offsetMinutes * 60_000L
    }

    private fun normalizeMinute(value: Int): Int {
        val remainder = value % MINUTES_PER_DAY
        return if (remainder < 0) remainder + MINUTES_PER_DAY else remainder
    }
}
