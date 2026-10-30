package com.personal.vibratuner.data.local.alarm

import com.personal.vibratuner.data.local.db.PlaylistTrackEntity
import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlarmPlaylistQueueTest {

    private fun track(id: Long, uri: String, title: String = "T$id"): PlaylistTrackEntity =
        PlaylistTrackEntity(
            id = id,
            playlistId = 2L,
            trackUri = uri,
            title = title,
            artist = "Artist $id",
            addedAt = id
        )

    private val ordered = listOf(
        track(1, "content://media/a", "A"),
        track(2, "content://media/b", "B"),
        track(3, "content://media/c", "C"),
        track(4, "content://media/d", "D")
    )

    @Test
    fun buildWithoutShufflePreservesStoredOrder() {
        val queue = AlarmPlaylistQueue.build(ordered, shuffle = false)

        assertEquals(listOf("A", "B", "C", "D"), queue.map { it.title })
        assertEquals(
            listOf("content://media/a", "content://media/b", "content://media/c", "content://media/d"),
            queue.map { it.uri }
        )
    }

    @Test
    fun buildKeepsPlaylistSnapshotFieldsForMissingLibraryTracks() {
        val queue = AlarmPlaylistQueue.build(ordered, shuffle = false)

        assertEquals("Artist 1", queue.first().artist)
        assertEquals(0L, queue.first().durationMs)
        assertTrue(queue.all { it.id < 0L })
        assertEquals(queue.size, queue.map { it.id }.distinct().size)
    }

    @Test
    fun buildWithShuffleContainsSameTracksInDifferentOrder() {
        val many = (1..10).map { index ->
            track(index.toLong(), "content://media/$index")
        }
        val queue: List<TrackEntity> =
            AlarmPlaylistQueue.build(many, shuffle = true, random = Random(7))

        val expectedUris = many.map { track -> track.trackUri }
        val actualUris = queue.map { track -> track.uri }
        assertEquals(expectedUris.toSet(), actualUris.toSet())
        assertEquals(many.size, queue.size)
        assertNotEquals(expectedUris, actualUris)
    }

    @Test
    fun buildOfEmptyPlaylistIsEmpty() {
        assertTrue(AlarmPlaylistQueue.build(emptyList(), shuffle = false).isEmpty())
        assertTrue(AlarmPlaylistQueue.build(emptyList(), shuffle = true).isEmpty())
    }
}
