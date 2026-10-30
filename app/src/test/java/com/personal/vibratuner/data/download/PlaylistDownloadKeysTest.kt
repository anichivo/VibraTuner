package com.personal.vibratuner.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключи заданий скачивания: плейлист библиотеки — его id, плейлист каталога —
 * стабильный отрицательный ключ. Один плейлист = одно задание.
 */
class PlaylistDownloadKeysTest {

    @Test
    fun libraryPlaylistUsesItsOwnId() {
        assertEquals(5L, PlaylistDownloadKeys.libraryPlaylist(5L))
        assertEquals(1L, PlaylistDownloadKeys.libraryPlaylist(1L))
    }

    @Test
    fun catalogPlaylistKeyIsStableAndNegative() {
        val first = PlaylistDownloadKeys.catalogPlaylist("morning-soft")
        val second = PlaylistDownloadKeys.catalogPlaylist("morning-soft")
        assertEquals(first, second)
        assertTrue("каталожный ключ должен быть отрицательным", first < 0L)
    }

    @Test
    fun differentCatalogPlaylistsGetDifferentKeys() {
        val ids = listOf("morning-soft", "evening", "night", "", "a", "b")
        val keys = ids.map { PlaylistDownloadKeys.catalogPlaylist(it) }
        assertEquals(ids.size, keys.toSet().size)
        keys.forEach { assertTrue(it < 0L) }
    }

    @Test
    fun catalogKeysCannotCollideWithLibraryIds() {
        // Положительные id и 0 заняты плейлистами библиотеки.
        repeat(100) { index ->
            val key = PlaylistDownloadKeys.catalogPlaylist("playlist-$index")
            assertNotEquals(0L, key)
            assertTrue(key < 0L)
        }
    }

    @Test
    fun onlyHttpsTracksAreDownloadable() {
        assertTrue(
            PlaylistDownloadKeys.hasDownloadableTracks(
                listOf("https://a.example/1.mp3", "content://media/2")
            )
        )
        assertFalse(
            PlaylistDownloadKeys.hasDownloadableTracks(
                listOf("content://media/2", "file:///data/3.mp3")
            )
        )
        assertFalse(PlaylistDownloadKeys.hasDownloadableTracks(emptyList()))
    }

    @Test
    fun allDownloadableDownloadedRequiresEveryHttpsFile() {
        val tracks = listOf(
            "https://a.example/1.mp3",
            "https://a.example/2.mp3",
            "content://media/imported"
        )
        // Локальный (импортированный) трек не мешает: его файл — не наш.
        assertTrue(
            PlaylistDownloadKeys.allDownloadableDownloaded(
                tracks,
                setOf("https://a.example/1.mp3", "https://a.example/2.mp3")
            )
        )
        assertFalse(
            PlaylistDownloadKeys.allDownloadableDownloaded(
                tracks,
                setOf("https://a.example/1.mp3")
            )
        )
        assertFalse(PlaylistDownloadKeys.allDownloadableDownloaded(tracks, emptySet()))
    }

    @Test
    fun allDownloadableDownloadedIsFalseWhenNothingIsDownloadable() {
        assertFalse(
            PlaylistDownloadKeys.allDownloadableDownloaded(
                listOf("content://media/1", "file:///data/2.mp3"),
                setOf("content://media/1", "file:///data/2.mp3")
            )
        )
        assertFalse(PlaylistDownloadKeys.allDownloadableDownloaded(emptyList(), emptySet()))
    }
}
