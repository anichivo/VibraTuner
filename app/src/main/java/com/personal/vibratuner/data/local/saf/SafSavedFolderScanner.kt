package com.personal.vibratuner.data.local.saf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.personal.vibratuner.data.local.db.TrackEntity

/**
 * SAF-реализация [SavedFolderScanner] для автосканирования сохранённой папки.
 *
 * Доступ проверяется по `ContentResolver.persistedUriPermissions` — это
 * единственный надёжный признак, что URI пережил перезапуск процесса. Само
 * сканирование повторно persistable-грант не забирает ([SafTrackImporter]).
 */
class SafSavedFolderScanner(private val context: Context) : SavedFolderScanner {

    override fun hasReadPermission(treeUri: String): Boolean {
        val uri = parse(treeUri) ?: return false
        return SafTrackImporter.hasPersistedReadPermission(context, uri)
    }

    override fun scan(treeUri: String): List<TrackEntity> {
        val uri = parse(treeUri)
            ?: throw IllegalArgumentException("Некорректный tree-URI папки импорта")
        return SafTrackImporter.scanFolderRecursively(context, uri, takePermission = false)
    }

    private fun parse(treeUri: String): Uri? = try {
        Uri.parse(treeUri)
    } catch (e: Exception) {
        Log.w(TAG, "Некорректный tree-URI папки импорта: $treeUri", e)
        null
    }

    private companion object {
        const val TAG = "SafFolderScanner"
    }
}
