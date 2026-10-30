package com.personal.vibratuner.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Реализация [EqualizerSettingsStore] поверх существующего `DataStore<Preferences>`
 * приложения (`vibra_tuner_preferences`), того же механизма, что и остальные
 * настройки. Значения санитизируются и при чтении, и при записи.
 */
class DataStoreEqualizerSettingsStore(context: Context) : EqualizerSettingsStore {

    private val dataStore = context.dataStore

    override val settings: Flow<EqualizerSettings> = dataStore.data.map { preferences ->
        EqualizerSettings.sanitize(
            enabled = preferences[KEY_ENABLED] ?: false,
            bassPercent = preferences[KEY_BASS_PERCENT]
                ?: EqualizerSettings.TONE_NEUTRAL_PERCENT,
            treblePercent = preferences[KEY_TREBLE_PERCENT]
                ?: EqualizerSettings.TONE_NEUTRAL_PERCENT,
            overboostPercent = preferences[KEY_OVERBOOST_PERCENT]
                ?: EqualizerSettings.OVERBOOST_MIN_PERCENT,
            speed = preferences[KEY_SPEED] ?: EqualizerSettings.DEFAULT_SPEED,
            boostSystemVolume = preferences[KEY_BOOST_SYSTEM_VOLUME] ?: false
        )
    }

    override suspend fun save(settings: EqualizerSettings) {
        val s = EqualizerSettings.sanitize(settings)
        dataStore.edit { preferences ->
            preferences[KEY_ENABLED] = s.enabled
            preferences[KEY_BASS_PERCENT] = s.bassPercent
            preferences[KEY_TREBLE_PERCENT] = s.treblePercent
            preferences[KEY_OVERBOOST_PERCENT] = s.overboostPercent
            preferences[KEY_SPEED] = s.speed
            preferences[KEY_BOOST_SYSTEM_VOLUME] = s.boostSystemVolume
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val KEY_BASS_PERCENT = intPreferencesKey("equalizer_bass_percent")
        val KEY_TREBLE_PERCENT = intPreferencesKey("equalizer_treble_percent")
        val KEY_OVERBOOST_PERCENT = intPreferencesKey("equalizer_overboost_percent")
        val KEY_SPEED = floatPreferencesKey("equalizer_speed")
        val KEY_BOOST_SYSTEM_VOLUME = booleanPreferencesKey("equalizer_boost_system_volume")
    }
}
