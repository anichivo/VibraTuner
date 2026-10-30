package com.personal.vibratuner.data.local.alarm

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPlaybackSelectionTest {

    private val library = listOf(
        TrackEntity(id = 1L, uri = "content://one", title = "One"),
        TrackEntity(id = 2L, uri = "content://two", title = "Two")
    )

    @Test
    fun noTrackSelected_playsWholeLibraryWithoutLoopFlag() {
        val target = AlarmPlaybackSelection.resolve(trackId = null, tracks = library)

        assertNull(target.track)
        assertFalse(target.loopsOnlySelected)
        assertFalse(target.selectedTrackMissing)
    }

    @Test
    fun selectedTrack_loopsForWholeAlarm() {
        val target = AlarmPlaybackSelection.resolve(trackId = 2L, tracks = library)

        assertEquals(2L, target.track?.id)
        assertTrue(target.loopsOnlySelected)
        assertFalse(target.selectedTrackMissing)
    }

    @Test
    fun selectedButDeletedTrack_fallsBackToWholeLibrary() {
        val target = AlarmPlaybackSelection.resolve(trackId = 99L, tracks = library)

        assertNull(target.track)
        assertFalse(target.loopsOnlySelected)
        assertTrue(target.selectedTrackMissing)
    }

    @Test
    fun emptyLibraryWithSelectedTrack_fallsBackSafely() {
        val target = AlarmPlaybackSelection.resolve(trackId = 1L, tracks = emptyList())

        assertNull(target.track)
        assertFalse(target.loopsOnlySelected)
        assertTrue(target.selectedTrackMissing)
    }
}
