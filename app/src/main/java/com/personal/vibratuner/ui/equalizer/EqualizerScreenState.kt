package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerSettings
import java.util.Locale

/**
 * Проекция настроек эквалайзера в состояние экрана: выбранный пресет, подписи
 * значений и доступность регуляторов. Ничего не хранит сверх [settings] —
 * единственным источником истины остаётся репозиторий. Чистая функция,
 * покрыта JVM-тестами.
 */
data class EqualizerScreenState(
    val settings: EqualizerSettings,
    val selectedPreset: EqualizerPreset?,
    val bassLabel: String,
    val trebleLabel: String,
    val overboostLabel: String,
    val speedLabel: String
) {
    /**
     * Выключенный эквалайзер оставляет карточки, пресеты и строку системной
     * громкости видимыми, но все регуляторы становятся disabled.
     */
    val controlsEnabled: Boolean get() = settings.enabled
}

/**
 * Единственная точка сборки [EqualizerScreenState] из настроек. [bandRange]
 * — реальный диапазон полос устройства (мБ), [dbUnit] — единица измерения из
 * `strings.xml`, [locale] передаётся параметром, чтобы тесты проверяли
 * десятичную запятую без зависимости от локали JVM.
 */
fun equalizerScreenState(
    settings: EqualizerSettings,
    bandRange: IntRange,
    dbUnit: String,
    locale: Locale = Locale.getDefault()
): EqualizerScreenState = EqualizerScreenState(
    settings = settings,
    selectedPreset = EqualizerPreset.of(settings),
    bassLabel = EqualizerFormat.tone(settings.bassPercent, bandRange, dbUnit),
    trebleLabel = EqualizerFormat.tone(settings.treblePercent, bandRange, dbUnit),
    overboostLabel = EqualizerFormat.overboost(settings.overboostPercent),
    speedLabel = EqualizerFormat.speed(settings.speed, locale)
)
