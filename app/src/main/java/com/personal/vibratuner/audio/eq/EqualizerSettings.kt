package com.personal.vibratuner.audio.eq

/**
 * Настройки эквалайзера — единый источник истины. Сохраняются сразу при каждом
 * изменении и переживают перезапуск приложения.
 *
 * [bassPercent]/[treblePercent] — СИММЕТРИЧНЫЕ уровни тембра: отрицательные
 * значения СРЕЗАЮТ частоты, положительные поднимают, `0` — нейтраль. Проценты
 * отсчитываются от РЕАЛЬНОГО диапазона уровней полос устройства
 * (`Equalizer.getBandLevelRange`), поэтому -100 = аппаратный минимум, +100 =
 * аппаратный максимум, а не захардкоженные -15/+15 дБ. Прежние сохранённые
 * значения 0..100 читаются без миграции: смысл положительных значений тот же.
 *
 * [enabled] = false означает, что НИЧЕГО не применяется (без эффектов, скорость
 * 1.0, системная громкость не трогается), но сохранённые значения не теряются.
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bassPercent: Int = TONE_NEUTRAL_PERCENT,
    val treblePercent: Int = TONE_NEUTRAL_PERCENT,
    val overboostPercent: Int = 0,
    val speed: Float = DEFAULT_SPEED,
    val boostSystemVolume: Boolean = false
) {
    companion object {
        // Все пределы живут ЗДЕСЬ и только здесь (см. equalizer_spec).
        /** Нейтральное положение регуляторов тембра (0 дБ). */
        const val TONE_NEUTRAL_PERCENT = 0

        /** Максимальный срез тембра: аппаратный МИНИМУМ полос устройства. */
        const val TONE_MIN_PERCENT = -100

        /** Максимальный подъём тембра: аппаратный МАКСИМУМ полос устройства. */
        const val TONE_MAX_PERCENT = 100
        const val OVERBOOST_MIN_PERCENT = 0
        /**
         * Верхняя граница усиления — 500 % (≈ +15,6 дБ через `LoudnessEnhancer`).
         * Прежний максимум 200 % остаётся допустимым значением, поэтому
         * сохранённые настройки читаются без миграции.
         */
        const val OVERBOOST_MAX_PERCENT = 500
        const val SPEED_MIN = 0.25f
        const val SPEED_MAX = 4.0f
        const val DEFAULT_SPEED = 1.0f

        /** Значения по умолчанию (эквалайзер выключен). */
        val DEFAULT = EqualizerSettings()

        /**
         * Приводит сырые значения к допустимым диапазонам; NaN скорости
         * заменяется на 1.0. Применяется и при чтении, и при записи.
         */
        fun sanitize(
            enabled: Boolean = false,
            bassPercent: Int = TONE_NEUTRAL_PERCENT,
            treblePercent: Int = TONE_NEUTRAL_PERCENT,
            overboostPercent: Int = 0,
            speed: Float = DEFAULT_SPEED,
            boostSystemVolume: Boolean = false
        ): EqualizerSettings = EqualizerSettings(
            enabled = enabled,
            bassPercent = bassPercent.coerceIn(TONE_MIN_PERCENT, TONE_MAX_PERCENT),
            treblePercent = treblePercent.coerceIn(TONE_MIN_PERCENT, TONE_MAX_PERCENT),
            overboostPercent = overboostPercent.coerceIn(
                OVERBOOST_MIN_PERCENT,
                OVERBOOST_MAX_PERCENT
            ),
            speed = if (speed.isNaN()) DEFAULT_SPEED else speed.coerceIn(SPEED_MIN, SPEED_MAX),
            boostSystemVolume = boostSystemVolume
        )

        /** Санитизированная копия существующего объекта. */
        fun sanitize(settings: EqualizerSettings): EqualizerSettings = sanitize(
            enabled = settings.enabled,
            bassPercent = settings.bassPercent,
            treblePercent = settings.treblePercent,
            overboostPercent = settings.overboostPercent,
            speed = settings.speed,
            boostSystemVolume = settings.boostSystemVolume
        )
    }
}
