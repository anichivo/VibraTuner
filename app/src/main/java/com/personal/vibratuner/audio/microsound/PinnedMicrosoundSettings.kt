package com.personal.vibratuner.audio.microsound

/**
 * Настройки закреплённого микрозвука «Включение колонки».
 *
 * Закреплённый микрозвук звучит один раз перед каждым будильником. Время старта
 * вычисляется автоматически: T − (длительность звука + фиксированная пауза 10 с).
 * НЕ подчиняется «Часам тишины» и случайным интервалам обычных микрозвуков.
 * Хранится в DataStore: без изменения схемы БД и без влияния на строки таблицы microsounds.
 *
 * Звук хранится в [uri]: `builtin:<id>` (встроенный каталог) или `content://`
 * (свой файл). Фейк-включение колонки СВОЕГО ЗВУКА НЕ ИМЕЕТ: при срабатывании
 * оно всегда играет ровно этот [uri] с усилением [gain]. Устаревший ключ
 * DataStore `fake_pinned_microsound_uri` не читается и не удаляется.
 */
data class PinnedMicrosoundSettings(
    val enabled: Boolean = false,
    val uri: String = "",
    val fileName: String = "",
    val gain: Float = DEFAULT_GAIN,
    /** Фейк-включение колонки включено (по умолчанию выключено). */
    val fakeEnabled: Boolean = false,
    val fakeMinIntervalMinutes: Int = MicrosoundIntervalRule.DEFAULT_MIN_INTERVAL_MINUTES,
    val fakeMaxIntervalMinutes: Int = MicrosoundIntervalRule.DEFAULT_MAX_INTERVAL_MINUTES,
    /**
     * Собственное окно работы фейк-включения колонки (Stage 3): `null` — весь
     * день, то есть прежнее поведение и совместимость с отсутствующими
     * ключами DataStore.
     */
    val fakeWindow: MicrosoundWindow? = null,
    /**
     * Собственное окно работы «Включения колонки» (Stage 3): `null` — окно
     * выключено. Закреплённый звук не имеет интервала, его единственное
     * срабатывание — предзвук будильника (sequenceStart), поэтому окно
     * проверяется на времени старта предзвука: вне окна предзвук не
     * планируется, а будильник всё равно срабатывает в T.
     */
    val window: MicrosoundWindow? = null
) {
    /**
     * Выбран реально воспроизводимый звук: свой файл (content://) либо
     * существующий встроенный. Неизвестный `builtin:<id>` таковым не считается.
     */
    val hasSound: Boolean
        get() = uri.isNotBlank() &&
            (!BuiltinMicrosoundCatalog.hasBuiltinPrefix(uri) ||
                BuiltinMicrosoundCatalog.findByStored(uri) != null)

    /** Воспроизводить можно только при выбранном звуке и включённом флаге. */
    val canPlay: Boolean get() = enabled && hasSound

    /** Фейк играет тем же звуком и усилением; собственного выбора звука у него нет. */
    val fakeCanPlay: Boolean get() = canPlay && fakeEnabled

    companion object {
        const val DEFAULT_GAIN = 0.8f

        const val MIN_GAIN = 0.05f
        const val MAX_GAIN = 1f
    }
}
