package com.personal.vibratuner.audio.microsound

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.personal.vibratuner.R

/**
 * Один встроенный микрозвук каталога.
 *
 * @property id Стабильный строковый идентификатор. НИКОГДА не переименовывать:
 * он сохраняется в БД (`microsounds.uri`) в виде `builtin:<id>`.
 * @property nameRes Отображаемое название (только из `strings.xml`).
 * @property rawRes Ссылка на файл в `res/raw` (формат — MP3, см. STATE.md).
 * @property categoryRes Необязательная категория (вторая строка в списке выбора).
 */
data class BuiltinMicrosound(
    val id: String,
    @StringRes val nameRes: Int,
    @RawRes val rawRes: Int,
    @StringRes val categoryRes: Int? = null
)

/**
 * ЕДИНСТВЕННЫЙ каталог встроенных микрозвуков (поставляются внутри APK).
 *
 * Выбор хранится в существующем поле `microsounds.uri` как `builtin:<id>`,
 * поэтому схема Room НЕ меняется, а старые пользовательские файлы (content://)
 * продолжают работать без конвертации.
 *
 * Чтобы добавить новый звук: 1) положить файл в `app/src/main/res/raw`
 * (имя — строчное snake_case, MP3); 2) добавить одну строку названия (и, если
 * нужно, категории) в `strings.xml`; 3) добавить одну запись в [entries].
 *
 * Каталог содержит ТОЛЬКО реальные записи владельца (папка `microsounds/`).
 * Сгенерированные плейсхолдеры v1 (`chime`, `bell`, `droplet`, `ping`,
 * `low_gong`, `pulse`) удалены навсегда — их id НИКОГДА не возвращаются:
 * сохранённые строки с ними дают «звук недоступен» и пропускаются с логом.
 */
object BuiltinMicrosoundCatalog {

    /** Префикс сохранённого значения для встроенного звука. */
    const val SCHEME_PREFIX = "builtin:"

    val entries: List<BuiltinMicrosound> = listOf(
        BuiltinMicrosound(
            id = "s3_power_on",
            nameRes = R.string.microsound_builtin_s3_power_on,
            rawRes = R.raw.microsound_s3_power_on,
            categoryRes = R.string.microsound_category_notification
        ),
        BuiltinMicrosound(
            id = "notification",
            nameRes = R.string.microsound_builtin_notification,
            rawRes = R.raw.microsound_notification,
            categoryRes = R.string.microsound_category_notification
        ),
        BuiltinMicrosound(
            id = "notification_2",
            nameRes = R.string.microsound_builtin_notification_2,
            rawRes = R.raw.microsound_notification_2,
            categoryRes = R.string.microsound_category_notification
        ),
        BuiltinMicrosound(
            id = "notification_3",
            nameRes = R.string.microsound_builtin_notification_3,
            rawRes = R.raw.microsound_notification_3,
            categoryRes = R.string.microsound_category_notification
        ),
        BuiltinMicrosound(
            id = "bluetooth_connected",
            nameRes = R.string.microsound_builtin_bluetooth_connected,
            rawRes = R.raw.microsound_bluetooth_connected,
            categoryRes = R.string.microsound_category_notification
        ),
        BuiltinMicrosound(
            id = "doorbell",
            nameRes = R.string.microsound_builtin_doorbell,
            rawRes = R.raw.microsound_doorbell,
            categoryRes = R.string.microsound_category_home
        ),
        BuiltinMicrosound(
            id = "door_slam",
            nameRes = R.string.microsound_builtin_door_slam,
            rawRes = R.raw.microsound_door_slam,
            categoryRes = R.string.microsound_category_home
        ),
        BuiltinMicrosound(
            id = "furniture_move",
            nameRes = R.string.microsound_builtin_furniture_move,
            rawRes = R.raw.microsound_furniture_move,
            categoryRes = R.string.microsound_category_home
        ),
        BuiltinMicrosound(
            id = "item_fall",
            nameRes = R.string.microsound_builtin_item_fall,
            rawRes = R.raw.microsound_item_fall,
            categoryRes = R.string.microsound_category_home
        ),
        BuiltinMicrosound(
            id = "smoke_detector",
            nameRes = R.string.microsound_builtin_smoke_detector,
            rawRes = R.raw.microsound_smoke_detector,
            categoryRes = R.string.microsound_category_home
        ),
        BuiltinMicrosound(
            id = "perforator_1",
            nameRes = R.string.microsound_builtin_perforator_1,
            rawRes = R.raw.microsound_perforator_1,
            categoryRes = R.string.microsound_category_technique
        ),
        BuiltinMicrosound(
            id = "perforator_2",
            nameRes = R.string.microsound_builtin_perforator_2,
            rawRes = R.raw.microsound_perforator_2,
            categoryRes = R.string.microsound_category_technique
        ),
        BuiltinMicrosound(
            id = "yandex_speaker_activation",
            nameRes = R.string.microsound_builtin_yandex_speaker_activation,
            rawRes = R.raw.microsound_yandex_speaker_activation,
            categoryRes = R.string.microsound_category_technique
        ),
        BuiltinMicrosound(
            id = "squeak",
            nameRes = R.string.microsound_builtin_squeak,
            rawRes = R.raw.microsound_squeak,
            categoryRes = R.string.microsound_category_other
        ),
        BuiltinMicrosound(
            id = "arabian_mystery",
            nameRes = R.string.microsound_builtin_arabian_mystery,
            rawRes = R.raw.microsound_arabian_mystery,
            categoryRes = R.string.microsound_category_other
        )
    )

    /** Значение для сохранения в БД. */
    fun encode(id: String): String = SCHEME_PREFIX + id

    /** Начинается ли сохранённое значение с префикса встроенного звука. */
    fun hasBuiltinPrefix(stored: String): Boolean = stored.trim().startsWith(SCHEME_PREFIX)

    /**
     * Идентификатор из `builtin:<id>`. `null` — это не встроенный звук
     * (пользовательский URI) либо идентификатор пустой.
     */
    fun parseId(stored: String): String? {
        val trimmed = stored.trim()
        if (!trimmed.startsWith(SCHEME_PREFIX)) return null
        return trimmed.removePrefix(SCHEME_PREFIX).trim().ifEmpty { null }
    }

    /** Запись каталога по стабильному id; `null` — такого звука в каталоге нет. */
    fun findById(id: String): BuiltinMicrosound? = entries.firstOrNull { it.id == id }

    /** Запись каталога по сохранённому значению `builtin:<id>`. */
    fun findByStored(stored: String): BuiltinMicrosound? = parseId(stored)?.let(::findById)

    /**
     * Звук по умолчанию для «Включения колонки»: первый встроенный звук каталога
     * в сохранённом виде `builtin:<id>`. Пустая строка — каталог пуст (не бывает).
     * Используется при включении звука без предварительного выбора: сохранённое
     * состояние обязано оставаться валидным.
     */
    fun defaultStoredUri(): String = entries.firstOrNull()?.let { encode(it.id) } ?: ""

    /**
     * URI для Media3 (`android.resource://<package>/<resId>`).
     * `null` — неизвестный/пустой id: вызывающая сторона обязана пропустить
     * звук с записью в лог, а не падать (инвариант отказоустойчивости).
     */
    fun resourceUriString(packageName: String, stored: String): String? {
        val entry = findByStored(stored) ?: return null
        return "android.resource://$packageName/${entry.rawRes}"
    }
}
