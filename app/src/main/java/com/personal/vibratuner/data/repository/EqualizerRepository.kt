package com.personal.vibratuner.data.repository

import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.data.local.datastore.EqualizerSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Единая точка чтения/записи настроек эквалайзера. Значения санитизируются и на
 * чтении, и на записи; каждое изменение сохраняется сразу (незамедлительно).
 */
class EqualizerRepository(private val store: EqualizerSettingsStore) {

    val settings: Flow<EqualizerSettings> = store.settings.map { EqualizerSettings.sanitize(it) }

    suspend fun setEnabled(enabled: Boolean) = save { it.copy(enabled = enabled) }

    suspend fun setBassPercent(percent: Int) = save { it.copy(bassPercent = percent) }

    suspend fun setTreblePercent(percent: Int) = save { it.copy(treblePercent = percent) }

    suspend fun setOverboostPercent(percent: Int) = save { it.copy(overboostPercent = percent) }

    suspend fun setSpeed(speed: Float) = save { it.copy(speed = speed) }

    suspend fun setBoostSystemVolume(boost: Boolean) = save { it.copy(boostSystemVolume = boost) }

    private suspend fun save(transform: (EqualizerSettings) -> EqualizerSettings) {
        val current = settings.first()
        store.save(EqualizerSettings.sanitize(transform(current)))
    }
}
