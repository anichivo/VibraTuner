package com.personal.vibratuner.data.repository

import com.personal.vibratuner.data.local.db.AlarmDao
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.data.local.db.CatalogPlaylistInput
import com.personal.vibratuner.data.local.db.PlaylistAlarmUsage
import com.personal.vibratuner.data.local.db.PlaylistCopyCandidate
import com.personal.vibratuner.data.local.db.PlaylistDao
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.PlaylistTrackEntity
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика `PlaylistRepository` на JVM: настоящий SQLite в юнит-тестах недоступен
 * (Robolectric не подключён), поэтому DAO заменён [FakePlaylistDao] с теми же
 * правилами: уникальность `(playlistId, trackUri)`, уникальность `catalogId`,
 * каскадное удаление связей. SQL самих запросов проверяется отдельно — миграции
 * тестами констант и ручной проверкой (docs/STATE.md).
 */
class PlaylistRepositoryTest {

    private val dao = FakePlaylistDao()
    private val repository = PlaylistRepository(dao, FakeAlarmDao())

    @Test
    fun createPlaylistRejectsBlankName() = runTest {
        assertEquals(PlaylistRepository.INVALID_PLAYLIST_ID, repository.createPlaylist("   "))
    }

    @Test
    fun createPlaylistTrimsNameAndHasNoCatalogId() = runTest {
        val id = repository.createPlaylist("  Утро  ")
        assertTrue(id > 0L)
        assertEquals("Утро", dao.getPlaylist(id)?.name)
        assertNull(dao.getPlaylist(id)?.catalogId)
    }

    @Test
    fun addCatalogPlaylistCopiesMetadataAndTracksInOnePlaylist() = runTest {
        val id = repository.addCatalogPlaylist(catalogPlaylist())
        assertTrue(id > 0L)

        val playlist = dao.getPlaylist(id)
        assertEquals("morning-soft", playlist?.catalogId)
        assertEquals("Утро", playlist?.name)
        assertEquals(
            listOf("https://example.com/a.mp3", "https://example.com/b.mp3"),
            dao.tracksOf(id).first().map { it.trackUri }
        )
        assertEquals(setOf("morning-soft"), repository.observeAddedCatalogIds().first())
    }

    @Test
    fun addCatalogPlaylistIsIdempotent() = runTest {
        val first = repository.addCatalogPlaylist(catalogPlaylist())
        val second = repository.addCatalogPlaylist(catalogPlaylist(name = "Другое имя"))

        assertEquals(first, second)
        assertEquals(1, dao.catalogInserts)
        // Повторный вызов не переименовывает плейлист и не дублирует треки.
        assertEquals("Утро", dao.getPlaylist(first)?.name)
        assertEquals(2, dao.tracksOf(first).first().size)
    }

    @Test
    fun addCatalogPlaylistRejectsBlankCatalogId() = runTest {
        val result = repository.addCatalogPlaylist(catalogPlaylist(catalogId = "  "))
        assertEquals(PlaylistRepository.INVALID_PLAYLIST_ID, result)
        assertEquals(0, dao.catalogInserts)
    }

    @Test
    fun renamingCatalogCopyKeepsCatalogIdAndAddedState() = runTest {
        val id = repository.addCatalogPlaylist(catalogPlaylist())

        assertTrue(repository.renamePlaylist(id, "  Моё утро  "))

        val playlist = dao.getPlaylist(id)
        assertEquals("Моё утро", playlist?.name)
        // Связь с каталогом — стабильный catalogId, а не имя: «Добавлено»
        // остаётся, повторное добавление по-прежнему идемпотентно.
        assertEquals("morning-soft", playlist?.catalogId)
        assertEquals(setOf("morning-soft"), repository.observeAddedCatalogIds().first())
        assertEquals(2, dao.tracksOf(id).first().size)
        assertEquals(id, repository.addCatalogPlaylist(catalogPlaylist()))
    }

