package com.personal.vibratuner.data.local.saf

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Общая проверка «такой трек уже есть» (часть 2 фидбека: дубли при импорте).
 * Ключ выводится из строки URI, поэтому тест чистый JVM-ный, без Android API.
 */
class TrackIdentityTest {

    private val authority = "com.android.externalstorage.documents"

    /** «Выбрать папку целиком»: child-URI документа внутри tree. */
    private val folderUri =
        "content://$authority/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack.mp3"

    /** «Выбрать файлы»: тот же файл, но документ-URI без tree. */
    private val singleFileUri =
        "content://$authority/document/primary%3AMusic%2Ftrack.mp3"

    private fun track(uri: String, title: String = "Трек") = TrackEntity(uri = uri, title = title)

    // --- 1. Канонический ключ одинаков для tree-URI и документ-URI ---

    @Test
    fun treeUriAndDocumentUriOfSameFile_haveSameKey() {
        assertEquals(TrackIdentity.keyOf(folderUri), TrackIdentity.keyOf(singleFileUri))
    }

    @Test
    fun documentKey_containsAuthorityAndDecodedDocumentId() {
        assertEquals(
            "doc:$authority:primary:Music/track.mp3",
            TrackIdentity.documentKeyOf(folderUri)
        )
        assertEquals(
            "doc:$authority:primary:Music/track.mp3",
            TrackIdentity.documentKeyOf(singleFileUri)
        )
    }

    @Test
    fun differentFilesInSameFolder_haveDifferentKeys() {
        val other = "content://$authority/tree/primary%3AMusic/document/primary%3AMusic%2Fother.mp3"
        assertTrue(TrackIdentity.keyOf(folderUri) != TrackIdentity.keyOf(other))
    }

    @Test
    fun sameDocumentIdInDifferentProviders_haveDifferentKeys() {
        val media = "content://com.android.providers.media.documents/document/audio%3A1"
        val external = "content://$authority/document/audio%3A1"
        assertTrue(TrackIdentity.keyOf(media) != TrackIdentity.keyOf(external))
    }

    @Test
    fun treeUriWithTwoLevelNesting_isNotADocumentKey() {
        // У URI нет сегмента document — это не документ, значит ключ из URI.
        val treeRoot = "content://$authority/tree/primary%3AMusic"
        assertNull(TrackIdentity.documentKeyOf(treeRoot))
        assertEquals("uri:$treeRoot", TrackIdentity.keyOf(treeRoot))
    }

    // --- 2. Фолбэк: URI без document id нормализуется как раньше ---

    @Test
    fun mediaStoreAndNetworkUris_fallBackToNormalizedUri() {
        val media = "content://media/external/audio/media/42"
        assertEquals("uri:$media", TrackIdentity.keyOf(media))

        val https = "https://example.com/audio/track.mp3"
        assertEquals("uri:$https", TrackIdentity.keyOf(https))

        // Схема нормализуется к нижнему регистру (TrackUriNormalizer).
        assertEquals(
            "uri:content://media/external/audio/media/42",
            TrackIdentity.keyOf("CONTENT://media/external/audio/media/42")
        )
    }

    @Test
    fun malformedUri_doesNotThrowAndUsesUriFallback() {
        assertEquals("uri:", TrackIdentity.keyOf(""))
        assertEquals("uri:content:", TrackIdentity.keyOf("content:"))
        assertEquals("uri:content://authority", TrackIdentity.keyOf("content://authority"))
        assertEquals("uri:file:///storage/a.mp3", TrackIdentity.keyOf("file:///storage/a.mp3"))
    }

    @Test
    fun documentIdFromSegments_handlesBothForms() {
        assertEquals(
            "primary:Music/a.mp3",
            TrackIdentity.documentIdFromSegments(listOf("document", "primary:Music/a.mp3"))
        )
        assertEquals(
            "primary:Music/a.mp3",
            TrackIdentity.documentIdFromSegments(
                listOf("tree", "primary:Music", "document", "primary:Music/a.mp3")
            )
        )
        assertNull(TrackIdentity.documentIdFromSegments(emptyList()))
        assertNull(TrackIdentity.documentIdFromSegments(listOf("tree", "primary:Music")))
        assertNull(TrackIdentity.documentIdFromSegments(listOf("document", " ")))
    }

    // --- 3. Проверка «уже есть»: БД + дубли внутри пачки ---

    @Test
    fun keysOf_existingRows_usesCanonicalKey() {
        val keys = TrackIdentity.keysOf(
            listOf(track(folderUri), track("https://example.com/a.mp3"))
        )
        assertEquals(
            setOf(
                "doc:$authority:primary:Music/track.mp3",
                "uri:https://example.com/a.mp3"
            ),
            keys
        )
    }

    @Test
    fun selectNew_skipsTrackAlreadyInDatabase() {
        val existing = TrackIdentity.keysOf(listOf(track(folderUri)))
        val result = TrackIdentity.selectNew(listOf(track(singleFileUri)), existing)
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectNew_skipsDuplicateInsideSameBatch() {
        val batch = listOf(track(singleFileUri), track(folderUri))
        val result = TrackIdentity.selectNew(candidates = batch, existingKeys = emptySet())
        assertEquals(1, result.size)
        assertEquals(singleFileUri, result[0].uri)
    }

    @Test
    fun selectNew_keepsOrderAndDifferentTracks() {
        val other = "content://$authority/document/primary%3AMusic%2Fother.mp3"
        val batch = listOf(track(singleFileUri), track(other), track(folderUri))
        val result = TrackIdentity.selectNew(
            candidates = batch,
            existingKeys = TrackIdentity.keysOf(listOf(track(other)))
        )
        assertEquals(listOf(singleFileUri), result.map { it.uri })
    }

    @Test
    fun selectNew_emptyBatch_returnsEmpty() {
        assertTrue(TrackIdentity.selectNew(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun selectNew_supportsInjectedKeySelector() {
        val batch = listOf(track("a", "Первый"), track("b", "Второй"), track("c", "Первый"))
        val result = TrackIdentity.selectNew(
            candidates = batch,
            existingKeys = setOf("Второй"),
            keySelector = { it.title }
        )
        assertEquals(listOf("a"), result.map { it.uri })
    }
}
