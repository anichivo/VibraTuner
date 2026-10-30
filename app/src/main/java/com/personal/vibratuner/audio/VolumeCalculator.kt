package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.eq.EqualizerSettings

/**
 * Итоговые программные усиления плееров (0.0f–1.0f). Единственная точка,
 * где определяется композиция громкости (часть 5):
 *
 *   громкость музыки   = громкость сеанса × ducking/fade;
 *   громкость микрозвука = громкость сеанса × собственное усиление микрозвука
 *                          × ducking/fade.
 *
 * Громкость сеанса — это `alarms.gain` во время сигнала или текущее усиление
 * ручной музыки вне сеанса; она НИКОГДА не усиливает (0..1). Перегруз
 * эквалайзера сюда НЕ входит: его добавляет только `LoudnessEnhancer`
 * в `EqualizerEngine`, поэтому итоговая громкость = сеанс × перегруз.
 *
 * [settings] намеренно не участвует в расчёте: переключение эквалайзера
 * ОБЯЗАНО менять только эффекты/скорость, но не `player.volume`. Параметр
 * стоит в сигнатуре, чтобы это правило было явным и проверяемым тестом.
 */
object VolumeCalculator {

    /** Программные усиления двух плееров сервиса после композиции. */
    data class EffectiveVolumes(
        val music: Float,
        val microsound: Float
    )

    /** Нейтральное усиление: ничего не усиливает и не приглушает. */
    const val NEUTRAL_GAIN: Float = 1.0f

    /**
     * Приводит любое усиление к 0..1. NaN (битые данные DataStore/БД) даёт
     * нейтральное 1.0, чтобы неожиданный ноль не заглушил сеанс.
     */
    fun sanitizeGain(value: Float): Float = when {
        value.isNaN() -> NEUTRAL_GAIN
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /**
     * ЕДИНСТВЕННАЯ функция композиции громкости. [sessionVolume] — громкость
     * сеанса (0..1; 1.0 — нейтральная, когда сеанса нет), [microsoundVolume] —
     * собственное усиление микрозвука (0..1; для музыки 1.0), [duckingFactor] —
     * существующий fade/ducking (0..1; для нарастания сеанса — прогресс 0..1).
     */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveVolumes(
        sessionVolume: Float,
        microsoundVolume: Float = NEUTRAL_GAIN,
        duckingFactor: Float = NEUTRAL_GAIN,
        settings: EqualizerSettings = EqualizerSettings.DEFAULT
    ): EffectiveVolumes {
        val session = sanitizeGain(sessionVolume)
        val microsound = sanitizeGain(microsoundVolume)
        val ducking = sanitizeGain(duckingFactor)
        return EffectiveVolumes(
            music = session * ducking,
            microsound = session * microsound * ducking
        )
    }

    /**
     * Усиление музыки, запущенной будильником. Источник громкости — усиление
     * самого будильника (0.0f–1.0f), ограничивается диапазоном [0.0f, 1.0f].
     */
    fun calculateAlarmMusicGain(alarmGain: Float): Float = sanitizeGain(alarmGain)

    /**
     * Выбор громкости сеанса по источнику запуска:
     * будильник (alarmGain != null) → усиление сеанса (0..1);
     * ручное воспроизведение → текущее усиление музыки (0..1).
     */
    fun calculateTargetMusicGain(
        alarmGain: Float?,
        currentGain: Float
    ): Float {
        return if (alarmGain != null) {
            calculateAlarmMusicGain(alarmGain)
        } else {
            sanitizeGain(currentGain)
        }
    }

    /**
     * Прогресс нарастания громкости от 0.0f до 1.0f (это ducking-фактор
     * композиции). Если rampDurationMs <= 0, нарастание отключено и сразу
     * возвращается 1.0f.
     */
    fun calculateRampProgress(elapsedMs: Long, rampDurationMs: Long): Float {
        if (rampDurationMs <= 0L) return 1.0f
        val elapsedClamped = elapsedMs.coerceAtLeast(0L)
        return (elapsedClamped.toFloat() / rampDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
    }
}