    @Test
    fun renameRejectsSystemPlaylistAndBlankName() = runTest {
        val id = repository.createPlaylist("Имя")

        assertFalse(repository.renamePlaylist(PlaylistEntity.FAVORITES_PLAYLIST_ID, "Другое"))
        assertFalse(repository.renamePlaylist(id, "   "))
        assertEquals("Имя", dao.getPlaylist(id)?.name)
        assertEquals(
            PlaylistEntity.FAVORITES_NAME,
            dao.getPlaylist(PlaylistEntity.FAVORITES_PLAYLIST_ID)?.name
        )
    }

    @Test
    fun deletingCatalogCopyClearsAddedState() = runTest {
        val id = repository.addCatalogPlaylist(catalogPlaylist())
        assertEquals(setOf("morning-soft"), repository.observeAddedCatalogIds().first())

        // Удаление из библиотеки — единственный источник признака «Добавлено»:
        // каталог снова должен показать «Добавить к себе», добавление возможно.
        assertTrue(repository.deletePlaylist(id))
        assertEquals(emptySet<String>(), repository.observeAddedCatalogIds().first())
        assertTrue(repository.addCatalogPlaylist(catalogPlaylist()) > 0L)
    }

    @Test
    fun assignCatalogIdRepairsLegacyCopyOnly() = runTest {
        val legacyId = repository.createPlaylist("Утро")
        assertEquals(
            listOf(legacyId),
            repository.getCatalogCopyCandidates().map { it.playlistId }
        )
        assertTrue(repository.assignCatalogId(legacyId, "morning-soft"))
        assertEquals("morning-soft", dao.getPlaylist(legacyId)?.catalogId)
        assertEquals(setOf("morning-soft"), repository.observeAddedCatalogIds().first())

        // Повторный ремонт не меняет строку (catalogId уже установлен).
        assertFalse(repository.assignCatalogId(legacyId, "morning-soft"))
        // Второй плейлист с тем же catalogId создать нельзя.
        val second = repository.createPlaylist("Ещё одно утро")
        assertFalse(repository.assignCatalogId(second, "morning-soft"))
        assertNull(dao.getPlaylist(second)?.catalogId)
    }

    @Test
    fun addTrackToPlaylistIgnoresDuplicateSilently() = runTest {
        val playlistId = repository.createPlaylist("Плейлист")
        val track = PlaylistTrackInput(trackUri = "content://music/1", title = "Трек")

        assertTrue(repository.addTrackToPlaylist(playlistId, track))
        assertFalse(repository.addTrackToPlaylist(playlistId, track))
        assertEquals(1, dao.tracksOf(playlistId).first().size)
    }

    @Test
    fun removeTrackFromPlaylistRemovesOnlyThatLink() = runTest {
        val first = repository.createPlaylist("Первый")
        val second = repository.createPlaylist("Второй")
        val track = PlaylistTrackInput(trackUri = "content://music/1", title = "Трек")
        repository.addTrackToPlaylist(first, track)
        repository.addTrackToPlaylist(second, track)

        assertTrue(repository.removeTrackFromPlaylist(first, track.trackUri))
        assertTrue(dao.tracksOf(first).first().isEmpty())
        assertEquals(1, dao.tracksOf(second).first().size)

        // Повторное удаление — безопасный no-op.
        assertFalse(repository.removeTrackFromPlaylist(first, track.trackUri))
    }

    @Test
    fun referencedTrackUrisKeepsTracksSharedWithOtherPlaylists() = runTest {
        val first = repository.createPlaylist("Первый")
        val second = repository.createPlaylist("Второй")
        val shared = PlaylistTrackInput(trackUri = "https://example.com/a.mp3", title = "A")
        val onlyFirst = PlaylistTrackInput(trackUri = "https://example.com/b.mp3", title = "B")
        repository.addTrackToPlaylist(first, shared)
        repository.addTrackToPlaylist(first, onlyFirst)
        repository.addTrackToPlaylist(second, shared)

        assertTrue(repository.deletePlaylist(first))
        assertEquals(
            setOf(shared.trackUri),
            repository.referencedTrackUris(setOf(shared.trackUri, onlyFirst.trackUri))
        )
        assertEquals(emptySet<String>(), repository.referencedTrackUris(emptySet()))
    }

