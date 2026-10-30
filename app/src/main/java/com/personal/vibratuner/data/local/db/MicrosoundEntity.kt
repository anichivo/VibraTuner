package com.personal.vibratuner.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.personal.vibratuner.audio.microsound.MicrosoundWindow
import com.personal.vibratuner.audio.source.LocalFileSoundSource
import com.personal.vibratuner.audio.source.SoundSource

/**
 * Сущность микрозвука для периодического случайного воспроизведения.
 *
 * @property id Уникальный идентификатор
 * @property title Отображаемое название или имя файла
 * @property uri Persistable URI файла через Storage Access Framework
 * @property gain Программное усиление звука (0.0f..1.0f, по умолчанию 0.8f = 80%)
 * @property minIntervalMinutes Минимальный интервал включения в минутах (например 60 мин)
 * @property maxIntervalMinutes Максимальный интервал включения в минутах (например 120 мин)
 * @property isEnabled Включен / выключен
 * @property windowStartMinutes Начало собственного окна работы (минуты от полуночи)
 * @property windowEndMinutes Конец собственного окна работы (минуты от полуночи)
 *
 * Окно работы (`windowStartMinutes`/`windowEndMinutes`) необязательно: `NULL` в
 * обеих колонках означает «весь день», то есть прежнее поведение и совместимость
 * со всеми существующими строками (миграция 12 -> 13 добавляет колонки без значений).
 * Глобальные «Часы тишины» (DataStore) продолжают работать независимо от окна.
 */
@Entity(tableName = "microsounds")
data class MicrosoundEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val uri: String,
    val gain: Float = 0.8f,
    val minIntervalMinutes: Int = 60,
    val maxIntervalMinutes: Int = 120,
    val isEnabled: Boolean = true,
    val windowStartMinutes: Int? = null,
    val windowEndMinutes: Int? = null
) {
    /**
     * Собственное окно работы или `null` (весь день). Некорректная сохранённая
     * пара (начало == конец, значения вне суток) трактуется как отсутствие окна.
     */
    val window: MicrosoundWindow?
        get() = MicrosoundWindow.sanitize(windowStartMinutes, windowEndMinutes)

    fun toSoundSource(): SoundSource {
        return LocalFileSoundSource(
            id = id.toString(),
            displayName = title,
            uriString = uri
        )
    }
}
