package com.personal.vibratuner.ui.equalizer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.eq.EqualizerBandRange
import com.personal.vibratuner.audio.eq.EqualizerMath
import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel экрана эквалайзера. Тонкая Android-обёртка над чистым
 * [EqualizerEditor]: читает единственный репозиторий и передаёт изменения в
 * него. Никакой аудиологики здесь нет — движок реагирует на Flow репозитория.
 */
class EqualizerViewModel(application: Application) : AndroidViewModel(application) {

    private val editor = EqualizerEditor(
        repository = (application as VibraTunerApplication).equalizerRepository,
        scope = viewModelScope
    )

    val settings: StateFlow<EqualizerSettings> = editor.settings

    /**
     * Реальный диапазон полос устройства для подписей в дБ. Публикуется
     * движком из `Equalizer.getBandLevelRange()`; до первого воспроизведения
     * действует типичный ±15 дБ. Второго источника настроек здесь нет.
     */
    val bandRange: StateFlow<IntRange> = EqualizerBandRange.millibels

    fun onEnabledChanged(enabled: Boolean) = editor.setEnabled(enabled)

    fun onBoostSystemVolumeChanged(boost: Boolean) = editor.setBoostSystemVolume(boost)

    fun onPresetSelected(preset: EqualizerPreset) = editor.selectPreset(preset)

    fun onBassChanged(value: Float) = editor.onSliderChanged(EqualizerControl.BASS, value)

    fun onTrebleChanged(value: Float) = editor.onSliderChanged(EqualizerControl.TREBLE, value)

    fun onOverboostChanged(value: Float) =
        editor.onSliderChanged(EqualizerControl.OVERBOOST, value)

    /**
     * Слайдер скорости отдаёт позицию 0..1; логарифмическая шкала и снэп к 1x
     * берутся из чистой математики части 1 (`speedFromSlider`).
     */
    fun onSpeedSliderChanged(position: Float) =
        editor.onSliderChanged(EqualizerControl.SPEED, EqualizerMath.speedFromSlider(position))

    fun onSliderChangeFinished() = editor.onSliderChangeFinished()

    fun onReset(control: EqualizerControl) = editor.reset(control)
}
