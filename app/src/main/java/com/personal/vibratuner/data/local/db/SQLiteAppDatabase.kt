package com.personal.vibratuner.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Надежная SQLite-реализация базы данных приложения.
 * Обеспечивает полную изоляцию от несовместимостей версий кодогенерации компилятора Room
 * с современными версиями Kotlin и AGP, сохраняя полную контрактую совместимость с DAO и Flow.
 */
class SQLiteAppDatabase(context: Context) : AppDatabase() {

    private val dbHelper = DatabaseHelper(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val alarmDaoImpl = SQLiteAlarmDao(dbHelper, scope)
    private val playlistDaoImpl = SQLitePlaylistDao(dbHelper, scope)
    private val trackDaoImpl = SQLiteTrackDao(dbHelper, scope) {
        // Удаление трека каскадно чистит и ссылки будильников, и связи плейлистов.
        alarmDaoImpl.refresh()
        playlistDaoImpl.refresh()
    }
    private val microsoundDaoImpl = SQLiteMicrosoundDao(dbHelper, scope)

    override fun trackDao(): TrackDao = trackDaoImpl
    override fun alarmDao(): AlarmDao = alarmDaoImpl
    override fun microsoundDao(): MicrosoundDao = microsoundDaoImpl
    override fun playlistDao(): PlaylistDao = playlistDaoImpl

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, "vibra_tuner.db", null, AppDatabaseMigrations.VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tracks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uri TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    durationMs INTEGER NOT NULL,
                    dateAdded INTEGER NOT NULL,
                    source TEXT NOT NULL DEFAULT 'IMPORTED'
                )
                """.trimIndent()
            )
            db.execSQL(AppDatabaseMigrations.CREATE_TRACKS_URI_INDEX_SQL)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS alarms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    hour INTEGER NOT NULL,
                    minute INTEGER NOT NULL,
                    durationMinutes INTEGER NOT NULL,
                    isEnabled INTEGER NOT NULL,
                    daysOfWeek TEXT NOT NULL,
                    label TEXT NOT NULL,
                    trackId INTEGER,
                    gain REAL NOT NULL DEFAULT 0.8,
                    sourceType TEXT NOT NULL DEFAULT 'TRACK',
                    playlistId INTEGER,
                    catalogPlaylistId TEXT,
                    shuffle INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS microsounds (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    gain REAL NOT NULL,
                    minIntervalMinutes INTEGER NOT NULL,
                    maxIntervalMinutes INTEGER NOT NULL,
                    isEnabled INTEGER NOT NULL,
                    windowStartMinutes INTEGER,
                    windowEndMinutes INTEGER
                )
                """.trimIndent()
            )
            // Схема плейлистов совпадает с миграцией 7 -> 8: те же SQL-константы.
            db.execSQL(AppDatabaseMigrations.CREATE_PLAYLISTS_TABLE_SQL)
            db.execSQL(AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_TABLE_SQL)
            db.execSQL(AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_PLAYLIST_INDEX_SQL)
            db.execSQL(AppDatabaseMigrations.CREATE_PLAYLIST_TRACKS_UNIQUE_INDEX_SQL)
            // v10: стабильный id каталога у добавленного плейлиста — те же SQL, что в миграции 9 -> 10.
            db.execSQL(AppDatabaseMigrations.ADD_PLAYLIST_CATALOG_ID_SQL)
            db.execSQL(AppDatabaseMigrations.CREATE_PLAYLISTS_CATALOG_INDEX_SQL)
            db.execSQL(AppDatabaseMigrations.INSERT_FAVORITES_PLAYLIST_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Только последовательные миграции с сохранением данных.
            // Destructive fallback запрещён правилами проекта.
            AppDatabaseMigrations.migrate(db, oldVersion, newVersion)
        }
    }

    private class SQLiteTrackDao(
        private val dbHelper: DatabaseHelper,
        private val scope: CoroutineScope,
        private val onTrackReferencesCleared: () -> Unit
    ) : TrackDao {
        private val _flow = MutableStateFlow<List<TrackEntity>>(emptyList())

        init {
            refresh()
        }

        private fun refresh() {
            scope.launch {
                val list = queryAll()
                _flow.value = list
            }
        }

        private fun queryAll(): List<TrackEntity> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, uri, title, artist, durationMs, dateAdded, source " +
                    "FROM tracks ORDER BY dateAdded ASC",
                null
            )
            val list = mutableListOf<TrackEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        TrackEntity(
                            id = it.getLong(0),
                            uri = it.getString(1),
                            title = it.getString(2),
                            artist = it.getString(3),
                            durationMs = it.getLong(4),
                            dateAdded = it.getLong(5),
                            source = it.getString(6)
                        )
                    )
                }
            }
            return list
        }

        override fun getAllTracksFlow(): Flow<List<TrackEntity>> = _flow.asStateFlow()

        override suspend fun getAllTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
            queryAll()
        }

        override suspend fun getTrackById(id: Long): TrackEntity? =
            withContext(Dispatchers.IO) {
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, uri, title, artist, durationMs, dateAdded, source " +
                        "FROM tracks WHERE id = ? LIMIT 1",
                    arrayOf(id.toString())
                )
                cursor.use {
                    if (!it.moveToFirst()) return@withContext null
                    TrackEntity(
                        id = it.getLong(0),
                        uri = it.getString(1),
                        title = it.getString(2),
                        artist = it.getString(3),
                        durationMs = it.getLong(4),
                        dateAdded = it.getLong(5),
                        source = it.getString(6)
                    )
                }
            }

        /** URI треков указанного происхождения (Stage 2: защита скачанных по одному). */
        override suspend fun getUrisBySource(source: String): List<String> =
            withContext(Dispatchers.IO) {
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    "SELECT uri FROM tracks WHERE source = ?",
                    arrayOf(source)
                )
                val uris = mutableListOf<String>()
                cursor.use {
                    while (it.moveToNext()) {
                        uris.add(it.getString(0))
                    }
                }
                uris
            }

        /**
         * Строки с неизвестной длительностью, которые могут быть скачанными
         * файлами (Stage 3): указанное происхождение ИЛИ https-URI (так выглядят
         * треки, скачанные до появления колонки `source` — миграция 11 -> 12
         * пометила их IMPORTED). Материал для дозаполнения из локального файла.
         */
        override suspend fun getTracksWithoutDuration(source: String): List<TrackEntity> =
            withContext(Dispatchers.IO) {
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, uri, title, artist, durationMs, dateAdded, source " +
                        "FROM tracks WHERE durationMs <= 0 " +
                        "AND (source = ? OR uri LIKE 'https://%')",
                    arrayOf(source)
                )
                val list = mutableListOf<TrackEntity>()
                cursor.use {
                    while (it.moveToNext()) {
                        list.add(
                            TrackEntity(
                                id = it.getLong(0),
                                uri = it.getString(1),
                                title = it.getString(2),
                                artist = it.getString(3),
                                durationMs = it.getLong(4),
                                dateAdded = it.getLong(5),
                                source = it.getString(6)
                            )
                        )
                    }
                }
                list
            }

        /**
         * Заполняет длительность ТОЛЬКО если она ещё неизвестна (Stage 3):
         * уже известное значение не перезаписывается. URI сравнивается в той же
         * нормализованной форме, в которой он хранится (см. [insertTracks]).
         */
        override suspend fun updateDurationIfMissing(uri: String, durationMs: Long): Int =
            withContext(Dispatchers.IO) {
                if (durationMs <= 0L) return@withContext 0
                val db = dbHelper.writableDatabase
                val updated = db.update(
                    "tracks",
                    ContentValues().apply { put("durationMs", durationMs) },
                    "uri = ? AND durationMs <= 0",
                    arrayOf(TrackUriNormalizer.normalize(uri))
                )
                if (updated > 0) refresh()
                updated
            }

        override suspend fun insertTracks(tracks: List<TrackEntity>): List<Long> =
            withContext(Dispatchers.IO) {
                val db = dbHelper.writableDatabase
                val insertedIds = mutableListOf<Long>()
                db.beginTransaction()
                try {
                    for (t in tracks) {
                        val values = ContentValues().apply {
                            if (t.id > 0) put("id", t.id)
                            put("uri", TrackUriNormalizer.normalize(t.uri))
                            put("title", t.title)
                            put("artist", t.artist)
                            put("durationMs", t.durationMs)
                            put("dateAdded", t.dateAdded)
                            put("source", TrackSource.fromValue(t.source).value)
                        }
                        insertedIds.add(
                            db.insertWithOnConflict(
                                "tracks",
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_IGNORE
                            )
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                refresh()
                insertedIds
            }

        override suspend fun deleteTrack(track: TrackEntity) = withContext(Dispatchers.IO) {
            deleteById(track.id)
        }

        override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                // Сначала сбрасываем ссылки будильников (ON DELETE SET NULL не включён),
                // затем удаляем связи плейлистов по тому же URI и сам трек: удаление
                // трека из библиотеки каскадно убирает его из плейлистов.
                db.execSQL(AlarmTrackReference.CLEAR_BY_TRACK_ID_SQL, arrayOf<Any>(id))
                db.execSQL(DELETE_PLAYLIST_LINKS_OF_TRACK_SQL, arrayOf<Any>(id))
                db.delete("tracks", "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            refresh()
            onTrackReferencesCleared()
        }

        override suspend fun clearAll() = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(AlarmTrackReference.CLEAR_ALL_SQL)
                // Только связи библиотечных треков: https-строки «От автора» в tracks не входят.
                db.execSQL(DELETE_PLAYLIST_LINKS_OF_ALL_LIBRARY_TRACKS_SQL)
                db.delete("tracks", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            refresh()
            onTrackReferencesCleared()
        }

        private companion object {
            const val DELETE_PLAYLIST_LINKS_OF_TRACK_SQL =
                "DELETE FROM playlist_tracks WHERE trackUri IN (SELECT uri FROM tracks WHERE id = ?)"

            const val DELETE_PLAYLIST_LINKS_OF_ALL_LIBRARY_TRACKS_SQL =
                "DELETE FROM playlist_tracks WHERE trackUri IN (SELECT uri FROM tracks)"
        }
    }

    private class SQLiteAlarmDao(
        private val dbHelper: DatabaseHelper,
        private val scope: CoroutineScope
    ) : AlarmDao {
        private val _flow = MutableStateFlow<List<AlarmEntity>>(emptyList())

        /** Сколько будильников используют каждый плейлист (DELETE-предупреждение). */
        private val _playlistUsage = MutableStateFlow<List<PlaylistAlarmUsage>>(emptyList())

        init {
            refresh()
        }

        /** Перечитывает будильники: вызывается и при удалении трека, сбрасывающем trackId. */
        fun refresh() {
            scope.launch {
                val list = queryAll()
                _flow.value = list
                _playlistUsage.value = queryPlaylistUsage()
            }
        }

        /**
         * Число будильников с источником PLAYLIST по каждому плейлисту.
         * Отдельный SQL-запрос (не расчёт по _flow), чтобы значение было точным
         * сразу после любой мутации будильников.
         */
        private fun queryPlaylistUsage(): List<PlaylistAlarmUsage> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT playlistId, COUNT(*) FROM alarms " +
                    "WHERE sourceType = ? AND playlistId IS NOT NULL GROUP BY playlistId",
                arrayOf(AlarmSoundSource.PLAYLIST.value)
            )
            val usage = mutableListOf<PlaylistAlarmUsage>()
            cursor.use {
                while (it.moveToNext()) {
                    usage.add(
                        PlaylistAlarmUsage(
                            playlistId = it.getLong(0),
                            alarmCount = it.getInt(1)
                        )
                    )
                }
            }
            return usage
        }

        private fun queryAll(): List<AlarmEntity> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, hour, minute, durationMinutes, isEnabled, daysOfWeek, label, trackId, gain, sourceType, playlistId, shuffle, catalogPlaylistId FROM alarms ORDER BY hour ASC, minute ASC",
                null
            )
            val list = mutableListOf<AlarmEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(readAlarm(it))
                }
            }
            return list
        }

        /** Единственное чтение строки alarms: порядок колонок совпадает во всех запросах. */
        private fun readAlarm(cursor: Cursor): AlarmEntity = AlarmEntity(
            id = cursor.getLong(0),
            hour = cursor.getInt(1),
            minute = cursor.getInt(2),
            durationMinutes = cursor.getInt(3),
            isEnabled = cursor.getInt(4) == 1,
            daysOfWeek = cursor.getString(5),
            label = cursor.getString(6),
            trackId = if (cursor.isNull(7)) null else cursor.getLong(7),
            gain = cursor.getFloat(8),
            sourceType = cursor.getString(9),
            playlistId = if (cursor.isNull(10)) null else cursor.getLong(10),
            shuffle = cursor.getInt(11) == 1,
            catalogPlaylistId = if (cursor.isNull(12)) null else cursor.getString(12)
        )

        override fun getAllAlarmsFlow(): Flow<List<AlarmEntity>> = _flow.asStateFlow()

        override fun getPlaylistAlarmUsageFlow(): Flow<List<PlaylistAlarmUsage>> =
            _playlistUsage.asStateFlow()

        override suspend fun getEnabledAlarms(): List<AlarmEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, hour, minute, durationMinutes, isEnabled, daysOfWeek, label, trackId, gain, sourceType, playlistId, shuffle, catalogPlaylistId FROM alarms WHERE isEnabled = 1 ORDER BY hour ASC, minute ASC",
                null
            )
            val list = mutableListOf<AlarmEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(readAlarm(it))
                }
            }
            list
        }

        override suspend fun getAlarmById(id: Long): AlarmEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, hour, minute, durationMinutes, isEnabled, daysOfWeek, label, trackId, gain, sourceType, playlistId, shuffle, catalogPlaylistId FROM alarms WHERE id = ? LIMIT 1",
                arrayOf(id.toString())
            )
            cursor.use {
                if (it.moveToNext()) readAlarm(it) else null
            }
        }

        override suspend fun insertAlarm(alarm: AlarmEntity): Long = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = alarmValues(alarm).apply {
                if (alarm.id > 0) put("id", alarm.id)
            }
            val id = db.insertWithOnConflict("alarms", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            refresh()
            id
        }

        override suspend fun updateAlarm(alarm: AlarmEntity) = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.update("alarms", alarmValues(alarm), "id = ?", arrayOf(alarm.id.toString()))
            refresh()
        }

        private fun alarmValues(alarm: AlarmEntity) = ContentValues().apply {
            put("hour", alarm.hour)
            put("minute", alarm.minute)
            put("durationMinutes", alarm.durationMinutes)
            put("isEnabled", if (alarm.isEnabled) 1 else 0)
            put("daysOfWeek", alarm.daysOfWeek)
            put("label", alarm.label)
            put("trackId", alarm.trackId)
            put("gain", alarm.gain)
            put("sourceType", alarm.sourceType)
            put("playlistId", alarm.playlistId)
            put("catalogPlaylistId", alarm.catalogPlaylistId)
            put("shuffle", if (alarm.shuffle) 1 else 0)
        }

        override suspend fun deleteAlarm(alarm: AlarmEntity) = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.delete("alarms", "id = ?", arrayOf(alarm.id.toString()))
            refresh()
        }
    }

    private class SQLiteMicrosoundDao(
        private val dbHelper: DatabaseHelper,
        private val scope: CoroutineScope
    ) : MicrosoundDao {
        private val _flow = MutableStateFlow<List<MicrosoundEntity>>(emptyList())

        init {
            refresh()
        }

        private fun refresh() {
            scope.launch {
                val list = queryAll()
                _flow.value = list
            }
        }

        private fun queryAll(): List<MicrosoundEntity> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "$MICROSOUND_COLUMNS FROM microsounds ORDER BY id ASC",
                null
            )
            val list = mutableListOf<MicrosoundEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(readMicrosound(it))
                }
            }
            return list
        }

        /**
         * Единственное чтение строки microsounds: порядок колонок совпадает
         * во всех запросах и в [MICROSOUND_COLUMNS]. Окно работы необязательно:
         * NULL в обеих колонках означает «весь день».
         */
        private fun readMicrosound(cursor: Cursor): MicrosoundEntity = MicrosoundEntity(
            id = cursor.getLong(0),
            title = cursor.getString(1),
            uri = cursor.getString(2),
            gain = cursor.getFloat(3),
            minIntervalMinutes = cursor.getInt(4),
            maxIntervalMinutes = cursor.getInt(5),
            isEnabled = cursor.getInt(6) == 1,
            windowStartMinutes = if (cursor.isNull(7)) null else cursor.getInt(7),
            windowEndMinutes = if (cursor.isNull(8)) null else cursor.getInt(8)
        )

        override fun getAllMicrosoundsFlow(): Flow<List<MicrosoundEntity>> = _flow.asStateFlow()

        override suspend fun getMicrosoundById(id: Long): MicrosoundEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "$MICROSOUND_COLUMNS FROM microsounds WHERE id = ? LIMIT 1",
                arrayOf(id.toString())
            )
            cursor.use {
                if (it.moveToNext()) readMicrosound(it) else null
            }
        }

        override suspend fun getEnabledMicrosounds(): List<MicrosoundEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "$MICROSOUND_COLUMNS FROM microsounds WHERE isEnabled = 1",
                null
            )
            val list = mutableListOf<MicrosoundEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(readMicrosound(it))
                }
            }
            list
        }

        override suspend fun insertMicrosound(entity: MicrosoundEntity): Long = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = microsoundValues(entity).apply {
                if (entity.id > 0) put("id", entity.id)
            }
            val id = db.insertWithOnConflict("microsounds", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            refresh()
            id
        }

        override suspend fun updateMicrosound(entity: MicrosoundEntity) = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.update("microsounds", microsoundValues(entity), "id = ?", arrayOf(entity.id.toString()))
            refresh()
        }

        /**
         * Значения строки микрозвука. Выключенное окно пишется как NULL в обе
         * колонки, поэтому `window` сущности читается обратно как «весь день».
         */
        private fun microsoundValues(entity: MicrosoundEntity) = ContentValues().apply {
            put("title", entity.title)
            put("uri", entity.uri)
            put("gain", entity.gain)
            put("minIntervalMinutes", entity.minIntervalMinutes)
            put("maxIntervalMinutes", entity.maxIntervalMinutes)
            put("isEnabled", if (entity.isEnabled) 1 else 0)
            val window = entity.window
            put("windowStartMinutes", window?.startMinuteOfDay)
            put("windowEndMinutes", window?.endMinuteOfDay)
        }

        override suspend fun deleteMicrosound(entity: MicrosoundEntity) = withContext(Dispatchers.IO) {
            deleteMicrosoundById(entity.id)
        }

        override suspend fun deleteMicrosoundById(id: Long) = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.delete("microsounds", "id = ?", arrayOf(id.toString()))
            refresh()
        }

        private companion object {
            const val MICROSOUND_COLUMNS =
                "SELECT id, title, uri, gain, minIntervalMinutes, maxIntervalMinutes, " +
                    "isEnabled, windowStartMinutes, windowEndMinutes"
        }
    }

    /**
     * Нативная реализация плейлистов. После каждой мутации увеличивается
     * [revision], и все Flow перезапрашивают данные на `Dispatchers.IO`.
     * Внешние ключи SQLite в проекте не включены, поэтому удаление плейлиста
     * сначала удаляет его треки в той же транзакции (каскад объявлен в схеме,
     * но сам по себе не сработал бы).
     */
    private class SQLitePlaylistDao(
        private val dbHelper: DatabaseHelper,
        private val scope: CoroutineScope
    ) : PlaylistDao {

        private val revision = MutableStateFlow(0L)

        init {
            // «Избранное» существует сразу после запуска приложения, ещё до UI.
            scope.launch { ensureSystemPlaylist() }
        }

        private fun bumpRevision() {
            revision.update { it + 1L }
        }

        /** Перечитывает плейлисты после внешней мутации (удаление трека из библиотеки). */
        fun refresh() {
            scope.launch {
                ensureFavorites()
                bumpRevision()
            }
        }

        /** Вставляет системную строку; true, если её действительно не было. */
        private fun ensureFavoritesRow(): Boolean {
            val db = dbHelper.writableDatabase
            val inserted = db.insertWithOnConflict(
                "playlists",
                null,
                ContentValues().apply {
                    put("id", PlaylistEntity.FAVORITES_PLAYLIST_ID)
                    put("name", PlaylistEntity.FAVORITES_NAME)
                    put("isSystem", 1)
                    put("createdAt", PlaylistEntity.FAVORITES_CREATED_AT)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            return inserted != -1L
        }

        private fun ensureFavorites() {
            if (ensureFavoritesRow()) bumpRevision()
        }

        override suspend fun ensureSystemPlaylist() = withContext(Dispatchers.IO) {
            ensureFavorites()
        }

        override fun playlistsWithCounts(): Flow<List<PlaylistSummary>> = flow {
            ensureFavorites()
            emitAll(revision.map { queryPlaylistsWithCounts() })
        }.flowOn(Dispatchers.IO)

        override fun tracksOf(playlistId: Long): Flow<List<PlaylistTrackEntity>> = flow {
            ensureFavorites()
            emitAll(revision.map { queryTracks(playlistId) })
        }.flowOn(Dispatchers.IO)

        override fun favoriteUris(): Flow<Set<String>> = flow {
            ensureFavorites()
            emitAll(revision.map { queryFavoriteUris() })
        }.flowOn(Dispatchers.IO)

        override fun allPlaylistTrackUris(): Flow<Set<String>> = flow {
            ensureFavorites()
            emitAll(revision.map { queryAllPlaylistTrackUris() })
        }.flowOn(Dispatchers.IO)

        override fun addedCatalogIds(): Flow<Set<String>> = flow {
            ensureFavorites()
            emitAll(revision.map { queryAddedCatalogIds() })
        }.flowOn(Dispatchers.IO)

        override suspend fun getCatalogCopyCandidates(): List<PlaylistCopyCandidate> =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    """
                    SELECT p.id, p.name, t.trackUri
                    FROM playlists p
                    LEFT JOIN playlist_tracks t ON t.playlistId = p.id
                    WHERE p.catalogId IS NULL AND p.isSystem = 0
                    ORDER BY p.id ASC, t.addedAt ASC, t.id ASC
                    """.trimIndent(),
                    null
                )
                val byId = LinkedHashMap<Long, PlaylistCopyCandidate>()
                cursor.use { rows ->
                    while (rows.moveToNext()) {
                        val id = rows.getLong(0)
                        val name = rows.getString(1)
                        val uri = if (rows.isNull(2)) null else rows.getString(2)
                        val existing = byId[id]
                        byId[id] = when {
                            existing == null -> PlaylistCopyCandidate(
                                playlistId = id,
                                name = name,
                                trackUris = if (uri != null) listOf(uri) else emptyList()
                            )

                            uri != null -> existing.copy(trackUris = existing.trackUris + uri)
                            else -> existing
                        }
                    }
                }
                byId.values.toList()
            }

        override suspend fun assignCatalogId(playlistId: Long, catalogId: String): Boolean =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                val updated = try {
                    dbHelper.writableDatabase.update(
                        "playlists",
                        ContentValues().apply { put("catalogId", catalogId) },
                        "id = ? AND isSystem = 0 AND catalogId IS NULL",
                        arrayOf(playlistId.toString())
                    )
                } catch (e: Exception) {
                    // Уникальный индекс catalogId: такой каталог уже добавлен —
                    // второй строки не создаём, данные не трогаем.
                    Log.w(
                        TAG,
                        "Не удалось восстановить catalogId у плейлиста id=$playlistId: ${e.message}"
                    )
                    0
                }
                if (updated > 0) bumpRevision()
                updated > 0
            }

        override suspend fun getTracksOnce(playlistId: Long): List<PlaylistTrackEntity> =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                queryTracks(playlistId)
            }

        override suspend fun getPlaylist(id: Long): PlaylistEntity? = withContext(Dispatchers.IO) {
            ensureFavorites()
            queryPlaylist(id)
        }

        override suspend fun getPlaylistByCatalogId(catalogId: String): PlaylistEntity? =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                queryPlaylistByCatalogId(catalogId)
            }

        override suspend fun insertPlaylist(
            name: String,
            createdAt: Long,
            catalogId: String?
        ): Long = withContext(Dispatchers.IO) {
            ensureFavorites()
            val db = dbHelper.writableDatabase
            val id = db.insert(
                "playlists",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("isSystem", 0)
                    put("createdAt", createdAt)
                    put("catalogId", catalogId)
                }
            )
            if (id != -1L) bumpRevision()
            id
        }

        override suspend fun renamePlaylist(id: Long, name: String): Boolean =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                val db = dbHelper.writableDatabase
                // Системный плейлист защищён и на уровне SQL.
                val updated = db.update(
                    "playlists",
                    ContentValues().apply { put("name", name) },
                    "id = ? AND isSystem = 0",
                    arrayOf(id.toString())
                )
                if (updated > 0) bumpRevision()
                updated > 0
            }

        override suspend fun deletePlaylist(id: Long): Boolean = withContext(Dispatchers.IO) {
            ensureFavorites()
            val playlist = queryPlaylist(id)
            if (playlist == null || playlist.isSystem) return@withContext false
            val db = dbHelper.writableDatabase
            var deleted = 0
            db.beginTransaction()
            try {
                // Внешние ключи SQLite не включены: каскад выполняем вручную.
                db.delete("playlist_tracks", "playlistId = ?", arrayOf(id.toString()))
                deleted = db.delete(
                    "playlists",
                    "id = ? AND isSystem = 0",
                    arrayOf(id.toString())
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (deleted > 0) bumpRevision()
            deleted > 0
        }

        override suspend fun insertTrack(
            playlistId: Long,
            track: PlaylistTrackInput,
            addedAt: Long
        ): Long = withContext(Dispatchers.IO) {
            ensureFavorites()
            val inserted = insertTrackInternal(playlistId, track, addedAt)
            if (inserted != -1L) bumpRevision()
            inserted
        }

        override suspend fun insertTracks(
            playlistId: Long,
            tracks: List<PlaylistTrackInput>,
            addedAt: Long
        ): Int = withContext(Dispatchers.IO) {
            ensureFavorites()
            if (tracks.isEmpty()) return@withContext 0
            val db = dbHelper.writableDatabase
            var added = 0
            db.beginTransaction()
            try {
                for (track in tracks) {
                    if (insertTrackInternal(playlistId, track, addedAt) != -1L) added++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (added > 0) bumpRevision()
            added
        }

        private fun insertTrackInternal(
            playlistId: Long,
            track: PlaylistTrackInput,
            addedAt: Long
        ): Long {
            val db = dbHelper.writableDatabase
            return db.insertWithOnConflict(
                "playlist_tracks",
                null,
                ContentValues().apply {
                    put("playlistId", playlistId)
                    put("trackUri", TrackUriNormalizer.normalize(track.trackUri))
                    put("title", track.title)
                    put("artist", track.artist)
                    put("addedAt", addedAt)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }

        override suspend fun deleteTrack(playlistId: Long, trackUri: String): Int =
            withContext(Dispatchers.IO) {
                ensureFavorites()
                val db = dbHelper.writableDatabase
                val deleted = db.delete(
                    "playlist_tracks",
                    "playlistId = ? AND trackUri = ?",
                    arrayOf(playlistId.toString(), TrackUriNormalizer.normalize(trackUri))
                )
                if (deleted > 0) bumpRevision()
                deleted
            }

        override suspend fun moveTrack(
            fromPlaylistId: Long,
            toPlaylistId: Long,
            trackUri: String
        ): Boolean = withContext(Dispatchers.IO) {
            ensureFavorites()
            if (fromPlaylistId == toPlaylistId) return@withContext false
            val normalized = TrackUriNormalizer.normalize(trackUri)
            val link = queryTrack(fromPlaylistId, normalized) ?: return@withContext false
            val db = dbHelper.writableDatabase
            var moved = false
            db.beginTransaction()
            try {
                // Если трек уже есть в целевом плейлисте, удаляется только связь
                // источника: дубликатов в целевом не появляется.
                val removed = db.delete(
                    "playlist_tracks",
                    "playlistId = ? AND trackUri = ?",
                    arrayOf(fromPlaylistId.toString(), normalized)
                )
                if (removed > 0) {
                    db.insertWithOnConflict(
                        "playlist_tracks",
                        null,
                        ContentValues().apply {
                            put("playlistId", toPlaylistId)
                            put("trackUri", normalized)
                            put("title", link.title)
                            put("artist", link.artist)
                            // Перенос ставит трек в конец целевого плейлиста.
                            put("addedAt", System.currentTimeMillis())
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    moved = true
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (moved) bumpRevision()
            moved
        }

        override suspend fun insertPlaylistWithTracks(
            name: String,
            tracks: List<PlaylistTrackInput>,
            createdAt: Long
        ): Long = withContext(Dispatchers.IO) {
            ensureFavorites()
            val db = dbHelper.writableDatabase
            var playlistId = -1L
            db.beginTransaction()
            try {
                playlistId = db.insert(
                    "playlists",
                    null,
                    ContentValues().apply {
                        put("name", name)
                        put("isSystem", 0)
                        put("createdAt", createdAt)
                    }
                )
                if (playlistId != -1L) {
                    for (track in tracks) {
                        insertTrackInternal(playlistId, track, createdAt)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (playlistId != -1L) bumpRevision()
            playlistId
        }

        override suspend fun insertCatalogPlaylist(
            catalogId: String,
            name: String,
            tracks: List<PlaylistTrackInput>,
            createdAt: Long
        ): Long = withContext(Dispatchers.IO) {
            ensureFavorites()
            val db = dbHelper.writableDatabase
            var playlistId = -1L
            var created = false
            db.beginTransaction()
            try {
                // Идемпотентность: второй вызов того же каталога ничего не меняет.
                playlistId = queryPlaylistByCatalogId(catalogId)?.id ?: -1L
                if (playlistId == -1L) {
                    playlistId = db.insert(
                        "playlists",
                        null,
                        ContentValues().apply {
                            put("name", name)
                            put("isSystem", 0)
                            put("createdAt", createdAt)
                            put("catalogId", catalogId)
                        }
                    )
                    if (playlistId != -1L) {
                        for (track in tracks) {
                            insertTrackInternal(playlistId, track, createdAt)
                        }
                    }
                    created = playlistId != -1L
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (created) bumpRevision()
            playlistId
        }

        override suspend fun findTrack(
            playlistId: Long,
            trackUri: String
        ): PlaylistTrackEntity? = withContext(Dispatchers.IO) {
            ensureFavorites()
            queryTrack(playlistId, TrackUriNormalizer.normalize(trackUri))
        }

        private fun queryPlaylistsWithCounts(): List<PlaylistSummary> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT p.id, p.name, p.isSystem, p.catalogId, COUNT(t.id) AS trackCount
                FROM playlists p
                LEFT JOIN playlist_tracks t ON t.playlistId = p.id
                GROUP BY p.id, p.name, p.isSystem, p.catalogId, p.createdAt
                ORDER BY p.isSystem DESC, p.createdAt ASC, p.id ASC
                """.trimIndent(),
                null
            )
            val list = mutableListOf<PlaylistSummary>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        PlaylistSummary(
                            id = it.getLong(0),
                            name = it.getString(1),
                            isSystem = it.getInt(2) == 1,
                            // Stage 3: id каталога нужен карточке для иконки копии.
                            catalogId = if (it.isNull(3)) null else it.getString(3),
                            trackCount = it.getInt(4)
                        )
                    )
                }
            }
            return list
        }

        private fun queryTracks(playlistId: Long): List<PlaylistTrackEntity> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, playlistId, trackUri, title, artist, addedAt " +
                    "FROM playlist_tracks WHERE playlistId = ? ORDER BY addedAt ASC, id ASC",
                arrayOf(playlistId.toString())
            )
            val list = mutableListOf<PlaylistTrackEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(readTrack(it))
                }
            }
            return list
        }

        private fun queryTrack(playlistId: Long, trackUri: String): PlaylistTrackEntity? {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, playlistId, trackUri, title, artist, addedAt " +
                    "FROM playlist_tracks WHERE playlistId = ? AND trackUri = ? LIMIT 1",
                arrayOf(playlistId.toString(), trackUri)
            )
            cursor.use {
                return if (it.moveToNext()) readTrack(it) else null
            }
        }

        private fun readTrack(cursor: Cursor): PlaylistTrackEntity = PlaylistTrackEntity(
            id = cursor.getLong(0),
            playlistId = cursor.getLong(1),
            trackUri = cursor.getString(2),
            title = cursor.getString(3),
            artist = if (cursor.isNull(4)) null else cursor.getString(4),
            addedAt = cursor.getLong(5)
        )

        private fun queryFavoriteUris(): Set<String> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT trackUri FROM playlist_tracks WHERE playlistId = ?",
                arrayOf(PlaylistEntity.FAVORITES_PLAYLIST_ID.toString())
            )
            val uris = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    uris.add(it.getString(0))
                }
            }
            return uris
        }

        private fun queryAllPlaylistTrackUris(): Set<String> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT DISTINCT trackUri FROM playlist_tracks", null)
            val uris = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    uris.add(it.getString(0))
                }
            }
            return uris
        }

        private fun queryAddedCatalogIds(): Set<String> {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT catalogId FROM playlists WHERE catalogId IS NOT NULL",
                null
            )
            val ids = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    ids.add(it.getString(0))
                }
            }
            return ids
        }

        private fun queryPlaylist(id: Long): PlaylistEntity? {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, name, isSystem, createdAt, catalogId FROM playlists WHERE id = ? LIMIT 1",
                arrayOf(id.toString())
            )
            cursor.use {
                return if (it.moveToNext()) readPlaylist(it) else null
            }
        }

        private fun queryPlaylistByCatalogId(catalogId: String): PlaylistEntity? {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, name, isSystem, createdAt, catalogId " +
                    "FROM playlists WHERE catalogId = ? LIMIT 1",
                arrayOf(catalogId)
            )
            cursor.use {
                return if (it.moveToNext()) readPlaylist(it) else null
            }
        }

        private fun readPlaylist(cursor: Cursor): PlaylistEntity = PlaylistEntity(
            id = cursor.getLong(0),
            name = cursor.getString(1),
            isSystem = cursor.getInt(2) == 1,
            createdAt = cursor.getLong(3),
            catalogId = if (cursor.isNull(4)) null else cursor.getString(4)
        )

        private companion object {
            const val TAG = "SQLitePlaylistDao"
        }
    }
}
