package com.personal.vibratuner.data.local.datastore

import com.personal.vibratuner.data.local.saf.SavedFolderStore
import kotlinx.coroutines.flow.first

/**
 * [SavedFolderStore] поверх существующего DataStore настроек: отдельного
 * хранилища/файла для папки импорта НЕ заводится.
 */
class DataStoreSavedFolderStore(
    private val preferences: MusicPreferencesDataStore
) : SavedFolderStore {

    override suspend fun savedFolder(): String? = preferences.importFolderUriFlow.first()

    override suspend fun saveFolder(uri: String?) {
        preferences.setImportFolderUri(uri)
    }
}
