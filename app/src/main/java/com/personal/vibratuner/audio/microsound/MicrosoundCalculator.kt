package com.personal.vibratuner.audio.microsound

import kotlin.random.Random

/**
 * Чистый калькулятор логики микрозвуков:
 * - Генерация случайного интервала в диапазоне min–max.
 * - Расчет времени следующего срабатывания.
 * Проверка «Часов тишины» находится в [QuietHoursCalculator].
 */
object MicrosoundCalculator {

    /**
     * Возвращает случайное количество минут в диапазоне [minMinutes, maxMinutes].
     * Если min >= max, возвращает minMinutes (минимум 1).
     */
    fun calculateRandomIntervalMinutes(
        minMinutes: Int,
        maxMinutes: Int,
        random: Random = Random.Default
    ): Int {
        val safeMin = minMinutes.coerceAtLeast(1)
        val safeMax = maxMinutes.coerceAtLeast(safeMin)
        if (safeMin == safeMax) {
            return safeMin
        }
        return random.nextInt(safeMin, safeMax + 1)
    }

    /**
     * Рассчитывает epoch millis следующего срабатывания на основе текущего времени и случайного интервала.
     */
    fun calculateNextTriggerTime(
        nowEpochMs: Long,
        minMinutes: Int,
        maxMinutes: Int,
        randomMinutes: Int? = null,
        random: Random = Random.Default
    ): Long {
        val intervalMinutes = randomMinutes ?: calculateRandomIntervalMinutes(minMinutes, maxMinutes, random)
        return nowEpochMs + intervalMinutes * 60_000L
    }
}
