package com.personal.vibratuner.data.local.datastore

import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlinx.coroutines.flow.Flow

/**
 * Хранилище настроек эквалайзера. Тонкая абстракция над DataStore, чтобы
 * репозиторий и его JVM-тесты работали с фейковым хранилищем (без Android).
 */
interface EqualizerSettingsStore {
    val settings: Flow<EqualizerSettings>
    suspend fun save(settings: EqualizerSettings)
}
