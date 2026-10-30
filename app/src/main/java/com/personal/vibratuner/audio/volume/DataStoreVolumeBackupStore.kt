package com.personal.vibratuner.audio.volume

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.personal.vibratuner.data.local.datastore.dataStore
import kotlinx.coroutines.flow.first

/**
 * Резерв системной громкости в СУЩЕСТВУЮЩЕМ `DataStore<Preferences>` приложения
 * (`vibra_tuner_preferences`), том же, где остальные настройки. Новых файлов,
 * БД и библиотек нет.
 *
 * Ключи: `system_volume_backup_<stream>_original` и
 * `system_volume_backup_<stream>_target`. Пишутся и читаются только
 * SystemVolumeController.
 */
class DataStoreVolumeBackupStore(context: Context) : VolumeBackupStore {

    private val dataStore = context.dataStore

    override suspend fun load(): Map<Int, VolumeBackupEntry> {
        val preferences = dataStore.data.first()
        val originals = mutableMapOf<Int, Int>()
        val targets = mutableMapOf<Int, Int>()
        preferences.asMap().forEach { (key, value) ->
            val name = key.name
            if (!name.startsWith(KEY_PREFIX)) return@forEach
            val stream = name.removePrefix(KEY_PREFIX)
                .substringBeforeLast('_', "")
                .toIntOrNull()
                ?: return@forEach
            val number = value as? Int ?: return@forEach
            if (name.endsWith(SUFFIX_TARGET)) {
                targets[stream] = number
            } else {
                originals[stream] = number
            }
        }
        return buildMap {
            originals.forEach { (stream, original) ->
                val target = targets[stream] ?: return@forEach
                if (stream < 0 || original < 0 || target <= 0) return@forEach
                put(stream, VolumeBackupEntry(original = original, target = target))
            }
        }
    }

    override suspend fun save(entries: Map<Int, VolumeBackupEntry>) {
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(KEY_PREFIX) }
                .forEach { preferences.remove(it) }
            entries.forEach { (stream, entry) ->
                if (stream < 0 || entry.original < 0 || entry.target <= 0) return@forEach
                preferences[keyOriginal(stream)] = entry.original
                preferences[keyTarget(stream)] = entry.target
            }
        }
    }

    private companion object {
        const val KEY_PREFIX = "system_volume_backup_"
        const val SUFFIX_ORIGINAL = "_original"
        const val SUFFIX_TARGET = "_target"

        fun keyOriginal(stream: Int) = intPreferencesKey("$KEY_PREFIX${stream}$SUFFIX_ORIGINAL")

        fun keyTarget(stream: Int) = intPreferencesKey("$KEY_PREFIX${stream}$SUFFIX_TARGET")
    }
}
