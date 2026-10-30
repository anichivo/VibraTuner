package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Состав меню строки трека — чистая функция, общая для всех списков.
 * Часть 3: «Переместить в…» существует только внутри плейлиста.
 */
class TrackRowMenuTest {

    @Test
    fun libraryMenuHasAddAndDeleteOnly() {
        assertEquals(
            listOf(TrackRowAction.ADD_TO_PLAYLIST, TrackRowAction.DELETE_TRACK),
            trackRowActions(TrackRowContext.Library)
        )
    }

    @Test
    fun catalogMenuHasOnlyAdd() {
        assertEquals(
            listOf(TrackRowAction.ADD_TO_PLAYLIST),
            trackRowActions(TrackRowContext.Catalog)
        )
    }

    @Test
    fun playlistMenuHasAddMoveAndRemove() {
        assertEquals(
            listOf(
                TrackRowAction.ADD_TO_PLAYLIST,
                TrackRowAction.MOVE_TO_PLAYLIST,
                TrackRowAction.REMOVE_FROM_PLAYLIST
            ),
            trackRowActions(TrackRowContext.InPlaylist(playlistId = 7L))
        )
    }

    @Test
    fun moveExistsOnlyInsidePlaylist() {
        assertTrue(
            TrackRowAction.MOVE_TO_PLAYLIST in
                trackRowActions(TrackRowContext.InPlaylist(playlistId = 1L))
        )
        assertFalse(TrackRowAction.MOVE_TO_PLAYLIST in trackRowActions(TrackRowContext.Library))
        assertFalse(TrackRowAction.MOVE_TO_PLAYLIST in trackRowActions(TrackRowContext.Catalog))
    }

    @Test
    fun contextPlaylistIdDoesNotChangeActions() {
        assertEquals(
            trackRowActions(TrackRowContext.InPlaylist(playlistId = 1L)),
            trackRowActions(TrackRowContext.InPlaylist(playlistId = 42L))
        )
    }

    @Test
    fun catalogMenuOffersDownloadOnlyForDownloadableTrack() {
        // По умолчанию (трек библиотеки с устройства) пункта «Скачать» нет.
        assertEquals(
            listOf(TrackRowAction.ADD_TO_PLAYLIST),
            trackRowActions(TrackRowContext.Catalog, TrackDownloadStatus.Unavailable)
        )
        listOf(
            TrackDownloadStatus.Available,
            TrackDownloadStatus.Running(progressFraction = 0.5f, progressPercent = 50)
        ).forEach { status ->
            assertEquals(
                listOf(TrackRowAction.ADD_TO_PLAYLIST, TrackRowAction.DOWNLOAD_TRACK),
                trackRowActions(TrackRowContext.Catalog, status)
            )
        }
    }

    @Test
    fun downloadedTrackOffersDeleteFromStorage() {
        // Part 3: «Удалить из памяти» — только у реально скачанного файла.
        assertEquals(
            listOf(
                TrackRowAction.ADD_TO_PLAYLIST,
                TrackRowAction.DOWNLOAD_TRACK,
                TrackRowAction.DELETE_DOWNLOADED
            ),
            trackRowActions(TrackRowContext.Catalog, TrackDownloadStatus.Downloaded)
        )
        assertEquals(
            listOf(
                TrackRowAction.ADD_TO_PLAYLIST,
                TrackRowAction.MOVE_TO_PLAYLIST,
                TrackRowAction.DOWNLOAD_TRACK,
                TrackRowAction.REMOVE_FROM_PLAYLIST,
                TrackRowAction.DELETE_DOWNLOADED
            ),
            trackRowActions(
                TrackRowContext.InPlaylist(playlistId = 3L),
                TrackDownloadStatus.Downloaded
            )
        )
        assertEquals(
            listOf(
                TrackRowAction.ADD_TO_PLAYLIST,
                TrackRowAction.DELETE_TRACK,
                TrackRowAction.DELETE_DOWNLOADED
            ),
            trackRowActions(TrackRowContext.Library, TrackDownloadStatus.Downloaded)
        )
    }

    @Test
    fun notDownloadedTracksNeverOfferDeleteFromStorage() {
        listOf(
            TrackDownloadStatus.Unavailable,
            TrackDownloadStatus.Available,
            TrackDownloadStatus.Running(progressFraction = 0.5f, progressPercent = 50)
        ).forEach { status ->
            assertFalse(TrackRowAction.DELETE_DOWNLOADED in trackRowActions(TrackRowContext.Library, status))
            assertFalse(TrackRowAction.DELETE_DOWNLOADED in trackRowActions(TrackRowContext.Catalog, status))
            assertFalse(
                TrackRowAction.DELETE_DOWNLOADED in
                    trackRowActions(TrackRowContext.InPlaylist(playlistId = 1L), status)
            )
        }
    }

    @Test
    fun playlistMenuKeepsRemoveLastWhenDownloadIsOffered() {
        assertEquals(
            listOf(
                TrackRowAction.ADD_TO_PLAYLIST,
                TrackRowAction.MOVE_TO_PLAYLIST,
                TrackRowAction.DOWNLOAD_TRACK,
                TrackRowAction.REMOVE_FROM_PLAYLIST
            ),
            trackRowActions(
                TrackRowContext.InPlaylist(playlistId = 3L),
                TrackDownloadStatus.Available
            )
        )
    }

    @Test
    fun libraryMenuNeverOffersDownload() {
        // «Моя коллекция» — импортированные треки; Stage 2 не меняет её меню.
        assertEquals(
            listOf(TrackRowAction.ADD_TO_PLAYLIST, TrackRowAction.DELETE_TRACK),
            trackRowActions(TrackRowContext.Library, TrackDownloadStatus.Available)
        )
    }
}
