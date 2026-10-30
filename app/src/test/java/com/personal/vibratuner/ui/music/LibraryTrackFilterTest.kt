package com.personal.vibratuner.ui.music

import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3: фильтр списка «Треки» в «Моей коллекции». Чипы «Все»/«Скачанные»/
 * «Импортированные» фильтруют ТОЛЬКО самостоятельные строки `tracks` по
 * `source` (Stage 2); треки, скачанные лишь в составе плейлиста, в `tracks` не
 * попадают, поэтому в «Скачанные» не показываются по построению.
 */
class LibraryTrackFilterTest {

    private fun track(id: Long, source: String): TrackEntity = TrackEntity(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Трек $id",
        source = source
    )

    private val imported1 = track(1, TrackSource.IMPORTED.value)
    private val downloaded1 = track(2, TrackSource.DOWNLOADED.value)
    private val imported2 = track(3, TrackSource.IMPORTED.value)
    private val downloaded2 = track(4, TrackSource.DOWNLOADED.value)
    private val all = listOf(imported1, downloaded1, imported2, downloaded2)

    @Test
    fun allKeepsEveryTrackInOrder() {
        assertEquals(all, filterLibraryTracks(all, LibraryTrackFilter.ALL))
    }

    @Test
    fun downloadedContainsOnlyDownloadedTracks() {
        assertEquals(
            listOf(downloaded1, downloaded2),
            filterLibraryTracks(all, LibraryTrackFilter.DOWNLOADED)
        )
    }

    @Test
    fun importedContainsOnlyImportedTracks() {
        assertEquals(
            listOf(imported1, imported2),
            filterLibraryTracks(all, LibraryTrackFilter.IMPORTED)
        )
    }

    @Test
    fun unknownOrEmptySourceIsTreatedAsImported() {
        val legacy = track(5, "")
        val garbage = track(6, "LEGACY")
        val tracks = listOf(legacy, garbage, downloaded1)

        assertEquals(
            listOf(legacy, garbage),
            filterLibraryTracks(tracks, LibraryTrackFilter.IMPORTED)
        )
        assertEquals(
            listOf(downloaded1),
            filterLibraryTracks(tracks, LibraryTrackFilter.DOWNLOADED)
        )
    }

    @Test
    fun filtersOnEmptyListAreEmpty() {
        LibraryTrackFilter.entries.forEach { filter ->
            assertTrue(filterLibraryTracks(emptyList(), filter).isEmpty())
        }
    }
}
