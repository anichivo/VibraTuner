package com.personal.vibratuner.data.repository

import com.personal.vibratuner.data.local.db.AlarmDao
import com.personal.vibratuner.data.local.db.CatalogPlaylistInput
import com.personal.vibratuner.data.local.db.PlaylistCopyCandidate
import com.personal.vibratuner.data.local.db.PlaylistDao
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistNameValidator
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.PlaylistTrackEntity
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Публичный контракт плейлистов для UI и координатора (этапы 2–4).
 *
 * Инварианты:
 * - строка «Избранное» ([PlaylistEntity.FAVORITES_PLAYLIST_ID]) создаётся до любой
 *   операции и НЕ может быть переименована или удалена;
 * - имя обрезается по краям, пустое и длиннее 50 символов отклоняется
 *   ([PlaylistNameValidator]); одинаковые имена разрешены;
 * - `trackUri` — стабильный идентификатор трека (`tracks.uri`, нормализованный
 *   content:// URI; в этапе 4 также https-URL); дубликаты в плейлисте игнорируются;
 * - плейлист, добавленный из каталога «От автора», хранит стабильный `catalogId`
 *   ([PlaylistEntity.catalogId]); повторное добавление того же каталога данных не меняет;
 * - все suspend-методы работают вне главного потока (DAO использует IO).
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val alarmDao: AlarmDao
) {

    /** Сериализует ♥: два быстрых нажатия не должны дать «прочитал-вставил» гонку. */
    private val favoriteMutex = Mutex()

    /** Сериализует добавление каталога: проверка «уже добавлен» и вставка не расходятся. */
    private val catalogMutex = Mutex()

    /** Сводка плейлистов: «Избранное» первым, затем по дате создания. */
    fun playlistsWithCounts(): Flow<List<PlaylistSummary>> = dao.playlistsWithCounts()

    /**
     * Локальные плейлисты библиотеки: «Избранное», пользовательские и добавленные
     * из каталога. Тот же набор и порядок, что у [playlistsWithCounts].
     */
    fun observeLibraryPlaylists(): Flow<List<PlaylistSummary>> = dao.playlistsWithCounts()

    /** Треки плейлиста по порядку `addedAt ASC, id ASC`. */
    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>> =
        dao.tracksOf(playlistId)

    /**
     * Стабильные id плейлистов каталога, уже добавленных в библиотеку.
     * ЕДИНСТВЕННЫЙ источник признака «Добавлено» (никакой эвристики по трекам).
     */
    fun observeAddedCatalogIds(): Flow<Set<String>> = dao.addedCatalogIds()

    /**
     * Кандидаты на самовосстановление `catalogId`: локальные плейлисты с
     * `catalogId = NULL` (копии каталога, созданные до версии БД 10).
     */
    suspend fun getCatalogCopyCandidates(): List<PlaylistCopyCandidate> {
        dao.ensureSystemPlaylist()
        return dao.getCatalogCopyCandidates()
    }

    /**
     * Самовосстановление: записывает `catalogId` старой копии каталога.
     * Меняет только строку с `catalogId IS NULL`; данные не удаляются.
     */
    suspend fun assignCatalogId(playlistId: Long, catalogId: String): Boolean {
        val stableId = catalogId.trim()
        if (stableId.isEmpty()) return false
        dao.ensureSystemPlaylist()
        return dao.assignCatalogId(playlistId, stableId)
    }

    /**
     * Будильники, использующие плейлист как источник: playlistId -> количество.
     * Живой Flow, чтобы предупреждение при удалении было актуальным сразу после
     * создания/правки будильника.
     */
    fun alarmUsageByPlaylist(): Flow<Map<Long, Int>> =
        alarmDao.getPlaylistAlarmUsageFlow().map { usage ->
            usage.associate { it.playlistId to it.alarmCount }
        }

    fun tracksOf(playlistId: Long): Flow<List<PlaylistTrackEntity>> = dao.tracksOf(playlistId)

    /** Однократное чтение для координатора (не подписка). */
    suspend fun getTracksOnce(playlistId: Long): List<PlaylistTrackEntity> {
        dao.ensureSystemPlaylist()
        return dao.getTracksOnce(playlistId)
    }

    /**
     * Один плейлист (не подписка): нужен удалению, чтобы до удаления строк
     * узнать `catalogId` и отменить загрузку копии каталога, запущенную из
     * read-only экрана под другим ключом (`PlaylistDownloadKeys`).
     */
    suspend fun getPlaylistOnce(playlistId: Long): PlaylistEntity? {
        dao.ensureSystemPlaylist()
        return dao.getPlaylist(playlistId)
    }

    /** Создаёт плейлист; возвращает id или [INVALID_PLAYLIST_ID], если имя невалидно. */
    suspend fun createPlaylist(name: String): Long {
        dao.ensureSystemPlaylist()
        val validName = PlaylistNameValidator.validate(name) ?: return INVALID_PLAYLIST_ID
        return dao.insertPlaylist(validName, System.currentTimeMillis())
    }

    /** Переименование; системный плейлист и невалидное имя отклоняются. */
    suspend fun renamePlaylist(id: Long, name: String): Boolean {
        dao.ensureSystemPlaylist()
        val playlist = dao.getPlaylist(id) ?: return false
        if (playlist.isSystem || id == PlaylistEntity.FAVORITES_PLAYLIST_ID) return false
        val validName = PlaylistNameValidator.validate(name) ?: return false
        return dao.renamePlaylist(id, validName)
    }

    /** Удаление вместе с треками; системный плейлист удалить нельзя. */
    suspend fun deletePlaylist(id: Long): Boolean {
        dao.ensureSystemPlaylist()
        val playlist = dao.getPlaylist(id) ?: return false
        if (playlist.isSystem || id == PlaylistEntity.FAVORITES_PLAYLIST_ID) return false
        return dao.deletePlaylist(id)
    }

    /** Добавляет трек; дубликат по URI молча игнорируется (false). */
    suspend fun addTrack(playlistId: Long, track: PlaylistTrackInput): Boolean {
        dao.ensureSystemPlaylist()
        if (dao.getPlaylist(playlistId) == null) return false
        return dao.insertTrack(playlistId, track, System.currentTimeMillis()) != -1L
    }

    /** Добавляет трек в плейлист; дубликат по URI молча игнорируется (false). */
    suspend fun addTrackToPlaylist(playlistId: Long, track: PlaylistTrackInput): Boolean =
        addTrack(playlistId, track)

    /** Добавляет пачку; возвращает число реально добавленных треков. */
    suspend fun addTracks(playlistId: Long, tracks: List<PlaylistTrackInput>): Int {
        dao.ensureSystemPlaylist()
        if (tracks.isEmpty() || dao.getPlaylist(playlistId) == null) return 0
        return dao.insertTracks(playlistId, tracks, System.currentTimeMillis())
    }

    /** Удаляет трек из плейлиста; возвращает true, если строка существовала. */
    suspend fun removeTrack(playlistId: Long, trackUri: String): Boolean {
        dao.ensureSystemPlaylist()
        return dao.deleteTrack(playlistId, trackUri) > 0
    }

    /**
     * Убирает трек ТОЛЬКО из плейлиста: сам трек в библиотеке и его связи
     * с другими плейлистами не меняются. true — связь существовала.
     */
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackUri: String): Boolean =
        removeTrack(playlistId, trackUri)

    /**
     * Переносит трек из одного плейлиста в другой ОДНОЙ транзакцией.
     * Если трек уже есть в целевом плейлисте, удаляется только связь источника.
     * false — связи в источнике нет, плейлисты совпадают или не существуют.
     */
    suspend fun moveTrack(
        trackUri: String,
        fromPlaylistId: Long,
        toPlaylistId: Long
    ): Boolean {
        if (fromPlaylistId == toPlaylistId) return false
        dao.ensureSystemPlaylist()
        if (dao.getPlaylist(fromPlaylistId) == null) return false
        if (dao.getPlaylist(toPlaylistId) == null) return false
        return dao.moveTrack(fromPlaylistId, toPlaylistId, trackUri)
    }

    /** Плейлист и его треки одной транзакцией (этап 4); -1 при невалидном имени. */
    suspend fun createPlaylistWithTracks(
        name: String,
        tracks: List<PlaylistTrackInput>
    ): Long {
        dao.ensureSystemPlaylist()
        val validName = PlaylistNameValidator.validate(name) ?: return INVALID_PLAYLIST_ID
        return dao.insertPlaylistWithTracks(validName, tracks, System.currentTimeMillis())
    }

    /**
     * Добавляет плейлист каталога «От автора» в библиотеку: имя, стабильный
     * `catalogId` и все треки — ОДНОЙ транзакцией.
     *
     * Идемпотентно: если каталог уже добавлен, возвращается id существующего
     * плейлиста, а в БД не меняется ни одна строка. -1 — пустой `catalogId`
     * или имя не проходит [PlaylistNameValidator].
     */
    suspend fun addCatalogPlaylist(playlist: CatalogPlaylistInput): Long {
        dao.ensureSystemPlaylist()
        val catalogId = playlist.catalogId.trim()
        if (catalogId.isEmpty()) return INVALID_PLAYLIST_ID
        return catalogMutex.withLock {
            val existing = dao.getPlaylistByCatalogId(catalogId)
            if (existing != null) {
                existing.id
            } else {
                val validName = PlaylistNameValidator.validate(playlist.name)
                    ?: return@withLock INVALID_PLAYLIST_ID
                dao.insertCatalogPlaylist(
                    catalogId = catalogId,
                    name = validName,
                    tracks = playlist.tracks,
                    createdAt = System.currentTimeMillis()
                )
            }
        }
    }

    /** URI избранных треков (нормализованные) для отрисовки ♥. */
    fun favoriteUris(): Flow<Set<String>> = dao.favoriteUris()

    /**
     * Какие из [trackUris] всё ещё используются хоть одним плейлистом (часть 3).
     * Нужно при удалении плейлиста: скачанный файл общего трека удалять нельзя.
     * Читается через существующий поток всех URI — отдельного запроса и схемы нет.
     *
     * Вызывается ПОСЛЕ удаления плейлиста: его собственные связи уже удалены.
     */
    suspend fun referencedTrackUris(trackUris: Set<String>): Set<String> {
        if (trackUris.isEmpty()) return emptySet()
        return allPlaylistTrackUris().first().intersect(trackUris)
    }

    /**
     * Все URI из плейлистов: нужны удалению скачанных файлов (общие треки не
     * удаляются). Признак «Добавлено» считается только по `catalogId`.
     */
    fun allPlaylistTrackUris(): Flow<Set<String>> = dao.allPlaylistTrackUris()

    /** Ставит/снимает ♥. Повторный вызов возвращает трек в прежнее состояние. */
    suspend fun toggleFavorite(track: PlaylistTrackInput) {
        favoriteMutex.withLock {
            dao.ensureSystemPlaylist()
            if (dao.findTrack(PlaylistEntity.FAVORITES_PLAYLIST_ID, track.trackUri) != null) {
                dao.deleteTrack(PlaylistEntity.FAVORITES_PLAYLIST_ID, track.trackUri)
            } else {
                dao.insertTrack(
                    PlaylistEntity.FAVORITES_PLAYLIST_ID,
                    track,
                    System.currentTimeMillis()
                )
            }
        }
    }

    companion object {
        /** Результат createPlaylist/createPlaylistWithTracks при отклонённом имени. */
        const val INVALID_PLAYLIST_ID = -1L
    }
}
