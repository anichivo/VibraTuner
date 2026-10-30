package com.personal.vibratuner.data.download

import com.personal.vibratuner.data.local.db.TrackDao
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Ленивое дозаполнение длительности скачанных треков (Stage 3):
 * - трогаются ТОЛЬКО строки `source = DOWNLOADED` с неизвестной длительностью;
 * - известная длительность и импортированные треки не меняются;
 * - сбой файла/БД не роняет проход и не мешает остальным трекам;
 * - повторный запуск в процессе ничего не делает (цикла и повторов нет).
 *
 * Room/MediaStore в JVM-тестах недоступны: DAO и чтение файла — фейки.
 */
class DownloadedDurationBackfillTest {

    /** Фейк `TrackDao`: хранит строки в памяти, чтение может падать. */
    private class FakeTrackDao(
        val stored: MutableList<TrackEntity> = mutableListOf(),
        private val failQuery: Boolean = false
    ) : TrackDao {

        override fun getAllTracksFlow(): Flow<List<TrackEntity>> = flowOf(stored)

        override suspend fun getAllTracks(): List<TrackEntity> = stored

        override suspend fun getTrackById(id: Long): TrackEntity? =
            stored.firstOrNull { it.id == id }

        override suspend fun getUrisBySource(source: String): List<String> =
            stored.filter { it.source == source }.map { it.uri }

        override suspend fun getTracksWithoutDuration(source: String): List<TrackEntity> {
            if (failQuery) throw IOException("database is locked")
            // Как в SQL: скачанные по source ИЛИ https-строки (старые скачанные).
            return stored.filter {
                it.durationMs <= 0L && (it.source == source || it.uri.startsWith("https://"))
            }
        }

        override suspend fun updateDurationIfMissing(uri: String, durationMs: Long): Int {
            val index = stored.indexOfFirst { it.uri == uri && it.durationMs <= 0L }
            if (index < 0) return 0
            stored[index] = stored[index].copy(durationMs = durationMs)
            return 1
        }

        override suspend fun insertTracks(tracks: List<TrackEntity>): List<Long> =
            tracks.map { track ->
                stored.add(track)
                track.id
            }

        override suspend fun deleteTrack(track: TrackEntity) {
            stored.removeAll { it.id == track.id }
        }

        override suspend fun deleteById(id: Long) {
            stored.removeAll { it.id == id }
        }

        override suspend fun clearAll() {
            stored.clear()
        }
    }

    private fun downloaded(id: Long, uri: String, durationMs: Long = 0L) =
        TrackEntity(id = id, uri = uri, title = "Трек $id", durationMs = durationMs, source = TrackSource.DOWNLOADED.value)

    private fun imported(id: Long, uri: String, durationMs: Long = 0L) =
        TrackEntity(id = id, uri = uri, title = "Трек $id", durationMs = durationMs, source = TrackSource.IMPORTED.value)

    @Test
    fun `заполняет только скачанные треки без длительности`() = runTest {
        val dao = FakeTrackDao(
            mutableListOf(
                downloaded(1, "https://a/1.mp3"),
                downloaded(2, "https://a/2.mp3", durationMs = 90_000L),
                imported(3, "content://doc/3")
            )
        )
        val read = mutableListOf<String>()
        val backfill = DownloadedDurationBackfill(
            trackDao = dao,
            durationMsOf = { url ->
                read += url
                if (url == "https://a/1.mp3") 180_000L else 0L
            },
            logger = { _, _ -> }
        )

        assertEquals(1, backfill.runOnce())

        assertEquals(180_000L, dao.stored.first { it.id == 1L }.durationMs)
        assertEquals(90_000L, dao.stored.first { it.id == 2L }.durationMs)
        assertEquals(0L, dao.stored.first { it.id == 3L }.durationMs)
        // Уже известная длительность и импортированный трек даже не читаются.
        assertEquals(listOf("https://a/1.mp3"), read)
    }

    @Test
    fun `заполняет старые скачанные строки с https-uri и source IMPORTED`() = runTest {
        val dao = FakeTrackDao(
            mutableListOf(
                imported(1, "https://old/1.mp3"),
                imported(2, "content://doc/2")
            )
        )
        val backfill = DownloadedDurationBackfill(
            trackDao = dao,
            durationMsOf = { url -> if (url.startsWith("https://old/")) 240_000L else 0L },
            logger = { _, _ -> }
        )

        assertEquals(1, backfill.runOnce())
        assertEquals(240_000L, dao.stored.first { it.id == 1L }.durationMs)
        assertEquals(0L, dao.stored.first { it.id == 2L }.durationMs)
    }

    @Test
    fun `неизвестная длительность файла оставляет строку нетронутой`() = runTest {
        val dao = FakeTrackDao(mutableListOf(downloaded(1, "https://a/1.mp3")))
        val backfill = DownloadedDurationBackfill(
            trackDao = dao,
            durationMsOf = { 0L },
            logger = { _, _ -> }
        )

        assertEquals(0, backfill.runOnce())
        assertEquals(0L, dao.stored.single().durationMs)
    }

    @Test
    fun `сбой одного файла не мешает остальным`() = runTest {
        val dao = FakeTrackDao(
            mutableListOf(
                downloaded(1, "https://a/broken.mp3"),
                downloaded(2, "https://a/2.mp3")
            )
        )
        val backfill = DownloadedDurationBackfill(
            trackDao = dao,
            durationMsOf = { url ->
                if (url.endsWith("broken.mp3")) throw IOException("файл занят")
                60_000L
            },
            logger = { _, _ -> }
        )

        assertEquals(1, backfill.runOnce())
        assertEquals(0L, dao.stored.first { it.id == 1L }.durationMs)
        assertEquals(60_000L, dao.stored.first { it.id == 2L }.durationMs)
    }

    @Test
    fun `повторный запуск в процессе ничего не делает`() = runTest {
        val dao = FakeTrackDao(mutableListOf(downloaded(1, "https://a/1.mp3")))
        var reads = 0
        val backfill = DownloadedDurationBackfill(
            trackDao = dao,
            durationMsOf = {
                reads++
                120_000L
            },
            logger = { _, _ -> }
        )

        assertEquals(1, backfill.runOnce())
        assertEquals(0, backfill.runOnce())
        assertEquals(1, reads)
    }

    @Test
    fun `сбой чтения списка только логируется`() = runTest {
        val logs = mutableListOf<String>()
        val backfill = DownloadedDurationBackfill(
            trackDao = FakeTrackDao(failQuery = true),
            durationMsOf = { 1L },
            logger = { message, error ->
                logs += message
                assertTrue(error != null)
            }
        )

        assertEquals(0, backfill.runOnce())
        assertEquals(1, logs.size)
    }

    @Test
    fun `пустая библиотека не пишет лишних логов`() = runTest {
        val logs = mutableListOf<String>()
        val backfill = DownloadedDurationBackfill(
            trackDao = FakeTrackDao(),
            durationMsOf = { 1L },
            logger = { message, _ -> logs += message }
        )

        assertEquals(0, backfill.runOnce())
        assertTrue(logs.isEmpty())
    }
}
