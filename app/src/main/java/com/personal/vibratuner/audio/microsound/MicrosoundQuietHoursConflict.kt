package com.personal.vibratuner.audio.microsound

import java.time.ZoneId

/**
 * Результат сравнения собственного окна микрозвука с глобальными «Часами тишины».
 *
 * Правило этапа 4: глобальные «Часы тишины» ВСЕГДА важнее окна микрозвука.
 * Микрозвук никогда не играет в часы тишины, что бы ни говорило его окно.
 */
enum class MicrosoundQuietHoursConflict {
    /** Пересечения нет (в том числе когда часы тишины выключены или окна нет). */
    NONE,

    /** Часть окна свободна, часть попадает в часы тишины: микрозвук сработает не всегда. */
    PARTIAL,

    /** Окно целиком внутри часов тишины: микрозвук не сработает никогда, пока настройки не изменятся. */
    FULL
}

/**
 * Чистые функции конфликта «окно микрозвука × часы тишины» (без Android,
 * покрыты JVM-тестами). Границы у обоих интервалов одинаковые: начало
 * включительно, конец НЕ включается, переход через полночь поддерживается.
 *
 * Здесь же считается ближайший момент, когда микрозвук реально может
 * сработать. Это нужно планировщику: иначе окно, целиком лежащее в тишине,
 * заставляло бы ставить сигналы в «мёртвые» часы снова и снова.
 */
object MicrosoundQuietHoursConflictCalculator {

    /** Сколько раз можно подвинуть момент вперёд, прежде чем признать его недостижимым. */
    private const val MAX_ADVANCE_STEPS = 8

    /**
     * Сравнивает окно микрозвука с часами тишины.
     *
     * - часы тишины выключены или некорректны -> [MicrosoundQuietHoursConflict.NONE];
     * - собственного окна нет («весь день») -> [MicrosoundQuietHoursConflict.NONE]:
     *   предупреждения нет, часы тишины просто действуют как обычно;
     * - иначе считается пересечение по минутам суток: касание границ
     *   (конец одного == начало другого) пересечением НЕ считается.
     */
    fun conflict(
        quietHours: QuietHoursSettings,
        window: MicrosoundWindow?
    ): MicrosoundQuietHoursConflict {
        if (!quietHours.enabled || !quietHours.isValid) {
            return MicrosoundQuietHoursConflict.NONE
        }
        if (window == null || !window.isValid) {
            return MicrosoundQuietHoursConflict.NONE
        }

        var windowMinutes = 0
        var sharedMinutes = 0
        for (minute in 0 until MicrosoundWindow.MINUTES_PER_DAY) {
            if (!window.containsMinuteOfDay(minute)) continue
            windowMinutes++
            if (QuietHoursCalculator.isWithinQuietHours(
                    minuteOfDay = minute,
                    startMinuteOfDay = quietHours.startMinuteOfDay,
                    endMinuteOfDay = quietHours.endMinuteOfDay
                )
            ) {
                sharedMinutes++
            }
        }

        return when {
            sharedMinutes == 0 -> MicrosoundQuietHoursConflict.NONE
            sharedMinutes >= windowMinutes -> MicrosoundQuietHoursConflict.FULL
            else -> MicrosoundQuietHoursConflict.PARTIAL
        }
    }

    /**
     * Ближайший момент, когда микрозвук действительно может сработать:
     * вне часов тишины и внутри собственного окна (начала окна включительно).
     *
     * - `null` — такого момента нет (окно целиком в тишине): сигнал не нужен,
     *   пока пользователь не изменит настройки;
     * - окно `null`/некорректное — ограничение только по часам тишины;
     * - время вне окна сначала переносится на начало следующего окна, затем
     *   уже проверяется тишина: получается «позже из двух» — конец тишины или
     *   начало окна, что раньше наступит.
     */
    fun nextPlayableEpochMs(
        candidateEpochMs: Long,
        quietHours: QuietHoursSettings,
        window: MicrosoundWindow?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val clipped = MicrosoundWindowCalculator.clipToWindow(candidateEpochMs, window, zoneId)
        if (!quietHours.enabled || !quietHours.isValid) return clipped
        if (window != null && conflict(quietHours, window) == MicrosoundQuietHoursConflict.FULL) {
            return null
        }

        var candidate = clipped
        repeat(MAX_ADVANCE_STEPS) {
            if (!QuietHoursCalculator.isWithinQuietHours(candidate, quietHours, zoneId)) {
                return candidate
            }
            // Конец тишины — первый свободный момент; если он вне окна,
            // clipToWindow перенесёт его на начало следующего окна.
            candidate = MicrosoundWindowCalculator.clipToWindow(
                candidateEpochMs = QuietHoursCalculator.quietEndAfter(
                    triggerEpochMs = candidate,
                    settings = quietHours,
                    zoneId = zoneId
                ),
                window = window,
                zoneId = zoneId
            )
        }
        return null
    }
}