    @Test
    fun moveTrackMovesBetweenPlaylists() = runTest {
        val from = repository.createPlaylist("Из")
        val to = repository.createPlaylist("В")
        val track = PlaylistTrackInput(trackUri = "content://music/1", title = "Трек")
        repository.addTrackToPlaylist(from, track)

        assertTrue(repository.moveTrack(track.trackUri, from, to))
        assertTrue(dao.tracksOf(from).first().isEmpty())
        assertEquals(listOf(track.trackUri), dao.tracksOf(to).first().map { it.trackUri })

        // В источнике связи уже нет — переносить нечего.
        assertFalse(repository.moveTrack(track.trackUri, from, to))
    }

    @Test
    fun moveTrackWhenAlreadyInTargetRemovesOnlySourceLink() = runTest {
        val from = repository.createPlaylist("Из")
        val to = repository.createPlaylist("В")
        val track = PlaylistTrackInput(trackUri = "content://music/1", title = "Трек")
        repository.addTrackToPlaylist(from, track)
        repository.addTrackToPlaylist(to, track)

        assertTrue(repository.moveTrack(track.trackUri, from, to))
        assertTrue(dao.tracksOf(from).first().isEmpty())
        // В целевом плейлисте дубликата не появилось.
        assertEquals(1, dao.tracksOf(to).first().size)
        assertEquals(listOf(track.trackUri), dao.tracksOf(to).first().map { it.trackUri })
    }

    @Test
    fun moveTrackRejectsSameOrMissingPlaylist() = runTest {
        val playlistId = repository.createPlaylist("Плейлист")
        val track = PlaylistTrackInput(trackUri = "content://music/1", title = "Трек")
        repository.addTrackToPlaylist(playlistId, track)

        assertFalse(repository.moveTrack(track.trackUri, playlistId, playlistId))
        assertFalse(repository.moveTrack(track.trackUri, playlistId, MISSING_ID))
        assertEquals(1, dao.tracksOf(playlistId).first().size)
    }

    @Test
    fun observeLibraryPlaylistsIncludesFavoritesFirst() = runTest {
        repository.createPlaylist("Мой")
        val playlists = repository.observeLibraryPlaylists().first()
        assertEquals(PlaylistEntity.FAVORITES_PLAYLIST_ID, playlists.first().id)
        assertTrue(playlists.first().isSystem)
        assertTrue(playlists.any { it.name == "Мой" && !it.isSystem })
    }

    @Test
    fun observePlaylistTracksStaysEmptyForEmptyPlaylist() = runTest {
        val playlistId = repository.createPlaylist("Пустой")
        assertTrue(repository.observePlaylistTracks(playlistId).first().isEmpty())
    }

    private fun catalogPlaylist(
        catalogId: String = "morning-soft",
        name: String = "Утро"
    ) = CatalogPlaylistInput(
        catalogId = catalogId,
        name = name,
        tracks = listOf(
            PlaylistTrackInput(trackUri = "https://example.com/a.mp3", title = "Первый"),
            PlaylistTrackInput(trackUri = "https://example.com/b.mp3", title = "Второй")
        )
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}

/** In-memory DAO: повторяет правила уникальности и каскада настоящего SQLite-DAO. */
private class FakePlaylistDao : PlaylistDao {

    private val revision = MutableStateFlow(0L)
    private val playlists = linkedMapOf<Long, PlaylistEntity>()
    private val links = mutableListOf<PlaylistTrackEntity>()
    private var nextPlaylistId = 2L
    private var nextTrackId = 1L

