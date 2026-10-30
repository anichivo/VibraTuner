package com.personal.vibratuner.data.local.saf

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackUriNormalizer

object SafTrackImporter {
    private const val TAG = "SafTrackImporter"

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus"
    )

    /**
     * Забирает persistable-доступ на чтение URI (папку или файл) и проверяет,
     * что он действительно сохранён. Возвращает `false`, если система отказала:
     * тогда URI запоминать для автосканирования нельзя (после перезапуска
     * доступа уже не будет).
     */
    fun takePersistablePermission(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            hasPersistedReadPermission(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось сохранить persistable URI разрешение для $uri", e)
            false
        }
    }

    /** Сохранён ли у приложения persistable-доступ на чтение [uri]. */
    fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось проверить persistable URI разрешения: ${e.message}")
        false
    }

    fun extractTrackFromUri(context: Context, uri: Uri): TrackEntity? {
        return try {
            val fileName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: context.getString(com.personal.vibratuner.R.string.track_unknown_title)
            var title = fileName.substringBeforeLast('.')
            var artist = ""
            var durationMs = 0L

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

                if (!metaTitle.isNullOrBlank()) {
                    title = metaTitle
                }
                if (!metaArtist.isNullOrBlank()) {
                    artist = metaArtist
                }
                if (!metaDuration.isNullOrBlank()) {
                    durationMs = metaDuration.toLongOrNull() ?: 0L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось извлечь метаданные через MediaMetadataRetriever для $uri, используется имя файла", e)
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Игнорируем
                }
            }

            TrackEntity(
                uri = TrackUriNormalizer.normalize(uri.toString()),
                title = title,
                artist = artist,
                durationMs = durationMs,
                dateAdded = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения аудиофайла $uri: ${e.message}", e)
            null
        }
    }

    /**
     * Рекурсивное сканирование дерева SAF.
     *
     * [takePermission] = false используется автосканированием сохранённой папки:
     * доступ уже проверен по `persistedUriPermissions`, а повторно «забирать»
     * грант при каждом возврате в приложение не нужно.
     */
    fun scanFolderRecursively(
        context: Context,
        treeUri: Uri,
        takePermission: Boolean = true
    ): List<TrackEntity> {
        val results = mutableListOf<TrackEntity>()
        if (takePermission) {
            takePersistablePermission(context, treeUri)
        }

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return results
        traverseDirectory(context, rootDoc, results)
        return results
    }

    private fun traverseDirectory(context: Context, directory: DocumentFile, results: MutableList<TrackEntity>) {
        try {
            val files = directory.listFiles()
            for (file in files) {
                if (file.isDirectory) {
                    traverseDirectory(context, file, results)
                } else if (file.isFile) {
                    val name = file.name ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val type = file.type
                    val isAudio = (type != null && type.startsWith("audio/")) || SUPPORTED_EXTENSIONS.contains(ext)
                    if (isAudio) {
                        val track = extractTrackFromUri(context, file.uri)
                        if (track != null) {
                            results.add(track)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сканирования директории ${directory.uri}: ${e.message}", e)
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
