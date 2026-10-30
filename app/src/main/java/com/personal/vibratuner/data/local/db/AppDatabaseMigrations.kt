package com.personal.vibratuner.data.local.db

import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * Последовательные миграции нативной SQLite-базы (без destructive fallback).
 *
 * Поддерживаемый путь обновления: 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13.
 * Свежая установка создаёт схему сразу в onCreate без миграций.
 */
object AppDatabaseMigrations {

    const val VERSION = 13

    /**
     * 5 -> 6: выбранный трек будильника. Колонка nullable и без DEFAULT,
     * поэтому у всех существующих будильников trackId = NULL («Вся библиотека»).
     */
    const val ADD_ALARM_TRACK_ID_SQL = "ALTER TABLE alarms ADD COLUMN trackId INTEGER"

    /**
     * 6 -> 7: громкость будильника. NOT NULL с DEFAULT 0.8 — SQLite подставляет
     * значение 0.8f во все существующие строки, данные будильников не теряются.
     */
    const val ADD_ALARM_GAIN_SQL = "ALTER TABLE alarms ADD COLUMN gain REAL NOT NULL DEFAULT 0.8"

    /*
     * 8 -> 9: источник звука будильника (локальный плейлист или трек).
     * Три ALTER TABLE ADD COLUMN сохраняют id, время, дни, трек и громкость
     * существующих будильников. DEFAULT совпадает с @ColumnInfo в AlarmEntity:
     * - sourceType TEXT NOT NULL DEFAULT 'TRACK' — старые будильники играют трек;
     * - playlistId INTEGER (nullable, без DEFAULT и БЕЗ внешнего ключа) — удалённый
     *   плейлист обрабатывается при срабатывании, а не ломает схему/удаление;
     * - shuffle INTEGER NOT NULL DEFAULT 0.
     */
    val ADD_ALARM_SOURCE_TYPE_SQL =
        "ALTER TABLE alarms ADD COLUMN sourceType TEXT NOT NULL DEFAULT '${AlarmSoundSource.TRACK.value}'"

    const val ADD_ALARM_PLAYLIST_ID_SQL = "ALTER TABLE alarms ADD COLUMN playlistId INTEGER"

    const val ADD_ALARM_SHUFFLE_SQL = "ALTER TABLE alarms ADD COLUMN shuffle INTEGER NOT NULL DEFAULT 0"

    const val TRIM_TRACK_URIS_SQL = "UPDATE tracks SET uri = trim(uri)"

    /** Приводит схему URI к нижнему регистру так же, как TrackUriNormalizer. */
    val LOWERCASE_TRACK_URI_SCHEME_SQL = """
        UPDATE tracks
        SET uri = lower(substr(uri, 1, instr(uri, ':') - 1)) || substr(uri, instr(uri, ':'))
        WHERE instr(uri, ':') > 1
    """.trimIndent()

    const val CREATE_TRACKS_URI_INDEX_SQL =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_uri ON tracks(uri)"

    val CREATE_MICROSOUNDS_V5_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS microsounds_v5 (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            uri TEXT NOT NULL,
            gain REAL NOT NULL,
            minIntervalMinutes INTEGER NOT NULL,
            maxIntervalMinutes INTEGER NOT NULL,
            isEnabled INTEGER NOT NULL
        )
    """.trimIndent()

    val COPY_MICROSOUNDS_V5_SQL = """
        INSERT INTO microsounds_v5
            (id, title, uri, gain, minIntervalMinutes, maxIntervalMinutes, isEnabled)
        SELECT id, title, uri, gain, minIntervalMinutes, maxIntervalMinutes, isEnabled
        FROM microsounds
    """.trimIndent()

    const val DROP_MICROSOUNDS_OLD_SQL = "DROP TABLE microsounds"
    const val RENAME_MICROSOUNDS_V5_SQL = "ALTER TABLE microsounds_v5 RENAME TO microsounds"

    /*
     * 7 -> 8: плейлисты и системное «Избранное» (id = 1).
     * Имена таблиц, колонок, индексов и внешнего ключа зафиксированы здесь и
     * используются и миграцией, и onCreate: схема свежей установки и апгрейда
     * не может разойтись. Room-кодогенерация в проекте не используется,
     * поэтому схема валидируется тестами и ручной проверкой SQL (STATE.md).
     */
    val CREATE_PLAYLISTS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            isSystem INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_PLAYLIST_TRACKS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS playlist_tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            playlistId INTEGER NOT NULL,
            trackUri TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT,
            addedAt INTEGER NOT NULL,
            FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
        )
    """.trimIndent()

