package com.personal.vibratuner.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseMigrationsTest {

    @Test
    fun databaseVersionIsThirteen() {
        assertEquals(13, AppDatabaseMigrations.VERSION)
    }

    @Test
    fun migration12To13AddsNullableMicrosoundWindowColumns() {
        val startSql = AppDatabaseMigrations.ADD_MICROSOUND_WINDOW_START_SQL
        assertTrue(startSql.startsWith("ALTER TABLE microsounds ADD COLUMN windowStartMinutes"))
        assertTrue(startSql.contains("INTEGER"))
        // Без NOT NULL и без DEFAULT: SQLite заполняет существующие строки NULL,
        // то есть «весь день» — ровно прежнее поведение микрозвука.
        assertFalse(startSql.contains("NOT NULL"))
        assertFalse(startSql.contains("DEFAULT"))

        val endSql = AppDatabaseMigrations.ADD_MICROSOUND_WINDOW_END_SQL
        assertTrue(endSql.startsWith("ALTER TABLE microsounds ADD COLUMN windowEndMinutes"))
        assertTrue(endSql.contains("INTEGER"))
        assertFalse(endSql.contains("NOT NULL"))
        assertFalse(endSql.contains("DEFAULT"))
    }

    @Test
    fun migration12To13DoesNotRewriteExistingMicrosoundData() {
        listOf(
            AppDatabaseMigrations.ADD_MICROSOUND_WINDOW_START_SQL,
            AppDatabaseMigrations.ADD_MICROSOUND_WINDOW_END_SQL
        ).forEach { sql ->
            assertTrue(sql.startsWith("ALTER TABLE microsounds ADD COLUMN"))
            assertFalse(sql.contains("DROP"))
            assertFalse(sql.contains("DELETE"))
            assertFalse(sql.contains("UPDATE"))
            assertFalse(sql.contains("RENAME"))
            assertFalse(sql.contains("REFERENCES"))
        }
    }

    @Test
    fun existingMicrosoundRowReadsBackAsNoWindow() {
        // Строка, созданная кодом без окна (и все строки после миграции),
        // означает «весь день»: окно не ограничивает срабатывания.
        val entity = MicrosoundEntity(title = "t", uri = "content://a")
        assertNull(entity.window)
        assertNull(entity.windowStartMinutes)
        assertNull(entity.windowEndMinutes)
    }

    @Test
    fun migration6To7AddsAlarmGainWithDefaultEightyPercent() {
        val sql = AppDatabaseMigrations.ADD_ALARM_GAIN_SQL
        assertTrue(sql.startsWith("ALTER TABLE alarms ADD COLUMN gain"))
        assertTrue(sql.contains("REAL"))
        assertTrue(sql.contains("NOT NULL"))
        assertTrue(sql.contains("DEFAULT 0.8"))
        // Новый будильник по умолчанию имеет ту же громкость 80%.
        assertEquals(0.8f, AlarmEntity(hour = 7, minute = 0).gain, 0.001f)
        assertEquals(0.8f, AlarmEntity.DEFAULT_GAIN, 0.001f)
    }

    @Test
    fun migrationsPreserveExistingAlarmRows() {
        // Обе миграции — ALTER TABLE ADD COLUMN без DROP/пересоздания:
        // id, время, дни и длительность существующих будильников сохраняются.
        assertTrue(AppDatabaseMigrations.ADD_ALARM_TRACK_ID_SQL.startsWith("ALTER TABLE alarms ADD COLUMN"))
        assertTrue(AppDatabaseMigrations.ADD_ALARM_GAIN_SQL.startsWith("ALTER TABLE alarms ADD COLUMN"))
        assertFalse(AppDatabaseMigrations.ADD_ALARM_GAIN_SQL.contains("DROP"))
    }

    @Test
    fun migration5To6AddsNullableTrackIdSoExistingAlarmsGetNull() {
        val sql = AppDatabaseMigrations.ADD_ALARM_TRACK_ID_SQL
        assertTrue(sql.startsWith("ALTER TABLE alarms ADD COLUMN trackId"))
        assertTrue(sql.contains("INTEGER"))
        // Без NOT NULL и без DEFAULT SQLite заполняет существующие строки NULL,
        // то есть старые будильники продолжают играть всю библиотеку.
        assertFalse(sql.contains("NOT NULL"))
        assertFalse(sql.contains("DEFAULT"))
        assertNull(AlarmEntity(hour = 7, minute = 0).trackId)
    }

    @Test
    fun migration5To6RunsOnlyForOlderSchema() {
        // migrate(5, 6) не выполняется для oldVersion >= 6, поэтому повторный
        // запуск не пытается добавить уже существующую колонку.
        assertTrue(AppDatabaseMigrations.ADD_ALARM_TRACK_ID_SQL.contains("alarms"))
    }

    @Test
    fun migration3To4NormalizesUrisBeforeUniqueness() {
        assertTrue(AppDatabaseMigrations.TRIM_TRACK_URIS_SQL.contains("UPDATE tracks SET uri = trim(uri)"))
        assertTrue(AppDatabaseMigrations.LOWERCASE_TRACK_URI_SCHEME_SQL.contains("lower(substr(uri"))
        assertTrue(AppDatabaseMigrations.LOWERCASE_TRACK_URI_SCHEME_SQL.contains("instr(uri, ':')"))
        assertTrue(AppDatabaseMigrations.CREATE_TRACKS_URI_INDEX_SQL.contains("UNIQUE INDEX"))
        assertTrue(AppDatabaseMigrations.CREATE_TRACKS_URI_INDEX_SQL.contains("ON tracks(uri)"))
    }

    @Test
    fun duplicateSelectionPreservesRemainingIdOrder() {
        val rows = listOf(
            TrackDedupeRow(10L, "content://a", 2L),
            TrackDedupeRow(11L, "content://a", 1L),
            TrackDedupeRow(12L, "content://b", 3L)
        )
        val duplicateIds = TrackDeduplication.selectDuplicateIds(rows)
        assertEquals(listOf(10L), duplicateIds)
        assertEquals(listOf(11L, 12L), rows.filter { it.id !in duplicateIds }.map { it.id })
    }

    @Test
    fun migration4To5RebuildsMicrosoundsWithoutActivityWindow() {
        val createSql = AppDatabaseMigrations.CREATE_MICROSOUNDS_V5_TABLE_SQL
        val copySql = AppDatabaseMigrations.COPY_MICROSOUNDS_V5_SQL

        // Новые колонки окна активности отсутствуют
        assertTrue(!createSql.contains("startHour"))
        assertTrue(!createSql.contains("endHour"))
        assertTrue(!copySql.contains("startHour"))
        assertTrue(!copySql.contains("endHour"))

        // Все остальные поля и id сохраняются при переносе
        val preservedColumns = listOf(
            "id", "title", "uri", "gain", "minIntervalMinutes", "maxIntervalMinutes", "isEnabled"
        )
        preservedColumns.forEach { column ->
            assertTrue("В CREATE нет колонки $column", createSql.contains(column))
            assertTrue("В COPY нет колонки $column", copySql.contains(column))
        }
    }

    @Test
    fun migration4To5DropsOldTableOnlyAfterCopy() {
        assertTrue(AppDatabaseMigrations.DROP_MICROSOUNDS_OLD_SQL.startsWith("DROP TABLE"))
        assertTrue(AppDatabaseMigrations.RENAME_MICROSOUNDS_V5_SQL.contains("RENAME TO microsounds"))
        assertTrue(AppDatabaseMigrations.CREATE_MICROSOUNDS_V5_TABLE_SQL.contains("microsounds_v5"))
    }

    @Test
    fun migration7To8CreatesPlaylistsTableWithRequiredColumns() {
        val sql = AppDatabaseMigrations.CREATE_PLAYLISTS_TABLE_SQL
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS playlists"))
        assertTrue(sql.contains("id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(sql.contains("name TEXT NOT NULL"))
        assertTrue(sql.contains("isSystem INTEGER NOT NULL DEFAULT 0"))
        assertTrue(sql.contains("createdAt INTEGER NOT NULL"))
        // Миграция ничего не удаляет и не меняет в существующих таблицах.
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("DELETE"))
        assertFalse(sql.contains("ALTER"))
    }

    @Test
    fun migration7To8CreatesPlaylistTracksTableWithCascade() {
        val sql = AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_TABLE_SQL
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS playlist_tracks"))
        assertTrue(sql.contains("playlistId INTEGER NOT NULL"))
        assertTrue(sql.contains("trackUri TEXT NOT NULL"))
        assertTrue(sql.contains("title TEXT NOT NULL"))
        assertTrue(sql.contains("artist TEXT,"))
        assertFalse(sql.contains("artist TEXT NOT NULL"))
        assertTrue(sql.contains("addedAt INTEGER NOT NULL"))
        assertTrue(sql.contains("FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE"))
    }

    @Test
    fun migration7To8CreatesPlaylistIndexes() {
        val playlistIndex = AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_PLAYLIST_INDEX_SQL
        assertTrue(playlistIndex.contains("INDEX IF NOT EXISTS index_playlist_tracks_playlistId"))
        assertTrue(playlistIndex.contains("ON playlist_tracks(playlistId)"))

        val uniqueIndex = AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_UNIQUE_INDEX_SQL
        assertTrue(uniqueIndex.contains("UNIQUE INDEX IF NOT EXISTS"))
        assertTrue(uniqueIndex.contains("index_playlist_tracks_playlistId_trackUri"))
        assertTrue(uniqueIndex.contains("ON playlist_tracks(playlistId, trackUri)"))
    }

    @Test
    fun migration7To8InsertsFavoritesIdempotentlyWithFixedId() {
        val sql = AppDatabaseMigrations.INSERT_FAVORITES_PLAYLIST_SQL
        assertTrue(sql.startsWith("INSERT OR IGNORE INTO playlists"))
        assertTrue(sql.contains("(id, name, isSystem, createdAt)"))
        // Пустое имя: подпись «Избранное» приходит из strings.xml, а не из БД.
        assertTrue(sql.contains("VALUES (1, '', 1, 0)"))
        assertEquals(1L, PlaylistEntity.FAVORITES_PLAYLIST_ID)
        assertEquals("", PlaylistEntity.FAVORITES_NAME)
    }

    @Test
    fun migration8To9AddsAlarmSourceColumnsWithMatchingDefaults() {
        val sourceSql = AppDatabaseMigrations.ADD_ALARM_SOURCE_TYPE_SQL
        assertTrue(sourceSql.startsWith("ALTER TABLE alarms ADD COLUMN sourceType"))
        assertTrue(sourceSql.contains("TEXT"))
        assertTrue(sourceSql.contains("NOT NULL"))
        assertTrue(sourceSql.contains("DEFAULT 'TRACK'"))

        val playlistSql = AppDatabaseMigrations.ADD_ALARM_PLAYLIST_ID_SQL
        assertTrue(playlistSql.startsWith("ALTER TABLE alarms ADD COLUMN playlistId"))
        assertTrue(playlistSql.contains("INTEGER"))
        // Без NOT NULL/DEFAULT: у старых будильников playlistId = NULL.
        assertFalse(playlistSql.contains("NOT NULL"))
        assertFalse(playlistSql.contains("DEFAULT"))
        // Внешнего ключа быть не должно: удаление плейлиста не ломает будильники.
        assertFalse(playlistSql.contains("REFERENCES"))

        val shuffleSql = AppDatabaseMigrations.ADD_ALARM_SHUFFLE_SQL
        assertTrue(shuffleSql.startsWith("ALTER TABLE alarms ADD COLUMN shuffle"))
        assertTrue(shuffleSql.contains("INTEGER"))
        assertTrue(shuffleSql.contains("NOT NULL DEFAULT 0"))
    }

    @Test
    fun migration8To9DefaultsKeepExistingAlarmsAsTrackSource() {
        // DEFAULT в SQL и @ColumnInfo(defaultValue) в AlarmEntity совпадают.
        assertEquals("TRACK", AlarmSoundSource.TRACK.value)
        assertEquals("PLAYLIST", AlarmSoundSource.PLAYLIST.value)
        val alarm = AlarmEntity(hour = 7, minute = 0)
        assertEquals(AlarmSoundSource.TRACK.value, alarm.sourceType)
        assertNull(alarm.playlistId)
        assertFalse(alarm.shuffle)
        assertFalse(alarm.isPlaylistSource)
        assertTrue(
            alarm.copy(
                sourceType = AlarmSoundSource.PLAYLIST.value,
                playlistId = 5L,
                shuffle = true
            ).isPlaylistSource
        )
    }

    @Test
    fun migration8To9OnlyAddsColumnsAndKeepsData() {
        listOf(
            AppDatabaseMigrations.ADD_ALARM_SOURCE_TYPE_SQL,
            AppDatabaseMigrations.ADD_ALARM_PLAYLIST_ID_SQL,
            AppDatabaseMigrations.ADD_ALARM_SHUFFLE_SQL
        ).forEach { sql ->
            assertTrue(sql.startsWith("ALTER TABLE alarms ADD COLUMN"))
            assertFalse(sql.contains("DROP"))
            assertFalse(sql.contains("DELETE"))
            assertFalse(sql.contains("UPDATE"))
        }
    }

    @Test
    fun migration9To10AddsNullableCatalogIdWithoutLosingData() {
        val sql = AppDatabaseMigrations.ADD_PLAYLIST_CATALOG_ID_SQL
        assertTrue(sql.startsWith("ALTER TABLE playlists ADD COLUMN catalogId"))
        assertTrue(sql.contains("TEXT"))
        // Без NOT NULL/DEFAULT: у всех существующих плейлистов catalogId = NULL.
        assertFalse(sql.contains("NOT NULL"))
        assertFalse(sql.contains("DEFAULT"))
        // Данные существующих плейлистов не удаляются и не переписываются.
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("DELETE"))
        assertFalse(sql.contains("UPDATE"))
        assertFalse(sql.contains("REFERENCES"))
        assertNull(PlaylistEntity(name = "Плейлист").catalogId)
    }

    @Test
    fun migration9To10CreatesUniqueCatalogIndexSoOneCatalogAddedOnce() {
        val sql = AppDatabaseMigrations.CREATE_PLAYLISTS_CATALOG_INDEX_SQL
        assertTrue(sql.contains("UNIQUE INDEX IF NOT EXISTS index_playlists_catalogId"))
        assertTrue(sql.contains("ON playlists(catalogId)"))
        // _IF NOT EXISTS_ делает миграцию идемпотентной для повторного запуска.
        assertTrue(sql.contains("IF NOT EXISTS"))
    }

    @Test
    fun catalogPlaylistKeepsStableCatalogId() {
        val playlist = PlaylistEntity(name = "Утро", catalogId = "morning-soft")
        assertEquals("morning-soft", playlist.catalogId)
        assertFalse(playlist.isSystem)
        assertNull(playlist.copy(catalogId = null).catalogId)
    }

    @Test
    fun migration10To11AddsNullableCatalogPlaylistIdWithoutLosingData() {
        val sql = AppDatabaseMigrations.ADD_ALARM_CATALOG_PLAYLIST_ID_SQL
        assertTrue(sql.startsWith("ALTER TABLE alarms ADD COLUMN catalogPlaylistId"))
        assertTrue(sql.contains("TEXT"))
        // Без NOT NULL/DEFAULT: у всех существующих будильников значение NULL,
        // поэтому их источник (TRACK или локальный PLAYLIST с playlistId) не меняется.
        assertFalse(sql.contains("NOT NULL"))
        assertFalse(sql.contains("DEFAULT"))
        assertFalse(sql.contains("REFERENCES"))
        // Данные будильников не удаляются и не переписываются.
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("DELETE"))
        assertFalse(sql.contains("UPDATE"))
    }

    @Test
    fun catalogPlaylistAlarmKeepsStableCatalogId() {
        val alarm = AlarmEntity(
            hour = 7,
            minute = 0,
            sourceType = AlarmSoundSource.PLAYLIST.value,
            catalogPlaylistId = "morning-soft"
        )
        assertEquals("morning-soft", alarm.catalogPlaylistId)
        assertTrue(alarm.isPlaylistSource)
        assertTrue(alarm.isCatalogPlaylistSource)
        assertNull(alarm.playlistId)
        // У локального плейлиста каталога нет, у трека нет ни того, ни другого.
        assertFalse(
            alarm.copy(catalogPlaylistId = null, playlistId = 5L).isCatalogPlaylistSource
        )
        assertFalse(alarm.copy(sourceType = AlarmSoundSource.TRACK.value).isCatalogPlaylistSource)
        // Новый будильник по умолчанию — без каталога (как у старых строк после миграции).
        assertNull(AlarmEntity(hour = 7, minute = 0).catalogPlaylistId)
    }

    @Test
    fun migration11To12AddsTrackSourceWithImportedDefault() {
        val sql = AppDatabaseMigrations.ADD_TRACK_SOURCE_SQL
        assertTrue(sql.startsWith("ALTER TABLE tracks ADD COLUMN source"))
        assertTrue(sql.contains("TEXT"))
        assertTrue(sql.contains("NOT NULL"))
        // SQLite требует non-null DEFAULT для NOT NULL-колонки: у всех старых
        // строк появляется 'IMPORTED', то есть они не меняются по смыслу.
        assertTrue(sql.contains("DEFAULT 'IMPORTED'"))
        // Данные существующих треков не удаляются и не переписываются.
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("DELETE"))
        assertFalse(sql.contains("UPDATE"))
        assertFalse(sql.contains("REFERENCES"))
    }

    @Test
    fun trackSourceDistinguishesImportedAndDownloaded() {
        assertEquals("IMPORTED", TrackSource.IMPORTED.value)
        assertEquals("DOWNLOADED", TrackSource.DOWNLOADED.value)
        // Неизвестное/пустое значение — обычный добавленный файл.
        assertEquals(TrackSource.IMPORTED, TrackSource.fromValue(null))
        assertEquals(TrackSource.IMPORTED, TrackSource.fromValue(""))
        assertEquals(TrackSource.IMPORTED, TrackSource.fromValue("SOMETHING"))
        assertEquals(TrackSource.DOWNLOADED, TrackSource.fromValue("DOWNLOADED"))
        // Строка, созданная кодом без source, остаётся IMPORTED (совместимо с v11).
        assertEquals(
            TrackSource.IMPORTED.value,
            TrackEntity(uri = "content://a", title = "A").source
        )
        assertEquals(
            TrackSource.DOWNLOADED.value,
            TrackEntity(
                uri = "https://example.com/a.mp3",
                title = "A",
                source = TrackSource.DOWNLOADED.value
            ).source
        )
    }
}
