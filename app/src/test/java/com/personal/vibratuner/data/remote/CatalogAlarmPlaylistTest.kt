package com.personal.vibratuner.data.remote

import com.personal.vibratuner.data.local.alarm.AlarmPlaylistQueue
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Будильник-плейлист каталога «От автора» (v11): треки берутся из манифеста
 * в порядке автора и попадают в СУЩЕСТВУЮЩУЮ очередь `AlarmPlaylistQueue`
 * (второго пути построения очереди нет).
 */
class CatalogAlarmPlaylistTest {

    private fun playlist(
        id: String = "morning-soft",
        tracks: List<AuthorTrack> = listOf(
            AuthorTrack(title = "Первый", artist = "Автор", url = "https://a.example/1.mp3"),
            AuthorTrack(title = "Второй", artist = null, url = "https://a.example/2.mp3")
        )
    ) = AuthorManifestPlaylist(id = id, name = "Утро", tracks = tracks)

    @Test
    fun tracksAreTakenFromCatalogInAuthorOrder() {
        val result = CatalogAlarmPlaylist.tracks(playlist())

        assertEquals(2, result.size)
        assertEquals("https://a.example/1.mp3", result[0].trackUri)
        assertEquals("Первый", result[0].title)
        assertEquals("Автор", result[0].artist)
        assertEquals("https://a.example/2.mp3", result[1].trackUri)
        // Синтетические строки: отрицательные id, локального плейлиста нет (0).
        assertTrue(result.all { it.id < 0L })
        assertEquals(CatalogAlarmPlaylist.CATALOG_PLAYLIST_ID, result[0].playlistId)
    }

    @Test
    fun unknownCatalogIdGivesEmptyList() {
        assertTrue(CatalogAlarmPlaylist.tracks("unknown", listOf(playlist())).isEmpty())
        assertTrue(CatalogAlarmPlaylist.tracks("morning-soft", null).isEmpty())
    }

    @Test
    fun emptyCatalogPlaylistGivesEmptyQueue() {
        val empty = playlist(tracks = emptyList())
        assertTrue(CatalogAlarmPlaylist.tracks(empty).isEmpty())
        assertTrue(AlarmPlaylistQueue.build(CatalogAlarmPlaylist.tracks(empty), false).isEmpty())
    }

    @Test
    fun queueUsesExistingAlarmPlaylistQueueAndKeepsOrder() {
        val queue = AlarmPlaylistQueue.build(
            CatalogAlarmPlaylist.tracks(playlist()),
            shuffle = false
        )
        assertEquals(
            listOf("https://a.example/1.mp3", "https://a.example/2.mp3"),
            queue.map { it.uri }
        )
        // https-адреса передаются в сервис как есть: локальный файл подставит
        // существующий buildMediaItem, иначе Media3 стримит.
        assertTrue(queue.all { it.uri.startsWith("https://") })
    }

    @Test
    fun shuffleKeepsAllCatalogTracks() {
        val tracks = CatalogAlarmPlaylist.tracks(playlist())
        val shuffled = AlarmPlaylistQueue.build(tracks, shuffle = true, random = Random(7))
        assertEquals(
            tracks.map { it.trackUri }.toSet(),
            shuffled.map { it.uri }.toSet()
        )
        assertEquals(tracks.size, shuffled.size)
    }
}
