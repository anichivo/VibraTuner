package com.personal.vibratuner.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Чистая логика [TrackDownloadStore] на JVM (часть 3): имя локального файла —
 * детерминированная функция https-URL, поэтому «скачан» проверяется без БД.
 * Сеть не используется.
 */
class TrackDownloadStoreTest {

    private val directory = Files.createTempDirectory("track-downloads").toFile()
    private val store = TrackDownloadStore(directory)

    @Test
    fun fileNameIsDeterministicAndDependsOnUrl() {
        val first = TrackDownloadStore.fileNameFor("https://example.com/a.mp3")
        val second = TrackDownloadStore.fileNameFor("https://example.com/a.mp3")

        assertEquals(first, second)
        assertNotEquals(first, TrackDownloadStore.fileNameFor("https://example.com/b.mp3"))
        assertTrue(first!!.endsWith(".mp3"))
        // SHA-256 в hex (64 символа) + точка + расширение.
        assertEquals(68, first.length)
    }

    @Test
    fun onlyHttpsUrlsAreDownloadable() {
        assertNull(TrackDownloadStore.fileNameFor("content://media/external/audio/media/1"))
        assertNull(TrackDownloadStore.fileNameFor("file:///data/user/0/app/a.mp3"))
        assertNull(TrackDownloadStore.fileNameFor("http://example.com/a.mp3"))
        assertNull(store.fileFor("content://media/external/audio/media/1"))
    }

    @Test
    fun unknownExtensionFallsBackToNeutralAudioExtension() {
        assertTrue(TrackDownloadStore.fileNameFor("https://example.com/stream?token=1")!!.endsWith(".audio"))
        assertTrue(TrackDownloadStore.fileNameFor("https://example.com")!!.endsWith(".audio"))
        assertTrue(TrackDownloadStore.fileNameFor("https://example.com/a.MP3?x=1")!!.endsWith(".mp3"))
    }

    @Test
    fun mimeTypeFollowsFileExtensionForMediaStore() {
        assertEquals("audio/mpeg", TrackDownloadStore.mimeTypeFor("track.mp3"))
        assertEquals("audio/mp4", TrackDownloadStore.mimeTypeFor("track.m4a"))
        assertEquals("audio/ogg", TrackDownloadStore.mimeTypeFor("track.ogg"))
        assertEquals("audio/flac", TrackDownloadStore.mimeTypeFor("track.flac"))
        assertEquals("audio/wav", TrackDownloadStore.mimeTypeFor("track.wav"))
        // Неизвестное расширение: Media3 определит формат по содержимому.
        assertEquals("application/octet-stream", TrackDownloadStore.mimeTypeFor("track.audio"))
    }

    @Test
    fun recognizesMediaStoreRenamedCopiesOfSameDownload() {
        val base = TrackDownloadStore.fileNameFor("https://example.com/a.mp3")!!

        assertTrue(TrackDownloadStore.isSameFileName(base, base))
        assertTrue(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")} (1).mp3", base))
        assertTrue(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")} (42).mp3", base))
        assertTrue(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")}(2).mp3", base))

        assertFalse(TrackDownloadStore.isSameFileName(null, base))
        assertFalse(TrackDownloadStore.isSameFileName("другой-файл.mp3", base))
        assertFalse(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")} (a).mp3", base))
        assertFalse(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")} (1).ogg", base))
        assertFalse(TrackDownloadStore.isSameFileName("${base.removeSuffix(".mp3")} ().mp3", base))
    }

    @Test
    fun existingFileIgnoresMissingAndEmptyFiles() {
        val url = "https://example.com/track.mp3"
        assertNull(store.existingFile(url))

        val target = store.fileFor(url)!!
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(0))
        assertNull(store.existingFile(url))

        target.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(target, store.existingFile(url))
    }

    @Test
    fun deleteRemovesDownloadedAndTemporaryFiles() {
        val url = "https://example.com/track.mp3"
        val target = store.fileFor(url)!!
        target.parentFile!!.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        val temp = store.tempFileFor(target)
        temp.writeBytes(byteArrayOf(4, 5))

        assertTrue(store.delete(url))
        assertFalse(target.exists())
        assertFalse(temp.exists())
        // Повторное удаление — безопасный no-op.
        assertFalse(store.delete(url))
    }
}
