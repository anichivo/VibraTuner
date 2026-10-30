package com.personal.vibratuner.data.local.saf

import com.personal.vibratuner.data.local.db.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Автосканирование сохранённой папки: добавляются только новые файлы, потерянный
 * persistable-доступ обрабатывается без падения, частые вызовы схлопываются.
 */
class FolderImportSyncTest {

    private class FakeFolderStore(var folder: String? = null) : SavedFolderStore {
        var saveCalls = 0
        override suspend fun savedFolder(): String? = folder
        override suspend fun saveFolder(uri: String?) {
            saveCalls++
            folder = uri
        }
    }

    private class FakeScanner(
        var permission: Boolean = true,
        var tracks: List<TrackEntity> = emptyList(),
        var failure: Throwable? = null
    ) : SavedFolderScanner {
        var scans = 0
        override fun hasReadPermission(treeUri: String): Boolean = permission
        override fun scan(treeUri: String): List<TrackEntity> {
            failure?.let { throw it }
            scans++
            return tracks
        }
    }

    private class FakeInserter(
        var added: Int = 0,
        var failure: Exception? = null
    ) : NewTrackInserter {
        var received: List<TrackEntity>? = null
        override suspend fun insertNew(tracks: List<TrackEntity>): TrackInsertResult? {
            failure?.let { throw it }
            received = tracks
            if (tracks.isEmpty()) return null
            return TrackInsertResult(added = added, skipped = tracks.size - added)
        }
    }

    private fun track(name: String) = TrackEntity(
        uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F$name",
        title = name
    )

    private fun sync(
        store: FakeFolderStore,
        scanner: FakeScanner,
        inserter: FakeInserter,
        clock: () -> Long = { 0L }
    ) = FolderImportSync(
        savedFolder = store,
        scanner = scanner,
        inserter = inserter,
        nowNanos = clock,
        logger = { _, _ -> },
        minIntervalMs = 0L
    )

    @Test
    fun noSavedFolderDoesNothing() = runTest {
        val store = FakeFolderStore(folder = null)
        val scanner = FakeScanner()
        val inserter = FakeInserter()
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.NoFolder, sync.scanSaved("test"))
        assertEquals(0, scanner.scans)
        assertNull(sync.event.value)
    }

    @Test
    fun newFilesAreInsertedAndReported() = runTest {
        val folder = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val store = FakeFolderStore(folder)
        val scanner = FakeScanner(tracks = listOf(track("a.mp3"), track("b.mp3")))
        val inserter = FakeInserter(added = 2)
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.Added, sync.scanSaved("test"))

        assertEquals(2, inserter.received?.size)
        assertEquals(FolderImportEvent.Added(2), sync.event.value)
        // Событие одноразовое: после показа его снимаем.
        sync.consumeEvent()
        assertNull(sync.event.value)
    }

    @Test
    fun noNewFilesIsNotReported() = runTest {
        val store = FakeFolderStore("content://tree/folder")
        val scanner = FakeScanner(tracks = listOf(track("a.mp3")))
        val inserter = FakeInserter(added = 0)
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.Unchanged, sync.scanSaved("test"))
        assertNull(sync.event.value)
    }

    @Test
    fun lostPermissionForgetsFolderAndReportsOnce() = runTest {
        val store = FakeFolderStore("content://tree/folder")
        val scanner = FakeScanner(permission = false)
        val inserter = FakeInserter(added = 1)
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.PermissionLost, sync.scanSaved("test"))
        assertEquals(FolderImportEvent.PermissionLost, sync.event.value)
        // URI забыт: без доступа автосканирование бессмысленно.
        assertNull(store.folder)
        // Новых попыток чтения не было.
        assertEquals(0, scanner.scans)

        sync.consumeEvent()
        // Повторного сообщения нет: папка уже забыта, второй проход ничего не делает.
        assertEquals(FolderScanOutcome.NoFolder, sync.scanSaved("test"))
        assertNull(sync.event.value)
    }

    @Test
    fun securityExceptionDuringScanIsTreatedAsLostPermission() = runTest {
        val store = FakeFolderStore("content://tree/folder")
        val scanner = FakeScanner(failure = SecurityException("grant revoked"))
        val inserter = FakeInserter(added = 1)
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.PermissionLost, sync.scanSaved("test"))
        assertEquals(FolderImportEvent.PermissionLost, sync.event.value)
        assertNull(store.folder)
    }

    @Test
    fun scanFailureKeepsFolderAndDoesNotCrash() = runTest {
        val folder = "content://tree/folder"
        val store = FakeFolderStore(folder)
        val scanner = FakeScanner(failure = IOException("disk error"))
        val inserter = FakeInserter(added = 1)
        val sync = sync(store, scanner, inserter)

        assertEquals(FolderScanOutcome.Failed, sync.scanSaved("test"))
        assertEquals(folder, store.folder)
        assertNull(sync.event.value)
    }

    @Test
    fun frequentRescansAreCollapsed() = runTest {
        var now = 0L
        val store = FakeFolderStore("content://tree/folder")
        val scanner = FakeScanner(tracks = listOf(track("a.mp3")))
        val inserter = FakeInserter(added = 0)
        val sync = FolderImportSync(
            savedFolder = store,
            scanner = scanner,
            inserter = inserter,
            nowNanos = { now },
            logger = { _, _ -> },
            minIntervalMs = 2_000L
        )

        assertEquals(FolderScanOutcome.Unchanged, sync.scanSaved("start"))
        assertEquals(1, scanner.scans)
        now += 500L * 1_000_000L
        assertEquals(FolderScanOutcome.Throttled, sync.scanSaved("foreground"))
        assertEquals(1, scanner.scans)
        now += 2_000L * 1_000_000L
        assertEquals(FolderScanOutcome.Unchanged, sync.scanSaved("library"))
        assertEquals(2, scanner.scans)
    }

    @Test
    fun userFolderImportMarksScanSoNoImmediateRescan() = runTest {
        val store = FakeFolderStore(null)
        val scanner = FakeScanner()
        val inserter = FakeInserter()
        var now = 10L
        val sync = FolderImportSync(
            savedFolder = store,
            scanner = scanner,
            inserter = inserter,
            nowNanos = { now },
            logger = { _, _ -> },
            minIntervalMs = 2_000L
        )

        sync.rememberFolder("content://tree/folder")
        assertEquals("content://tree/folder", store.folder)
        sync.markScannedNow()
        now += 100L * 1_000_000L

        assertEquals(FolderScanOutcome.Throttled, sync.scanSaved("library_screen"))
        assertEquals(0, scanner.scans)
        assertTrue(store.saveCalls >= 1)
    }
}