    const val CREATE_PLAYLIST_TRACKS_PLAYLIST_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId " +
            "ON playlist_tracks(playlistId)"

    const val CREATE_PLAYLIST_TRACKS_UNIQUE_INDEX_SQL =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_trackUri " +
            "ON playlist_tracks(playlistId, trackUri)"

    /*
     * 9 -> 10: плейлист, добавленный из каталога «От автора», помнит стабильный id
     * каталога. Один ALTER TABLE ADD COLUMN без NOT NULL/DEFAULT: у всех
     * существующих плейлистов catalogId = NULL, id, имена, даты и треки
     * сохраняются. Уникальный индекс запрещает добавить один каталог дважды;
     * NULL-ы SQLite считает разными, поэтому обычные плейлисты и «Избранное»
     * друг другу не мешают. Отдельная связь «копия -> манифест» не создаётся:
     * описание каталога по-прежнему живёт в кеше манифеста.
     */
    const val ADD_PLAYLIST_CATALOG_ID_SQL = "ALTER TABLE playlists ADD COLUMN catalogId TEXT"

    const val CREATE_PLAYLISTS_CATALOG_INDEX_SQL =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_catalogId ON playlists(catalogId)"

    /*
     * 10 -> 11: будильник может играть плейлист КАТАЛОГА «От автора», которого
     * нет в «Моей коллекции». Один ALTER TABLE ADD COLUMN без NOT NULL/DEFAULT:
     * у всех существующих будильников catalogPlaylistId = NULL, поэтому их
     * источник (TRACK или локальный PLAYLIST с playlistId) не меняется.
     * Каталог в БД не хранится: треки такого плейлиста при срабатывании читаются
     * из кеша манифеста, поэтому связь — стабильный строковый id каталога.
     * Внешнего ключа и индекса нет: плейлиста каталога в таблице playlists может
     * не быть вовсе, а удаление копии не должно ломать будильник.
     */
    const val ADD_ALARM_CATALOG_PLAYLIST_ID_SQL =
        "ALTER TABLE alarms ADD COLUMN catalogPlaylistId TEXT"

    /*
     * 11 -> 12: происхождение трека в библиотеке (Stage 2). Один
     * ALTER TABLE ADD COLUMN с NOT NULL и DEFAULT: SQLite требует non-null
     * DEFAULT для NOT NULL-колонки, поэтому ВСЕ существующие строки получают
     * 'IMPORTED' — они и были добавлены с устройства. Id, URI, названия, артист,
     * длительность и dateAdded не меняются, уникальный индекс по uri остаётся.
     * Скачанные по одному треки каталога получают 'DOWNLOADED' и становятся
     * самостоятельными строками библиотеки; треки, скачанные только в составе
     * плейлиста, в tracks не вставляются.
     */
    val ADD_TRACK_SOURCE_SQL =
        "ALTER TABLE tracks ADD COLUMN source TEXT NOT NULL " +
            "DEFAULT '${TrackSource.IMPORTED.value}'"

    /**
     * 12 -> 13: собственное окно работы микрозвука (Stage 3). Два
     * ALTER TABLE ADD COLUMN без NOT NULL и без DEFAULT: у всех существующих
     * строк windowStartMinutes = NULL и windowEndMinutes = NULL, то есть
     * «весь день» — ровно прежнее поведение. Id, название, URI, усиление,
     * интервалы и флаг включения не переписываются, таблица не пересоздаётся.
     * Окно с переходом через полночь хранится как есть (просто минуты от
     * полуночи), поэтому отдельной колонки «через полночь» нет.
     */
    const val ADD_MICROSOUND_WINDOW_START_SQL =
        "ALTER TABLE microsounds ADD COLUMN windowStartMinutes INTEGER"

