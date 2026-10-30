package com.personal.vibratuner.data.local.db

import kotlinx.coroutines.flow.Flow

/**
 * Доступ к таблицам `playlists` и `playlist_tracks`.
 *
 * Как и остальные DAO проекта, это чистый интерфейс без кодогенерации Room:
 * реализация — `SQLitePlaylistDao` в `SQLiteAppDatabase`. Все suspend-методы
 * выполняются на `Dispatchers.IO`, Flow перезапрашивает данные после каждой мутации.
 * Методы, доступные UI, перед работой гарантируют наличие строки «Избранное».
 *
 * `trackUri` при записи и поиске нормализуется так же, как `tracks.uri`
 * ([TrackUriNormalizer]): в БД он всегда хранится нормализованным.
 */
interface PlaylistDao {

    /** Сводка плейлистов: «Избранное» первым, остальные по [PlaylistEntity.createdAt]. */
    fun playlistsWithCounts(): Flow<List<PlaylistSummary>>

    /** Треки плейлиста по порядку добавления (addedAt, затем id). */
    fun tracksOf(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    /** Однократное чтение треков: используется координатором в следующих этапах. */
    suspend fun getTracksOnce(playlistId: Long): List<PlaylistTrackEntity>

    /** Поток URI избранных треков для дешёвой отрисовки ♥ в списках. */
    fun favoriteUris(): Flow<Set<String>>

    /**
     * Все URI, сохранённые в плейлистах (включая «Любимое»). Нужно удалению
     * скачанных файлов: общий трек нельзя удалять, пока он есть хоть в одном
     * плейлисте. Признак «Добавлено» по этим URI НЕ вычисляется (только catalogId).
     */
    fun allPlaylistTrackUris(): Flow<Set<String>>

    /**
     * Стабильные id каталога «От автора», уже добавленные в библиотеку
     * (`playlists.catalogId IS NOT NULL`). ЕДИНСТВЕННЫЙ источник признака
     * «Добавлено» в UI каталога.
     */
    fun addedCatalogIds(): Flow<Set<String>>

    /**
     * Локальные плейлисты с `catalogId IS NULL` (копии каталога, созданные до
     * версии БД 10) вместе с их треками — кандидаты на восстановление `catalogId`.
     * Только чтение, порядок стабилен.
     */
    suspend fun getCatalogCopyCandidates(): List<PlaylistCopyCandidate>

    /**
     * Восстанавливает `catalogId` у копии, созданной до v10. Меняет ТОЛЬКО строку
     * с `catalogId IS NULL` (и не системную); данные и треки не трогаются.
     * false — строка не найдена или такой `catalogId` уже занят.
     */
    suspend fun assignCatalogId(playlistId: Long, catalogId: String): Boolean

    /** Идемпотентно создаёт строку «Избранное» (INSERT OR IGNORE, id = 1). */
    suspend fun ensureSystemPlaylist()

    suspend fun getPlaylist(id: Long): PlaylistEntity?

    /** Плейлист, добавленный из каталога с этим стабильным id, либо `null`. */
    suspend fun getPlaylistByCatalogId(catalogId: String): PlaylistEntity?

    suspend fun insertPlaylist(
        name: String,
        createdAt: Long,
        catalogId: String? = null
    ): Long

    /** Переименование; 0 строк для системного плейлиста. */
    suspend fun renamePlaylist(id: Long, name: String): Boolean

    /** Удаление вместе с треками (каскад выполняется вручную); системный защищён. */
    suspend fun deletePlaylist(id: Long): Boolean

    /** Вставка одного трека; -1, если такой URI уже есть в плейлисте. */
    suspend fun insertTrack(playlistId: Long, track: PlaylistTrackInput, addedAt: Long): Long

    /** Пакетная вставка; возвращает число реально добавленных треков. */
    suspend fun insertTracks(
        playlistId: Long,
        tracks: List<PlaylistTrackInput>,
        addedAt: Long
    ): Int

    /** Удаление по URI; возвращает число удалённых строк (0 или 1). */
    suspend fun deleteTrack(playlistId: Long, trackUri: String): Int

    /**
     * Перенос трека между плейлистами ОДНОЙ транзакцией. Если трек уже есть
     * в целевом плейлисте, удаляется только связь в источнике; дубликатов нет.
     * Возвращает true, если связь в источнике существовала и была обработана.
     */
    suspend fun moveTrack(fromPlaylistId: Long, toPlaylistId: Long, trackUri: String): Boolean

    /** Создание плейлиста и его треков в одной транзакции; -1 при ошибке. */
    suspend fun insertPlaylistWithTracks(
        name: String,
        tracks: List<PlaylistTrackInput>,
        createdAt: Long
    ): Long

    /**
     * Добавление плейлиста каталога в одной транзакции: метаданные плейлиста
     * (`catalogId`) и все его треки. Идемпотентно: если `catalogId` уже есть,
     * возвращается id существующего плейлиста без единого изменения.
     * -1 — если плейлист создать не удалось.
     */
    suspend fun insertCatalogPlaylist(
        catalogId: String,
        name: String,
        tracks: List<PlaylistTrackInput>,
        createdAt: Long
    ): Long

    suspend fun findTrack(playlistId: Long, trackUri: String): PlaylistTrackEntity?
}
