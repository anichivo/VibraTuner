package com.personal.vibratuner.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.choppy.ChoppyValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication

    private val preferences = app.preferencesDataStore

    /**
     * Единственный источник состояния проверки обновлений (часть 1). UI «Настроек»
     * (часть 2) читает отсюда переключатель и данные последнего релиза.
     */
    private val updateRepository = app.updateRepository

    val autoUpdateCheckEnabled: StateFlow<Boolean> = updateRepository.autoUpdateCheckEnabled
    val updateAvailable: StateFlow<Boolean> = updateRepository.updateAvailable
    val latestVersionName: StateFlow<String?> = updateRepository.latestVersionName
    val latestReleaseUrl: StateFlow<String> = updateRepository.latestReleaseUrl

    private val _volumeRampDurationSec = MutableStateFlow(10)
    val volumeRampDurationSec: StateFlow<Int> = _volumeRampDurationSec.asStateFlow()

    private val _choppyEnabled = MutableStateFlow(false)
    val choppyEnabled: StateFlow<Boolean> = _choppyEnabled.asStateFlow()

    private val _choppyPlayMinutes = MutableStateFlow(6)
    val choppyPlayMinutes: StateFlow<Int> = _choppyPlayMinutes.asStateFlow()

    private val _choppyPauseMinutes = MutableStateFlow(1)
    val choppyPauseMinutes: StateFlow<Int> = _choppyPauseMinutes.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.volumeRampDurationSecFlow.collectLatest { sec ->
                _volumeRampDurationSec.value = sec
            }
        }
        viewModelScope.launch {
            preferences.choppyEnabledFlow.collectLatest { enabled ->
                _choppyEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            preferences.choppyPlayMinutesFlow.collectLatest { playMin ->
                _choppyPlayMinutes.value = playMin
            }
        }
        viewModelScope.launch {
            preferences.choppyPauseMinutesFlow.collectLatest { pauseMin ->
                _choppyPauseMinutes.value = pauseMin
            }
        }
    }

    fun onVolumeRampDurationChanged(seconds: Int) {
        val clamped = seconds.coerceIn(0, 120)
        _volumeRampDurationSec.value = clamped
        viewModelScope.launch {
            preferences.setVolumeRampDurationSec(clamped)
        }
    }

    fun onChoppyEnabledChanged(enabled: Boolean) {
        _choppyEnabled.value = enabled
        viewModelScope.launch {
            preferences.setChoppyEnabled(enabled)
        }
    }

    fun onChoppyIntervalsChanged(playMin: Int, pauseMin: Int): Boolean {
        if (!ChoppyValidator.validate(playMin, pauseMin)) {
            return false
        }
        _choppyPlayMinutes.value = playMin
        _choppyPauseMinutes.value = pauseMin
        viewModelScope.launch {
            preferences.setChoppyIntervals(playMin, pauseMin)
        }
        return true
    }

    /**
     * Переключатель «Проверять обновления» (часть 2 вызывает его из Switch).
     * Включение сразу запускает одну проверку в фоне; выключение снимает
     * флаг обновления, сохранённые данные не удаляются.
     */
    fun onAutoUpdateCheckEnabledChanged(enabled: Boolean) {
        updateRepository.setAutoUpdateCheckEnabled(enabled)
    }
}
