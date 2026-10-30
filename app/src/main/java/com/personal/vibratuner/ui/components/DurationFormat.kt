package com.personal.vibratuner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R

/**
 * Разбор длительности в МИНУТАХ на части русской подписи — БЕЗ Android-ресурсов.
 * Это чистые правила форматирования, покрытые обычными юнит-тестами
 * (`DurationFormatTest`); сами слова берутся из strings.xml.
 *
 * Правила: меньше часа — «45 мин»; ровно целые часы — «1 час», «2 часа»,
 * «5 часов» (plurals); часы и минуты — «1 ч 30 мин». Один и тот же формат
 * используют карточка расписания, редактор будильника, итог длительности
 * плейлиста и будущие колёса выбора (Part 2) — второго формата нет.
 */
sealed interface DurationLabel {
    data class Minutes(val minutes: Int) : DurationLabel
    data class Hours(val hours: Int) : DurationLabel
    data class HoursMinutes(val hours: Int, val minutes: Int) : DurationLabel
}

/**
 * Чистое правило выбора формы подписи для длительности в минутах.
 * Отрицательные значения трактуются как 0 минут.
 */
fun durationLabel(minutes: Int): DurationLabel {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val restMinutes = safeMinutes % 60
    return when {
        hours > 0 && restMinutes > 0 -> DurationLabel.HoursMinutes(hours, restMinutes)
        hours > 0 -> DurationLabel.Hours(hours)
        else -> DurationLabel.Minutes(restMinutes)
    }
}

/**
 * Читаемая русская форма длительности СИГНАЛА (минуты): «45 мин», «2 часа»,
 * «1 ч 30 мин». Единственный форматтер для минуто-часов сигналов; длительность
 * ТРЕКА форматируется `formatTrackDuration` в этом же файле (другой контракт:
 * миллисекунды, полное слово «час», без секунд).
 */
@Composable
fun formatDurationWords(minutes: Int): String = when (val label = durationLabel(minutes)) {
    is DurationLabel.HoursMinutes -> stringResource(
        R.string.duration_value_hours_minutes,
        label.hours,
        label.minutes
    )

    is DurationLabel.Hours -> pluralStringResource(
        R.plurals.duration_hours,
        label.hours,
        label.hours
    )

    is DurationLabel.Minutes -> stringResource(R.string.duration_value_minutes, label.minutes)
}

/**
 * Разбор длительности ТРЕКА (миллисекунды) на части подписи для строк списков
 * и мини-плеера — БЕЗ Android-ресурсов, поэтому правила покрыты обычным
 * юнит-тестом (`TrackDurationFormatTest`). Это НЕ второй форматтер: слова
 * берутся из тех же `duration_hours`/`duration_value_minutes`, а файл остаётся
 * единственным домом форматирования длительности.
 *
 * Правила фидбека: секунды отбрасываются (floor), но трек короче минуты —
 * «1 мин»; ровно целые часы — «2 часа»; часы и минуты — «1 час 3 мин»,
 * «2 часа 48 мин» (полное слово «час» во всех формах, «мин» неизменяемо).
 * `null` — длительность неизвестна (0/отрицательная): строку НЕ показываем,
 * никакого placeholder'а и «0 мин».
 */
sealed interface TrackDurationLabel {
    data class Minutes(val minutes: Int) : TrackDurationLabel
    data class Hours(val hours: Int) : TrackDurationLabel
    data class HoursMinutes(val hours: Int, val minutes: Int) : TrackDurationLabel
}

/** Чистое правило выбора формы подписи длительности трека по миллисекундам. */
fun trackDurationLabel(durationMs: Long): TrackDurationLabel? {
    if (durationMs <= 0L) return null
    // 1..59 999 мс — это «1 мин»: floor по минутам, но не ноль.
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> TrackDurationLabel.HoursMinutes(hours, minutes)
        hours > 0 -> TrackDurationLabel.Hours(hours)
        else -> TrackDurationLabel.Minutes(minutes)
    }
}

/**
 * ЕДИНСТВЕННАЯ подпись длительности трека для списков и мини-плеера:
 * «3 мин», «43 мин», «1 час», «2 часа 48 мин». `null` — неизвестна.
 */
@Composable
fun formatTrackDuration(durationMs: Long): String? =
    when (val label = trackDurationLabel(durationMs)) {
        null -> null
        is TrackDurationLabel.HoursMinutes -> stringResource(
            R.string.track_duration_hours_minutes_format,
            pluralStringResource(R.plurals.duration_hours, label.hours, label.hours),
            label.minutes
        )

        is TrackDurationLabel.Hours -> pluralStringResource(
            R.plurals.duration_hours,
            label.hours,
            label.hours
        )

        is TrackDurationLabel.Minutes -> stringResource(
            R.string.duration_value_minutes,
            label.minutes
        )
    }

/**
 * Миллисекунды -> минуты для итоговой длительности плейлиста. Округление вверх:
 * плейлист на 10 секунд показывается как «1 мин», а не «0 мин».
 */
fun durationMinutesRoundedUp(durationMs: Long): Int {
    val safeMs = durationMs.coerceAtLeast(0L)
    if (safeMs == 0L) return 0
    return ((safeMs + 59_999L) / 60_000L).toInt()
}
