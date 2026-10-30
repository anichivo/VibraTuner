package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MicrosoundCalculatorTest {

    @Test
    fun randomInterval_staysWithinBounds() {
        val min = 60
        val max = 120
        val generated = mutableSetOf<Int>()

        for (seed in 1..200) {
            val random = Random(seed)
            val interval = MicrosoundCalculator.calculateRandomIntervalMinutes(min, max, random)
            assertTrue("Интервал $interval должен быть >= $min", interval >= min)
            assertTrue("Интервал $interval должен быть <= $max", interval <= max)
            generated.add(interval)
        }

        // Проверяем, что генератор не возвращает константу, а покрывает диапазон
        assertTrue("Должно быть сгенерировано несколько различных значений", generated.size > 1)
    }

    @Test
    fun randomInterval_whenMinEqualsMax_returnsExactValue() {
        val exact = 45
        val interval = MicrosoundCalculator.calculateRandomIntervalMinutes(exact, exact)
        assertEquals(exact, interval)
    }

    @Test
    fun randomInterval_whenMinGreaterThanMax_coercesSafely() {
        val interval = MicrosoundCalculator.calculateRandomIntervalMinutes(minMinutes = 100, maxMinutes = 50)
        assertEquals(100, interval)
    }

    @Test
    fun calculateNextTriggerTime_addsIntervalCorrectly() {
        val now = 1_000_000L
        val trigger = MicrosoundCalculator.calculateNextTriggerTime(
            nowEpochMs = now,
            minMinutes = 30,
            maxMinutes = 60,
            randomMinutes = 45
        )
        assertEquals(now + 45 * 60_000L, trigger)
    }
}
