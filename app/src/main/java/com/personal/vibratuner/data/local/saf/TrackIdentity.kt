package com.personal.vibratuner.data.local.saf

import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackUriNormalizer
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Канонический ключ идентичности ФИЗИЧЕСКОГО файла трека и ЕДИНАЯ проверка
 * «такой трек уже есть» для импорта (часть 2 фидбека: дубли при импорте).
 *
 * Причина дублей: один и тот же файл приходит разными URI —
 * - «Выбрать файлы» (`OpenMultipleDocuments`) даёт документ-URI
 *   `content://…externalstorage.documents/document/primary%3AMusic%2Fa.mp3`;
 * - «Выбрать папку целиком» (`OpenDocumentTree` + `DocumentFile`) даёт
 *   tree-URI `content://…externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.mp3`.
 * Строки URI разные, уникальный индекс `tracks(uri)` их не ловит, поэтому
 * сравниваются не строки URI, а общий для обеих форм document id
 * (`primary:Music/a.mp3`), полученный так же, как это делает
 * `DocumentsContract.getDocumentId`: сегменты `document/{id}` и
 * `tree/{treeId}/document/{id}`. Percent-кодирование раскрывается, потому что
 * document id у обеих форм один и тот же.
 *
 * Фолбэк: URI без document id (MediaStore, file://, https-треки каталога) —
 * нормализованная строка URI, как и раньше ([TrackUriNormalizer]).
 *
 * ВАЖНО: ключ НЕ хранится в БД и схеме Room не нужен: он заново выводится из
 * уже существующего `tracks.uri`, поэтому миграция не требуется.
 */
object TrackIdentity {

    private const val SCHEME_SEPARATOR = "://"
    private const val CONTENT_SCHEME = "content"
    private const val DOCUMENT_PATH = "document"
    private const val TREE_PATH = "tree"
    private const val DOCUMENT_KEY_PREFIX = "doc:"
    private const val URI_KEY_PREFIX = "uri:"

    /**
     * Канонический ключ трека: `doc:{authority}:{documentId}` для SAF-документов,
     * иначе `uri:{нормализованный URI}`.
     */
    fun keyOf(uri: String): String {
        val normalized = TrackUriNormalizer.normalize(uri)
        return documentKeyOf(normalized) ?: (URI_KEY_PREFIX + normalized)
    }

    /** Ключи уже существующих строк `tracks` — вход общей проверки «уже есть». */
    fun keysOf(tracks: List<TrackEntity>): Set<String> =
        tracks.mapTo(LinkedHashSet(tracks.size)) { keyOf(it.uri) }

    /**
     * Оставляет только треки, которых ещё НЕТ ни в БД ([existingKeys]), ни выше
     * в этой же пачке. Порядок пачки сохраняется. Чистая функция: ключ
     * подставляется параметром, поэтому поведение покрыто JVM-тестом.
     */
    fun selectNew(
        candidates: List<TrackEntity>,
        existingKeys: Set<String>,
        keySelector: (TrackEntity) -> String = { keyOf(it.uri) }
    ): List<TrackEntity> {
        if (candidates.isEmpty()) return emptyList()
        val seen = HashSet(existingKeys)
        val result = ArrayList<TrackEntity>(candidates.size)
        for (candidate in candidates) {
            if (seen.add(keySelector(candidate))) {
                result.add(candidate)
            }
        }
        return result
    }

    /**
     * `doc:{authority}:{documentId}` для content-URI документа/дерева, иначе null.
     * Чистая функция от строки URI — покрыта JVM-тестом (без Android API).
     */
    fun documentKeyOf(uri: String): String? {
        val schemeEnd = uri.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd <= 0) return null
        val scheme = uri.substring(0, schemeEnd)
        if (!scheme.equals(CONTENT_SCHEME, ignoreCase = true)) return null

        val afterScheme = uri.substring(schemeEnd + SCHEME_SEPARATOR.length)
        val authorityEnd = afterScheme.indexOf('/')
        if (authorityEnd <= 0) return null
        val authority = afterScheme.substring(0, authorityEnd).lowercase(Locale.ROOT)

        val path = afterScheme.substring(authorityEnd + 1)
            .substringBefore('?')
            .substringBefore('#')
        if (path.isEmpty()) return null

        val segments = path.split('/').map { decodePathSegment(it) }
        val documentId = documentIdFromSegments(segments) ?: return null
        return DOCUMENT_KEY_PREFIX + authority + ":" + documentId
    }

    /**
     * document id по сегментам пути URI — те же правила, что у
     * `DocumentsContract.getDocumentId`: `document/{id}` и `tree/{treeId}/document/{id}`.
     */
    fun documentIdFromSegments(segments: List<String>): String? {
        if (segments.size >= 4 &&
            segments[0] == TREE_PATH &&
            segments[2] == DOCUMENT_PATH &&
            segments[3].isNotBlank()
        ) {
            return segments[3]
        }
        if (segments.size >= 2 &&
            segments[0] == DOCUMENT_PATH &&
            segments[1].isNotBlank()
        ) {
            return segments[1]
        }
        return null
    }

    /** Раскрывает percent-кодирование сегмента (%XX) как это делает `Uri`. */
    private fun decodePathSegment(segment: String): String {
        if (segment.indexOf('%') < 0) return segment
        val bytes = ByteArrayOutputStream(segment.length)
        var index = 0
        while (index < segment.length) {
            val decoded = if (segment[index] == '%') hexByte(segment, index) else null
            if (decoded != null) {
                bytes.write(decoded.toInt())
                index += 3
            } else {
                val chunk = segment[index].toString().toByteArray(Charsets.UTF_8)
                bytes.write(chunk, 0, chunk.size)
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hexByte(source: String, percentIndex: Int): Byte? {
        if (percentIndex + 2 >= source.length) return null
        val high = source[percentIndex + 1].digitToIntOrNull(16) ?: return null
        val low = source[percentIndex + 2].digitToIntOrNull(16) ?: return null
        return ((high shl 4) or low).toByte()
    }
}
