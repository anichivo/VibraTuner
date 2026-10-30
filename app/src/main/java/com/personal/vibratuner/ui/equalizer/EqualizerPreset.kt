package com.personal.vibratuner.ui.equalizer

import androidx.annotation.StringRes
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.eq.EqualizerSettings

/**
 * Быстрые пресеты эквалайзера. Пресет меняет ТОЛЬКО bass/treble/overboost;
 * скорость и переключатели (включение, системная громкость) не трогает.
 *
 * Чистая логика без Android: сопоставление с текущими настройками и применение
 * покрыты обычными JVM-тестами (`EqualizerPresetTest`).
 */
enum class EqualizerPreset(
    @StringRes val nameRes: Int,
    val bassPercent: Int,
    val treblePercent: Int,
    val overboostPercent: Int
) {
    NEUTRAL(R.string.equalizer_preset_neutral, bassPercent = 0, treblePercent = 0, overboostPercent = 0),
    BASS(R.string.equalizer_preset_bass, bassPercent = 70, treblePercent = 0, overboostPercent = 0),
    TREBLE(R.string.equalizer_preset_treble, bassPercent = 0, treblePercent = 70, overboostPercent = 0),
    LOUD(R.string.equalizer_preset_loud, bassPercent = 0, treblePercent = 0, overboostPercent = 200);

    /**
     * Чип показан выбранным ТОЛЬКО при точном совпадении всех трёх значений
     * пресета. Скорость и переключатели на совпадение не влияют.
     */
    fun matches(settings: EqualizerSettings): Boolean =
        settings.bassPercent == bassPercent &&
            settings.treblePercent == treblePercent &&
            settings.overboostPercent == overboostPercent

    /** Копия настроек с тремя значениями пресета; остальные поля не меняются. */
    fun applyTo(settings: EqualizerSettings): EqualizerSettings = settings.copy(
        bassPercent = bassPercent,
        treblePercent = treblePercent,
        overboostPercent = overboostPercent
    )

    companion object {
        /** Единственный пресет, точно совпадающий с настройками, либо null. */
        fun of(settings: EqualizerSettings): EqualizerPreset? =
            entries.firstOrNull { it.matches(settings) }
    }
}
