package com.personal.vibratuner.audio.choppy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно тишины прерывистой музыки (этап 4): плеер продолжает играть, усиление 0,
 * позиция старта запоминается, конец окна гарантирован страховкой по времени.
 * Тесты чистые: время передаётся снаружи, Android runtime не нужен.
 */
class ChoppySilentWindowTest {

    @Test
    fun start_mutesGainAndRemembersPosition() {
        val window = ChoppySilentWindow()

        window.start(positionMs = 42_000L, durationMs = 60_000L, nowElapsedMs = 1_000L)

        assertTrue("Окно активно", window.isActive)
        assertEquals("Позиция старта запомнена", 42_000L, window.savedPositionMs)
        assertEquals("Усиление в окне — 0", 0f, window.applyGain(0.8f), 0.0001f)
        assertEquals("Даже полное усиление глушится", 0f, window.applyGain(1f), 0.0001f)
    }

    @Test
    fun end_restoresNormalGainAndClearsState() {
        val window = ChoppySilentWindow()
        window.start(positionMs = 12_000L, durationMs = 60_000L, nowElapsedMs = 1_000L)

        window.end()

        assertFalse("Окно закрыто", window.isActive)
        assertEquals("Обычное усиление возвращается", 0.8f, window.applyGain(0.8f), 0.0001f)
        assertEquals("Позиция сброшена", 0L, window.savedPositionMs)
    }

    @Test
    fun end_isIdempotent_soEveryPathCanRestoreGain() {
        val window = ChoppySilentWindow()
        window.start(positionMs = 5_000L, durationMs = 30_000L, nowElapsedMs = 0L)

        window.end()
        window.end()
        window.end()

        assertFalse(window.isActive)
        assertEquals(1f, window.applyGain(1f), 0.0001f)
    }

    @Test
    fun deadline_isNotExceededDuringWindowAndIsAfterGrace() {
        val graceMs = ChoppySilentWindow.SAFETY_GRACE_MS
        val window = ChoppySilentWindow()
        window.start(positionMs = 0L, durationMs = 60_000L, nowElapsedMs = 1_000L)

        assertFalse(
            "Сразу после старта окно не просрочено",
            window.isDeadlineExceeded(nowElapsedMs = 1_000L)
        )
        assertFalse(
            "В конце штатного срока (машина ещё штатно завершает окно) не просрочено",
            window.isDeadlineExceeded(nowElapsedMs = 61_000L)
        )
        assertFalse(
            "В пределах запаса не просрочено",
            window.isDeadlineExceeded(nowElapsedMs = 61_000L + graceMs - 1)
        )
        assertTrue(
            "После срока + запаса окно обязано быть возвращено принудительно",
            window.isDeadlineExceeded(nowElapsedMs = 61_000L + graceMs)
        )
        assertTrue(
            "Процесс мог проспать окно целиком: большой сдвиг тоже просрочен",
            window.isDeadlineExceeded(nowElapsedMs = 10_000_000L)
        )
    }

    @Test
    fun deadline_isNeverExceededWhenWindowIsClosed() {
        val window = ChoppySilentWindow()
        window.start(positionMs = 0L, durationMs = 1_000L, nowElapsedMs = 0L)
        window.end()

        assertFalse(
            "Закрытое окно не требует принудительного возврата звука",
            window.isDeadlineExceeded(nowElapsedMs = Long.MAX_VALUE / 2)
        )
    }

    @Test
    fun restartDuringWindow_updatesPositionAndDeadline() {
        val window = ChoppySilentWindow()
        window.start(positionMs = 10_000L, durationMs = 60_000L, nowElapsedMs = 1_000L)
        window.start(positionMs = 25_000L, durationMs = 30_000L, nowElapsedMs = 5_000L)

        assertTrue(window.isActive)
        assertEquals(25_000L, window.savedPositionMs)
        assertFalse(
            "Новый срок считается от второго старта",
            window.isDeadlineExceeded(nowElapsedMs = 36_000L)
        )
    }

    @Test
    fun negativePositionAndDuration_areClamped() {
        val window = ChoppySilentWindow()
        window.start(positionMs = -5_000L, durationMs = -1_000L, nowElapsedMs = 500L)

        assertTrue(window.isActive)
        assertEquals("Отрицательная позиция не ломает seek", 0L, window.savedPositionMs)
        assertFalse(
            "Отрицательная длительность не делает окно просроченным сразу",
            window.isDeadlineExceeded(nowElapsedMs = 500L)
        )
        assertTrue(
            "Окно нулевой длины всё равно завершается страховкой",
            window.isDeadlineExceeded(
                nowElapsedMs = 500L + ChoppySilentWindow.SAFETY_GRACE_MS
            )
        )
    }
}
