package com.personal.vibratuner.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Итоговая длительность плейлиста: только полная и непустая сумма. */
class PlaylistDurationTest {

    private fun track(uri: String, durationMs: Long) = PlaylistTrackUi(
        trackUri = uri,
        title = uri,
        durationMs = durationMs
    )

    @Test
    fun sumsKnownDurationsOfAllTracks() {
        assertEquals(
            90_000L,
            playlistTotalDurationMs(
                listOf(
                    track("a", 30_000L),
                    track("b", 60_000L)
                )
            )
        )
    }

    @Test
    fun emptyPlaylistHasNoTotal() {
        assertNull(playlistTotalDurationMs(emptyList()))
    }

    @Test
    fun unknownDurationOfAnyTrackHidesPartialTotal() {
        assertNull(
            playlistTotalDurationMs(
                listOf(
                    track("a", 30_000L),
                    track("b", 0L)
                )
            )
        )
    }

    @Test
    fun singleTrackPlaylistIsSummed() {
        assertEquals(42_000L, playlistTotalDurationMs(listOf(track("a", 42_000L))))
    }
}