    /** Сколько раз каталог реально создавал плейлист (для проверки идемпотентности). */
    var catalogInserts = 0
        private set

    private fun bump() {
        revision.value += 1L
    }

    private fun ensureFavorites() {
        if (playlists.containsKey(PlaylistEntity.FAVORITES_PLAYLIST_ID)) return
        playlists[PlaylistEntity.FAVORITES_PLAYLIST_ID] = PlaylistEntity(
            id = PlaylistEntity.FAVORITES_PLAYLIST_ID,
            name = PlaylistEntity.FAVORITES_NAME,
            isSystem = true,
            createdAt = PlaylistEntity.FAVORITES_CREATED_AT
        )
    }

    private fun addLink(playlistId: Long, track: PlaylistTrackInput, addedAt: Long): Long {
        if (links.any { it.playlistId == playlistId && it.trackUri == track.trackUri }) return -1L
        val id = nextTrackId++
        links += PlaylistTrackEntity(
            id = id,
            playlistId = playlistId,
            trackUri = track.trackUri,
            title = track.title,
            artist = track.artist,
            addedAt = addedAt
        )
        return id
    }

    override fun playlistsWithCounts(): Flow<List<PlaylistSummary>> = revision.map {
        playlists.values
            .sortedWith(compareByDescending<PlaylistEntity> { it.isSystem }.thenBy { it.createdAt })
            .map { playlist ->
                PlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    isSystem = playlist.isSystem,
                    trackCount = links.count { it.playlistId == playlist.id }
                )
            }
    }

    override fun tracksOf(playlistId: Long): Flow<List<PlaylistTrackEntity>> = revision.map {
        links.filter { it.playlistId == playlistId }
            .sortedWith(compareBy({ it.addedAt }, { it.id }))
    }

    override suspend fun getTracksOnce(playlistId: Long): List<PlaylistTrackEntity> =
        tracksOf(playlistId).first()

    override fun favoriteUris(): Flow<Set<String>> = revision.map {
        links.filter { it.playlistId == PlaylistEntity.FAVORITES_PLAYLIST_ID }
            .mapTo(mutableSetOf()) { it.trackUri }
    }

    override fun allPlaylistTrackUris(): Flow<Set<String>> = revision.map {
        links.mapTo(mutableSetOf()) { it.trackUri }
    }

    override fun addedCatalogIds(): Flow<Set<String>> = revision.map {
        playlists.values.mapNotNull { it.catalogId }.toSet()
    }

    override suspend fun getCatalogCopyCandidates(): List<PlaylistCopyCandidate> {
        ensureFavorites()
        return playlists.values
            .filter { !it.isSystem && it.catalogId == null }
            .sortedBy { it.id }
            .map { playlist ->
                PlaylistCopyCandidate(
                    playlistId = playlist.id,
                    name = playlist.name,
                    trackUris = links
                        .filter { it.playlistId == playlist.id }
                        .sortedWith(compareBy({ it.addedAt }, { it.id }))
                        .map { it.trackUri }
                )
            }
    }

    override suspend fun assignCatalogId(playlistId: Long, catalogId: String): Boolean {
        val playlist = playlists[playlistId] ?: return false
        if (playlist.isSystem || playlist.catalogId != null) return false
        if (playlists.values.any { it.catalogId == catalogId }) return false
        playlists[playlistId] = playlist.copy(catalogId = catalogId)
        bump()
        return true
    }

    override suspend fun ensureSystemPlaylist() {
        ensureFavorites()
    }

    override suspend fun getPlaylist(id: Long): PlaylistEntity? {
        ensureFavorites()
        return playlists[id]
    }

    override suspend fun getPlaylistByCatalogId(catalogId: String): PlaylistEntity? {
        ensureFavorites()
        return playlists.values.firstOrNull { it.catalogId == catalogId }
    }

