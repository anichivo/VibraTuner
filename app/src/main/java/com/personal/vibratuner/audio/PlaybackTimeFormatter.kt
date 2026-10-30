package com.personal.vibratuner.audio

import java.util.Locale

/**
 * Форматирование позиции/длительности воспроизведения в mm:ss.
 * Минуты не ограничены 59 (например, 65:03 для длинного файла).
 */
object PlaybackTimeFormatter {

    fun format(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
