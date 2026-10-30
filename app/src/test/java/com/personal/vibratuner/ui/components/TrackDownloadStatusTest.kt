package com.personal.vibratuner.ui.components

import com.personal.vibratuner.data.download.SingleTrackDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2: статус отдельной загрузки строки трека — чистая функция из состояния
 * менеджера, наличия файла и типа URI. Меню предлагает «Скачать» только когда
 * файла ещё нет; во время загрузки и после неё пункт виден, но недоступен.
 */
class TrackDownloadStatusTest {

    private val url = "https://example.com/audio/track.mp3"

    @Test
    fun runningDownloadWinsOverEverythingElse() {
        val status = singleTrackDownloadStatus(
            trackUri = url,
            downloaded = true,
            singleStates = mapOf(
                url to SingleTrackDownloadState(
                    url = url,
                    isRunning = true,
                    bytes = 500L,
                    totalBytes = 1000L
                )
            ),
            canDownload = true
        )
        assertTrue(status is TrackDownloadStatus.Running)
        status as TrackDownloadStatus.Running
        assertEquals(0.5f, status.progressFraction, 0.0001f)
        assertEquals(50, status.progressPercent)
    }

    @Test
    fun doneStateOrExistingFileMeansDownloaded() {
        val doneState = singleTrackDownloadStatus(
            trackUri = url,
            downloaded = false,
            singleStates = mapOf(url to SingleTrackDownloadState(url = url, isDone = true)),
            canDownload = true
        )
        assertEquals(TrackDownloadStatus.Downloaded, doneState)

        // Файл мог быть скачан раньше (в т.ч. загрузкой плейлиста).
        val existingFile = singleTrackDownloadStatus(
            trackUri = url,
            downloaded = true,
            singleStates = emptyMap(),
            canDownload = true
        )
        assertEquals(TrackDownloadStatus.Downloaded, existingFile)
    }

    @Test
    fun downloadableTrackWithoutFileIsAvailable() {
        assertEquals(
            TrackDownloadStatus.Available,
            singleTrackDownloadStatus(
                trackUri = url,
                downloaded = false,
                singleStates = emptyMap(),
                canDownload = true
            )
        )
    }

    @Test
    fun nonHttpsTrackIsUnavailableAndNoDownloadOffered() {
        assertEquals(
            TrackDownloadStatus.Unavailable,
            singleTrackDownloadStatus(
                trackUri = "content://media/external/audio/media/42",
                downloaded = false,
                singleStates = emptyMap(),
                canDownload = true
            )
        )
        // Экран не передал обработчик — пункт «Скачать» не появляется.
        assertEquals(
            TrackDownloadStatus.Unavailable,
            singleTrackDownloadStatus(
                trackUri = url,
                downloaded = false,
                singleStates = emptyMap(),
                canDownload = false
            )
        )
    }

    @Test
    fun unknownFileSizeDoesNotInventProgress() {
        val state = SingleTrackDownloadState(
            url = url,
            isRunning = true,
            bytes = 900L,
            totalBytes = 0L
        )
        assertEquals(0f, state.progressFraction, 0.0001f)
        assertEquals(100, state.copy(isRunning = false, isDone = true).progressPercent)
    }
}
