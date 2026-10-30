package com.personal.vibratuner.audio.microsound

/**
 * Единые границы и проверка интервалов микрозвуков (минуты).
 *
 * Используется и обычными микрозвуками (таблица `microsounds`), и
 * фейк-включением колонки (DataStore), чтобы формат и валидация были
 * одинаковыми. Значения по умолчанию совпадают с умолчаниями обычного
 * микрозвука: 60–120 минут.
 */
object MicrosoundIntervalRule {

    const val MIN_INTERVAL_MINUTES = 1

    /** 7 суток в минутах — верхняя граница обычного микрозвука. */
    const val MAX_INTERVAL_MINUTES = 10_080

    const val DEFAULT_MIN_INTERVAL_MINUTES = 60
    const val DEFAULT_MAX_INTERVAL_MINUTES = 120

    /** Мин/макс в допустимых границах и мин не больше макс. */
    fun isValid(minIntervalMinutes: Int, maxIntervalMinutes: Int): Boolean {
        return minIntervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES &&
            maxIntervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES &&
            minIntervalMinutes <= maxIntervalMinutes
    }

    /**
     * Приводит пару из хранилища к допустимой. Битые значения заменяются
     * значениями по умолчанию, чтобы планировщик никогда не получил
     * нулевой/отрицательный интервал.
     */
    fun sanitize(minIntervalMinutes: Int, maxIntervalMinutes: Int): Pair<Int, Int> {
        if (isValid(minIntervalMinutes, maxIntervalMinutes)) {
            return minIntervalMinutes to maxIntervalMinutes
        }
        val min = minIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val max = maxIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        return if (min <= max) {
            min to max
        } else {
            DEFAULT_MIN_INTERVAL_MINUTES to DEFAULT_MAX_INTERVAL_MINUTES
        }
    }
}
