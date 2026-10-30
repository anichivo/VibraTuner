package com.personal.vibratuner.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Прогресс скачивания: завершённые треки + доля текущего файла. */
class PlaylistDownloadStateTest {

    @Test
    fun progressCountsCompletedTracks() {
        val state = PlaylistDownloadState(
            playlistId = 1L,
            total = 4,
            completed = 1,
            isRunning = true
        )
        assertEquals(0.25f, state.progressFraction, 0.0001f)
        assertEquals(25, state.progressPercent)
    }

    @Test
    fun currentFileFractionMovesProgressSmoothly() {
        val state = PlaylistDownloadState(
            playlistId = 1L,
            total = 4,
            completed = 1,
            isRunning = true,
            currentBytes = 500L,
            currentTotalBytes = 1000L
        )
        assertEquals(0.375f, state.progressFraction, 0.0001f)
        assertEquals(37, state.progressPercent)
    }

    @Test
    fun unknownCurrentSizeDoesNotInventProgress() {
        val state = PlaylistDownloadState(
            playlistId = 1L,
            total = 4,
            completed = 2,
            isRunning = true,
            currentBytes = 900L,
            currentTotalBytes = 0L
        )
        assertEquals(0.5f, state.progressFraction, 0.0001f)
    }

    @Test
    fun progressIsClampedAndCompleteOnlyWhenEverythingIsDone() {
        val running = PlaylistDownloadState(
            playlistId = 1L,
            total = 2,
            completed = 2,
            isRunning = true
        )
        assertEquals(1f, running.progressFraction, 0.0001f)
        assertFalse(running.isComplete)

        val done = running.copy(isRunning = false)
        assertTrue(done.isComplete)
        assertEquals(100, done.progressPercent)
    }
}
