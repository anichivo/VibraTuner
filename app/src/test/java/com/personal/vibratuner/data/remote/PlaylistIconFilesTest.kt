package com.personal.vibratuner.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Имя файла кеша, проверка PNG и лимит размера (Stage 2). */
class PlaylistIconFilesTest {

    private val pngHeader = byteArrayOf(
        0x89.toByte(),
        0x50.toByte(),
        0x4E.toByte(),
        0x47.toByte(),
        0x0D.toByte(),
        0x0A.toByte(),
        0x1A.toByte(),
        0x0A.toByte()
    )

    @Test
    fun `png signature is accepted`() {
        assertTrue(PlaylistIconFiles.isPngSignature(pngHeader))
        assertTrue(PlaylistIconFiles.isPngSignature(pngHeader + ByteArray(100)))
    }

    @Test
    fun `short foreign and corrupted data is rejected`() {
        assertFalse(PlaylistIconFiles.isPngSignature(ByteArray(0)))
        assertFalse(PlaylistIconFiles.isPngSignature(pngHeader.copyOf(7)))
        assertFalse(PlaylistIconFiles.isPngSignature("GIF89a-not-a-png".toByteArray()))

        val corrupted = pngHeader.copyOf()
        corrupted[1] = 0x00
        assertFalse(PlaylistIconFiles.isPngSignature(corrupted))
    }

    @Test
    fun `size limit is 256 KB and rejects empty files`() {
        assertFalse(PlaylistIconFiles.isWithinSizeLimit(0L))
        assertFalse(PlaylistIconFiles.isWithinSizeLimit(-1L))
        assertTrue(PlaylistIconFiles.isWithinSizeLimit(1L))
        assertTrue(PlaylistIconFiles.isWithinSizeLimit(256L * 1024L))
        assertFalse(PlaylistIconFiles.isWithinSizeLimit(256L * 1024L + 1L))
    }

    @Test
    fun `file name is sha256 hex of the url plus png extension`() {
        val url = "https://github.com/anichivo/playlists/raw/main/icons/a.png"

        val name = PlaylistIconFiles.fileNameFor(url)

        assertTrue(name != null)
        val actual = requireNotNull(name)
        assertEquals(68, actual.length) // 64 hex + ".png"
        assertTrue(actual.endsWith(".png"))
        assertTrue(actual.removeSuffix(".png").all { it in "0123456789abcdef" })
        // Имя детерминировано и не зависит от пробелов вокруг ссылки.
        assertEquals(actual, PlaylistIconFiles.fileNameFor("  $url  "))
        // Другой адрес — другое имя файла (смена иконки = новый адрес).
        assertNotEquals(
            actual,
            PlaylistIconFiles.fileNameFor(
                "https://github.com/anichivo/playlists/raw/main/icons/b.png"
            )
        )
    }

    @Test
    fun `non https references are not cached`() {
        assertNull(PlaylistIconFiles.fileNameFor("http://example.com/a.png"))
        assertNull(PlaylistIconFiles.fileNameFor("content://media/a.png"))
        assertNull(PlaylistIconFiles.fileNameFor("file:///sdcard/a.png"))
        assertNull(PlaylistIconFiles.fileNameFor(""))
        assertNull(PlaylistIconFiles.fileFor(File("cache"), "http://example.com/a.png"))
    }

    @Test
    fun `file name cannot escape the cache directory`() {
        val directory = File("cache/playlist_icons")

        val file = PlaylistIconFiles.fileFor(directory, "https://example.com/../../a.png")

        assertTrue(file != null)
        assertEquals(directory, requireNotNull(file).parentFile)
        assertTrue(requireNotNull(file).name.endsWith(".png"))
    }

    @Test
    fun `isPngFile reads the real file signature`() {
        val file = File.createTempFile("playlist-icon", ".png")
        try {
            file.writeBytes(pngHeader + ByteArray(512))
            assertTrue(PlaylistIconFiles.isPngFile(file))

            file.writeBytes("definitely not a png".toByteArray())
            assertFalse(PlaylistIconFiles.isPngFile(file))

            file.writeBytes(pngHeader.copyOf(5))
            assertFalse(PlaylistIconFiles.isPngFile(file))
        } finally {
            file.delete()
        }
        assertFalse(
            PlaylistIconFiles.isPngFile(
                File(file.parentFile, "missing-icon-${System.nanoTime()}.png")
            )
        )
    }
}