    const val ADD_MICROSOUND_WINDOW_END_SQL =
        "ALTER TABLE microsounds ADD COLUMN windowEndMinutes INTEGER"

    /**
     * Системная строка «Избранное»: фиксированный id, пустое имя (UI берёт
     * локализованную подпись), isSystem = 1. INSERT OR IGNORE делает вставку
     * идемпотентной; повторный вызов ничего не меняет и не затирает данные.
     */
    val INSERT_FAVORITES_PLAYLIST_SQL =
        "INSERT OR IGNORE INTO playlists (id, name, isSystem, createdAt) " +
            "VALUES (${PlaylistEntity.FAVORITES_PLAYLIST_ID}, " +
            "'${PlaylistEntity.FAVORITES_NAME}', 1, ${PlaylistEntity.FAVORITES_CREATED_AT})"

    fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            migrateTo4(db)
        }
        if (oldVersion < 5) {
            migrateTo5(db)
        }
        if (oldVersion < 6) {
            migrateTo6(db)
        }
        if (oldVersion < 7) {
            migrateTo7(db)
        }
        if (oldVersion < 8) {
            migrateTo8(db)
        }
        if (oldVersion < 9) {
            migrateTo9(db)
        }
        if (oldVersion < 10) {
            migrateTo10(db)
        }
        if (oldVersion < 11) {
            migrateTo11(db)
        }
        if (oldVersion < 12) {
            migrateTo12(db)
        }
        if (oldVersion < 13) {
            migrateTo13(db)
        }
    }

    /**
     * 3 -> 4: уникальность трека по нормализованному URI.
     * Сначала удаляются дубли (остаётся самая ранняя запись), только потом
     * создаётся уникальный индекс. Id и порядок остальных строк сохраняются.
     */
    private fun migrateTo4(db: SQLiteDatabase) {
        db.execSQL(TRIM_TRACK_URIS_SQL)
        db.execSQL(LOWERCASE_TRACK_URI_SCHEME_SQL)

        val rows = mutableListOf<TrackDedupeRow>()
        db.rawQuery("SELECT id, uri, dateAdded FROM tracks", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    TrackDedupeRow(
                        id = cursor.getLong(0),
                        uri = cursor.getString(1),
                        dateAdded = cursor.getLong(2)
                    )
                )
            }
        }

        val duplicateIds = TrackDeduplication.selectDuplicateIds(rows)
        for (id in duplicateIds) {
            db.delete("tracks", "id = ?", arrayOf(id.toString()))
        }
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 3->4: удалено дублей треков ${duplicateIds.size}, создаётся уникальный индекс URI"
        )

        db.execSQL(CREATE_TRACKS_URI_INDEX_SQL)
    }

    /**
     * 4 -> 5: удаление окна активности из таблицы microsounds.
     * SQLite на minSdk 31 не поддерживает ALTER TABLE DROP COLUMN, поэтому
     * таблица пересоздаётся: все прочие поля и id сохраняются, порядок не меняется.
     */
    private fun migrateTo5(db: SQLiteDatabase) {
        db.execSQL(CREATE_MICROSOUNDS_V5_TABLE_SQL)
        db.execSQL(COPY_MICROSOUNDS_V5_SQL)
        db.execSQL(DROP_MICROSOUNDS_OLD_SQL)
        db.execSQL(RENAME_MICROSOUNDS_V5_SQL)
        Log.i("AppDatabaseMigrations", "Миграция 4->5: таблица microsounds пересоздана без окна активности")
    }

    /** 5 -> 6: у существующих будильников выбранного трека нет (NULL = вся библиотека). */
    private fun migrateTo6(db: SQLiteDatabase) {
        db.execSQL(ADD_ALARM_TRACK_ID_SQL)
        Log.i("AppDatabaseMigrations", "Миграция 5->6: добавлена nullable-колонка trackId, старые будильники играют всю библиотеку")
    }

    /** 6 -> 7: у существующих будильников громкость 80% (значение DEFAULT колонки). */
    private fun migrateTo7(db: SQLiteDatabase) {
        db.execSQL(ADD_ALARM_GAIN_SQL)
        Log.i("AppDatabaseMigrations", "Миграция 6->7: добавлена громкость будильника (DEFAULT 0.8)")
    }

    /**
     * 7 -> 8: только новые таблицы и индексы. Существующие `tracks`, `alarms`,
     * `microsounds` не читаются и не меняются, поэтому данные v1 сохраняются.
     */
    private fun migrateTo8(db: SQLiteDatabase) {
        db.execSQL(CREATE_PLAYLISTS_TABLE_SQL)
        db.execSQL(CREATE_PLAYLIST_TRACKS_TABLE_SQL)
        db.execSQL(CREATE_PLAYLIST_TRACKS_PLAYLIST_INDEX_SQL)
        db.execSQL(CREATE_PLAYLIST_TRACKS_UNIQUE_INDEX_SQL)
        db.execSQL(INSERT_FAVORITES_PLAYLIST_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 7->8: созданы таблицы playlists/playlist_tracks и строка «Избранное» (id 1)"
        )
    }

    /**
     * 8 -> 9: источник звука будильника. Только ALTER TABLE ADD COLUMN:
     * существующие строки получают sourceType = 'TRACK', playlistId = NULL,
     * shuffle = 0, поэтому все старые будильники продолжают играть свой трек.
     * Внешний ключ на playlists НЕ создаётся: удаление плейлиста не должно
     * блокироваться или каскадно менять будильники — это решается при срабатывании.
     */
    private fun migrateTo9(db: SQLiteDatabase) {
        db.execSQL(ADD_ALARM_SOURCE_TYPE_SQL)
        db.execSQL(ADD_ALARM_PLAYLIST_ID_SQL)
        db.execSQL(ADD_ALARM_SHUFFLE_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 8->9: добавлены sourceType ('TRACK'), playlistId (NULL) и shuffle (0)"
        )
    }

    /**
     * 9 -> 10: стабильный id каталога у добавленных плейлистов. Только
     * ALTER TABLE ADD COLUMN + уникальный индекс: существующие плейлисты
     * и их треки не переписываются и не теряются.
     */
    private fun migrateTo10(db: SQLiteDatabase) {
        db.execSQL(ADD_PLAYLIST_CATALOG_ID_SQL)
        db.execSQL(CREATE_PLAYLISTS_CATALOG_INDEX_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 9->10: добавлена nullable-колонка playlists.catalogId " +
                "(NULL у существующих плейлистов) и уникальный индекс"
        )
    }

    /**
     * 10 -> 11: будильник-плейлист каталога, не добавленного в библиотеку.
     * Только ALTER TABLE ADD COLUMN: существующие будильники получают
     * catalogPlaylistId = NULL и продолжают играть свой трек или локальный
     * плейлист. Внешних ключей нет — каталога в БД нет.
     */
    private fun migrateTo11(db: SQLiteDatabase) {
        db.execSQL(ADD_ALARM_CATALOG_PLAYLIST_ID_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 10->11: добавлена nullable-колонка alarms.catalogPlaylistId " +
                "(NULL у существующих будильников)"
        )
    }

    /**
     * 11 -> 12: происхождение трека в библиотеке. Только ALTER TABLE ADD COLUMN
     * с DEFAULT 'IMPORTED': все существующие строки остаются треками пользователя,
     * их id, URI и метаданные не переписываются.
     */
    private fun migrateTo12(db: SQLiteDatabase) {
        db.execSQL(ADD_TRACK_SOURCE_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 11->12: добавлена колонка tracks.source " +
                "(DEFAULT 'IMPORTED' у существующих треков)"
        )
    }

    /**
     * 12 -> 13: собственное окно работы микрозвука. Только две
     * ALTER TABLE ADD COLUMN без NOT NULL/DEFAULT: у всех существующих
     * микрозвуков окна нет (NULL = «весь день»), их данные не меняются.
     */
    private fun migrateTo13(db: SQLiteDatabase) {
        db.execSQL(ADD_MICROSOUND_WINDOW_START_SQL)
        db.execSQL(ADD_MICROSOUND_WINDOW_END_SQL)
        Log.i(
            "AppDatabaseMigrations",
            "Миграция 12->13: добавлены nullable-колонки microsounds.windowStartMinutes " +
                "и windowEndMinutes (NULL у существующих микрозвуков — окно выключено)"
        )
    }
}
