package com.personal.vibratuner.data.local.db

/**
 * Ссылки будильников на треки.
 *
 * В нативной SQLite-реализации (`SQLiteAppDatabase`) внешние ключи SQLite не включены,
 * поэтому каскад `ON DELETE SET NULL` не сработал бы. Вместо этого ссылки очищаются
 * явно теми же SQL-запросами, что и удаление трека (см. `SQLiteTrackDao`).
 */
object AlarmTrackReference {
    /** Сброс ссылок на конкретный удаляемый трек. */
    const val CLEAR_BY_TRACK_ID_SQL = "UPDATE alarms SET trackId = NULL WHERE trackId = ?"

    /** Сброс всех ссылок при полной очистке библиотеки. */
    const val CLEAR_ALL_SQL = "UPDATE alarms SET trackId = NULL WHERE trackId IS NOT NULL"
}
