package com.personal.vibratuner.data.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Надёжность удаления скачанных файлов: каждый файл удаляется независимо,
 * результат проверяется, провалившиеся файлы повторяются ровно один раз.
 *
 * Файловый слой — фейк: Android/MediaStore в JVM-тестах недоступны.
 */
class DownloadedFilesCleanerTest {

    /**
     * Фейковый слой файлов.
     * - [present] — какие URL «лежат на диске»;
     * - [throwOnDelete] — удаление бросает исключение (файл остаётся);
     * - [throwOnExists] — проверка наличия бросает исключение;
     * - [failFirstDelete] — первое удаление не срабатывает, второе (повтор) срабатывает.
     */
    private class FakeLayer(
        val present: MutableSet<String> = mutableSetOf(),
        val throwOnDelete: MutableSet<String> = mutableSetOf(),
        val throwOnExists: MutableSet<String> = mutableSetOf(),
        val failFirstDelete: MutableSet<String> = mutableSetOf()
    ) : DownloadedFileLayer {

        val deleteCalls = mutableListOf<String>()

        override fun delete(url: String): Boolean {
            deleteCalls += url
            if (url in throwOnDelete) throw IOException("delete failed: $url")
            if (url in failFirstDelete) {
                failFirstDelete.remove(url)
                return false
            }
            return present.remove(url)
        }

        override fun exists(url: String): Boolean {
            if (url in throwOnExists) throw IOException("exists failed: $url")
            return url in present
        }
    }

    private fun cleaner(vararg layers: DownloadedFileLayer) =
        DownloadedFilesCleaner(layers.toList(), retryDelayMs = 0L)

    @Test
    fun oneThrowingFileDoesNotStopTheRest() = runTest {
        val urls = (1..5).map { "https://example.com/track$it.mp3" }
        val layer = FakeLayer(
            present = urls.toMutableSet(),
            throwOnDelete = mutableSetOf(urls[2])
        )

        val report = cleaner(layer).delete(urls)

        assertEquals(5, report.attempted)
        // Все остальные удалены, несмотря на исключение второго файла.
        assertEquals(urls.toSet() - urls[2], report.deletedUrls)
        assertEquals(setOf(urls[2]), report.remainingUrls)
        // Провалившийся файл был повторён, все файлы были хотя бы попробованы.
        assertTrue(report.retried >= 1)
        assertTrue(report.errors >= 1)
        assertFalse(report.allDeleted)
        urls.forEach { assertTrue("нет попытки удаления $it", it in layer.deleteCalls) }
    }

    @Test
    fun failedDeleteIsRetriedOnceAndSucceeds() = runTest {
        val urls = (1..3).map { "https://example.com/track$it.mp3" }
        val layer = FakeLayer(
            present = urls.toMutableSet(),
            failFirstDelete = mutableSetOf(urls[1])
        )

        val report = cleaner(layer).delete(urls)

        assertTrue(report.allDeleted)
        assertEquals(1, report.retried)
        assertEquals(urls.toSet(), report.deletedUrls)
        // Ровно две попытки для «упрямого» файла: первый проход + один повтор.
        assertEquals(2, layer.deleteCalls.count { it == urls[1] })
    }

    @Test
    fun persistentFailureIsReportedAfterSingleRetry() = runTest {
        val url = "https://example.com/stuck.mp3"
        val layer = FakeLayer(
            present = mutableSetOf(url),
            throwOnDelete = mutableSetOf(url)
        )

        val report = cleaner(layer).delete(listOf(url))

        assertEquals(setOf(url), report.remainingUrls)
        assertEquals(1, report.retried)
        // Один первый проход + ровно один повтор (без бесконечных попыток).
        assertEquals(2, layer.deleteCalls.count { it == url })
        assertTrue(report.errors >= 2)
    }

    @Test
    fun eachLayerIsAttemptedEvenWhenAnotherLayerThrows() = runTest {
        val url = "https://example.com/layered.mp3"
        val broken = FakeLayer(throwOnDelete = mutableSetOf(url))
        val working = FakeLayer(present = mutableSetOf(url))

        val report = cleaner(broken, working).delete(listOf(url))

        assertTrue(report.allDeleted)
        assertTrue("второй слой не тронут", url in working.deleteCalls)
        assertEquals(0, report.remainingUrls.size)
    }

    @Test
    fun existsFailureIsTreatedAsRemainingAndRetried() = runTest {
        val url = "https://example.com/unknown.mp3"
        val layer = FakeLayer(
            present = mutableSetOf(url),
            throwOnExists = mutableSetOf(url)
        )

        val report = cleaner(layer).delete(listOf(url))

        // Не знаем, удалён ли файл, — считаем, что остался, и повторяем.
        assertEquals(setOf(url), report.remainingUrls)
        assertEquals(1, report.retried)
        assertTrue(report.errors >= 1)
    }

    @Test
    fun alreadyMissingFileIsNotAnError() = runTest {
        val layer = FakeLayer()

        val report = cleaner(layer).delete(listOf("https://example.com/missing.mp3"))

        assertTrue(report.allDeleted)
        assertEquals(0, report.retried)
        assertEquals(0, report.errors)
    }

    @Test
    fun blankAndDuplicateUrlsAreNormalized() = runTest {
        val url = "https://example.com/one.mp3"
        val layer = FakeLayer(present = mutableSetOf(url))

        val report = cleaner(layer).delete(listOf("  $url ", url, "   "))

        assertEquals(1, report.attempted)
        assertTrue(report.allDeleted)
        assertEquals(1, layer.deleteCalls.count { it == url })
    }
}
