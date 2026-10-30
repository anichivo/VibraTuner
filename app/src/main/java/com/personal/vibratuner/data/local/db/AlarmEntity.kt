package com.personal.vibratuner.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Источник звука будильника. В БД хранится строкой в `alarms.sourceType`.
 * Этап 4 добавит сюда сетевые плейлисты, не меняя значение PLAYLIST.
 */
enum class AlarmSoundSource(val value: String) {
    TRACK("TRACK"),
    PLAYLIST("PLAYLIST");

    companion object {
        /** Неизвестное/пустое значение трактуется как обычный одиночный трек. */
        fun fromValue(value: String?): AlarmSoundSource =
            entries.firstOrNull { it.value == value } ?: TRACK
    }
}

/**
 * Сущность будильника («Включение по времени»).
 *
 * @property id Уникальный идентификатор
 * @property hour Час срабатывания (0..23)
 * @property minute Минута срабатывания (0..59)
 * @property durationMinutes Длительность сигнала в минутах (по умолчанию 30 мин,
 *           допустимо 1..1440; 1440 = 24 часа)
 * @property isEnabled Включен / выключен
 * @property daysOfWeek Дни недели в формате "1,2,3,4,5" (1 = Понедельник, 7 = Воскресенье).
 *                      Пустая строка означает однократный будильник.
 * @property label Название / метка будильника
 * @property trackId Выбранный трек будильника. `null` — вся библиотека (цикл/перемешивание).
 *                   Ссылка очищается вручную при удалении трека (см. AlarmTrackReference),
 *                   внешний ключ SQLite не включён в нативной реализации БД.
 * @property gain Громкость сигнала будильника (0.0f–1.0f, по умолчанию 0.8f).
 *                Для запущенного будильником воспроизведения это целевое усиление
 *                музыки (громкость сеанса), ограниченное диапазоном 0..1.
 * @property sourceType Источник звука: [AlarmSoundSource.TRACK] (один трек/вся библиотека)
 *           или [AlarmSoundSource.PLAYLIST]. Старые будильники — TRACK.
 * @property playlistId Локальный плейлист для источника PLAYLIST. Внешнего ключа НЕТ:
 *           удалённый плейлист обрабатывается при срабатывании (сигнал пропускается).
 * @property catalogPlaylistId Стабильный id плейлиста каталога «От автора» для
 *           источника PLAYLIST, который НЕ добавлен в «Мою коллекцию» (v11).
 *           Треки такого плейлиста читаются при срабатывании из кеша манифеста
 *           (`filesDir/author_manifest.json`), а не из Room: каталог в БД не
 *           хранится, поэтому добавлять плейлист в библиотеку не нужно.
 *           Взаимоисключающее с [playlistId]: у будильника-плейлиста ровно одно
 *           из двух значений.
 * @property shuffle true — треки плейлиста при срабатывании перемешиваются.
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val durationMinutes: Int = 30,
    val isEnabled: Boolean = true,
    val daysOfWeek: String = "",
    val label: String = "",
    val trackId: Long? = null,
    val gain: Float = DEFAULT_GAIN,
    @ColumnInfo(defaultValue = "'TRACK'")
    val sourceType: String = AlarmSoundSource.TRACK.value,
    val playlistId: Long? = null,
    val catalogPlaylistId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val shuffle: Boolean = false
) {
    /** true — сигнал играет плейлист (локальный или из каталога). */
    val isPlaylistSource: Boolean
        get() = AlarmSoundSource.fromValue(sourceType) == AlarmSoundSource.PLAYLIST

    /** true — сигнал играет плейлист каталога, не добавленный в библиотеку. */
    val isCatalogPlaylistSource: Boolean
        get() = isPlaylistSource && catalogPlaylistId != null

    companion object {
        /** Громкость будильника по умолчанию — 80%. */
        const val DEFAULT_GAIN = 0.8f
    }
}
