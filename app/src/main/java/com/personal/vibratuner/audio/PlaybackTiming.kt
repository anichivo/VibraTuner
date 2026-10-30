package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlin.math.roundToLong

/**
 * Соотношение медиавремени и реального (wall-clock) времени при ускоренном
 * воспроизведении (часть 5).
 *
 * Правило проекта:
 * - всё, что пользователь задал в минутах/секундах (длительность сеанса,
 *   прерывистая музыка, нарастание громкости, интервалы микрозвуков,
 *   автосохранение), живёт в РЕАЛЬНОМ времени и скоростью не растягивается;
 * - значения, выведенные из позиции/длительности трека, для решений о
 *   реальном времени переводятся как `wall = media / speed` (и наоборот).
 *
 * Скорость берётся только из настроек эквалайзера: при `enabled = false`
 * действует 1x, даже если сохранённое значение скорости другое. Все пределы —
 * из [EqualizerSettings], второго места с числами нет.
 */
object PlaybackTiming {

    /** Действующая скорость: при выключенном эквалайзере всегда 1x. */
    fun effectiveSpeed(settings: EqualizerSettings): Float {
        if (!settings.enabled) return EqualizerSettings.DEFAULT_SPEED
        return sanitizeSpeed(settings.speed)
    }

    /** Приводит скорость к допустимому диапазону; NaN/<= 0 дают 1x. */
    fun sanitizeSpeed(speed: Float): Float = when {
        speed.isNaN() || speed <= 0f -> EqualizerSettings.DEFAULT_SPEED
        else -> speed.coerceIn(EqualizerSettings.SPEED_MIN, EqualizerSettings.SPEED_MAX)
    }

    /**
     * Медиадлительность/позиция → реальное время: `wall = media / speed`.
     * Отрицательные значения дают 0, чтобы таймеры и watchdog не получали
     * некорректные задержки.
     */
    fun wallClockMs(mediaMs: Long, speed: Float): Long {
        if (mediaMs <= 0L) return 0L
        val safeSpeed = sanitizeSpeed(speed)
        return (mediaMs.toDouble() / safeSpeed.toDouble())
            .roundToLong()
            .coerceAtLeast(0L)
    }

    /** То же, но со скоростью из настроек (при выключенном эквалайзере — 1x). */
    fun wallClockMs(mediaMs: Long, settings: EqualizerSettings): Long =
        wallClockMs(mediaMs, effectiveSpeed(settings))
}