    override suspend fun insertPlaylist(name: String, createdAt: Long, catalogId: String?): Long {
        ensureFavorites()
        val id = nextPlaylistId++
        playlists[id] = PlaylistEntity(
            id = id,
            name = name,
            isSystem = false,
            createdAt = createdAt,
            catalogId = catalogId
        )
        bump()
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String): Boolean {
        val playlist = playlists[id] ?: return false
        if (playlist.isSystem) return false
        playlists[id] = playlist.copy(name = name)
        bump()
        return true
    }

    override suspend fun deletePlaylist(id: Long): Boolean {
        val playlist = playlists[id] ?: return false
        if (playlist.isSystem) return false
        playlists.remove(id)
        links.removeAll { it.playlistId == id }
        bump()
        return true
    }

    override suspend fun insertTrack(
        playlistId: Long,
        track: PlaylistTrackInput,
        addedAt: Long
    ): Long {
        val id = addLink(playlistId, track, addedAt)
        if (id != -1L) bump()
        return id
    }

    override suspend fun insertTracks(
        playlistId: Long,
        tracks: List<PlaylistTrackInput>,
        addedAt: Long
    ): Int {
        var added = 0
        tracks.forEach { track ->
            if (addLink(playlistId, track, addedAt) != -1L) added++
        }
        if (added > 0) bump()
        return added
    }

    override suspend fun deleteTrack(playlistId: Long, trackUri: String): Int {
        val before = links.size
        links.removeAll { it.playlistId == playlistId && it.trackUri == trackUri }
        val deleted = before - links.size
        if (deleted > 0) bump()
        return deleted
    }

    override suspend fun moveTrack(
        fromPlaylistId: Long,
        toPlaylistId: Long,
        trackUri: String
    ): Boolean {
        if (fromPlaylistId == toPlaylistId) return false
        val link = links.firstOrNull {
            it.playlistId == fromPlaylistId && it.trackUri == trackUri
        } ?: return false
        links.remove(link)
        if (links.none { it.playlistId == toPlaylistId && it.trackUri == trackUri }) {
            links += link.copy(
                id = nextTrackId++,
                playlistId = toPlaylistId,
                addedAt = System.currentTimeMillis()
            )
        }
        bump()
        return true
    }

    override suspend fun insertPlaylistWithTracks(
        name: String,
        tracks: List<PlaylistTrackInput>,
        createdAt: Long
    ): Long {
        val id = insertPlaylist(name, createdAt, catalogId = null)
        tracks.forEach { addLink(id, it, createdAt) }
        return id
    }

    override suspend fun insertCatalogPlaylist(
        catalogId: String,
        name: String,
        tracks: List<PlaylistTrackInput>,
        createdAt: Long
    ): Long {
        playlists.values.firstOrNull { it.catalogId == catalogId }?.let { return it.id }
        val id = insertPlaylist(name, createdAt, catalogId)
        tracks.forEach { addLink(id, it, createdAt) }
        catalogInserts++
        return id
    }

    override suspend fun findTrack(
        playlistId: Long,
        trackUri: String
    ): PlaylistTrackEntity? = links.firstOrNull {
        it.playlistId == playlistId && it.trackUri == trackUri
    }
}

/** Будильники репозиторию нужны только для Flow использования, в тестах пусты. */
private class FakeAlarmDao : AlarmDao {
    override fun getAllAlarmsFlow(): Flow<List<AlarmEntity>> = flowOf(emptyList())

    override fun getPlaylistAlarmUsageFlow(): Flow<List<PlaylistAlarmUsage>> = flowOf(emptyList())

    override suspend fun getEnabledAlarms(): List<AlarmEntity> = emptyList()

    override suspend fun getAlarmById(id: Long): AlarmEntity? = null

    override suspend fun insertAlarm(alarm: AlarmEntity): Long = 0L

    override suspend fun updateAlarm(alarm: AlarmEntity) = Unit

    override suspend fun deleteAlarm(alarm: AlarmEntity) = Unit
}
