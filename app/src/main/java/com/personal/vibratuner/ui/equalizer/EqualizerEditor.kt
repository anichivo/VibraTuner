package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.data.repository.EqualizerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/** Регуляторы эквалайзера, которые меняются протяжкой слайдера. */
enum class EqualizerControl {
    BASS,
    TREBLE,
    OVERBOOST,
    SPEED
}

/**
 * Состояние экрана эквалайзера и отложенная запись настроек.
 *
 * Чистый Kotlin (без Android), поэтому вся логика проверяется JVM-тестами
 * (`EqualizerEditorTest`). Экран показывает [settings]; единственный источник
 * истины — `EqualizerRepository.settings`:
 *  - пока пользователь тянет слайдер, показанное значение — локальная
 *    «незаписанная» дельта, наложенная на последние настройки из репозитория;
 *  - запись выполняется не чаще одного раза в [debounceMillis] мс (conflated:
 *    пока таймер идёт, новые значения только заменяют предыдущее), поэтому
 *    звук обновляется прямо во время протяжки;
 *  - `onSliderChangeFinished` (конец жеста) записывает последнее значение
 *    немедленно;
 *  - когда репозиторий присылает подтверждённое значение, локальная дельта
 *    снимается, поэтому показанное состояние не может разойтись с хранилищем.
 *
 * Все методы и сбор Flow вызываются в одном (главном) потоке, как и остальные
 * ViewModel-ы проекта. Записи сериализованы [writeMutex], чтобы параллельные
 * действия (переключатель во время протяжки) не теряли данные при
 * read-modify-write в репозитории.
 */
class EqualizerEditor(
    private val repository: EqualizerRepository,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_WRITE_MS
) {
    private val _settings = MutableStateFlow(EqualizerSettings.DEFAULT)

    /** Показанные настройки: репозиторий + ещё не подтверждённая локальная дельта. */
    val settings: StateFlow<EqualizerSettings> = _settings.asStateFlow()

    /** Последние настройки, реально пришедшие из репозитория. */
    private var persisted = EqualizerSettings.DEFAULT

    private var pendingEnabled: Boolean? = null
    private var pendingBoostSystemVolume: Boolean? = null
    private val pendingSliders = mutableMapOf<EqualizerControl, Float>()

    private val writeMutex = Mutex()
    private var debounceJob: Job? = null
    private var dragControl: EqualizerControl? = null

    init {
        scope.launch {
            repository.settings.collect { value ->
                persisted = value
                if (pendingEnabled == value.enabled) pendingEnabled = null
                if (pendingBoostSystemVolume == value.boostSystemVolume) {
                    pendingBoostSystemVolume = null
                }
                val iterator = pendingSliders.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (confirmedBy(entry.key, entry.value, value)) iterator.remove()
                }
                _settings.value = displaySettings(value)
            }
        }
    }

    /** Включение/выключение эквалайзера сохраняется сразу. */
    fun setEnabled(enabled: Boolean) {
        pendingEnabled = enabled
        publish()
        write { repository.setEnabled(enabled) }
    }

    /** Переключатель «максимальная системная громкость» сохраняется сразу. */
    fun setBoostSystemVolume(boost: Boolean) {
        pendingBoostSystemVolume = boost
        publish()
        write { repository.setBoostSystemVolume(boost) }
    }

    /**
     * Пресет меняет только bass/treble/overboost. Незаписанная протяжка по
     * этим же полям отменяется, чтобы не перезаписать пресет старым значением.
     */
    fun selectPreset(preset: EqualizerPreset) {
        val dragging = dragControl
        if (dragging != null && dragging in TONE_CONTROLS) cancelDebounce()
        pendingSliders[EqualizerControl.BASS] = preset.bassPercent.toFloat()
        pendingSliders[EqualizerControl.TREBLE] = preset.treblePercent.toFloat()
        pendingSliders[EqualizerControl.OVERBOOST] = preset.overboostPercent.toFloat()
        publish()
        write {
            repository.setBassPercent(preset.bassPercent)
            repository.setTreblePercent(preset.treblePercent)
            repository.setOverboostPercent(preset.overboostPercent)
        }
    }

    /**
     * Новое значение слайдера: сразу показывается и планируется отложенная
     * запись. Значение нормализуется (округление процентов, пределы из
     * [EqualizerSettings]).
     */
    fun onSliderChanged(control: EqualizerControl, rawValue: Float) {
        val value = normalize(control, rawValue)
        pendingSliders[control] = value
        dragControl = control
        publish()
        scheduleDebouncedWrite()
    }

    /** Конец жеста: последнее значение записывается немедленно. */
    fun onSliderChangeFinished() {
        val control = dragControl ?: return
        val value = pendingSliders[control]
        cancelDebounce()
        if (value != null) write { writeSlider(control, value) }
    }

    /** Сброс одного регулятора к значению по умолчанию (сразу сохраняется). */
    fun reset(control: EqualizerControl) {
        val value = defaultSliderValue(control)
        if (dragControl == control) cancelDebounce()
        pendingSliders[control] = value
        publish()
        write { writeSlider(control, value) }
    }

    // --- Отложенная запись -------------------------------------------------

    private fun scheduleDebouncedWrite() {
        // Conflated: пока таймер уже идёт, новое значение лишь заменяет
        // предыдущее в pendingSliders; отдельная запись не создаётся.
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch {
            delay(debounceMillis)
            val control = dragControl ?: return@launch
            val value = pendingSliders[control] ?: return@launch
            writeMutex.withLock { writeSlider(control, value) }
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
        dragControl = null
    }

    private fun write(block: suspend () -> Unit) {
        scope.launch { writeMutex.withLock { block() } }
    }

    private suspend fun writeSlider(control: EqualizerControl, value: Float) {
        when (control) {
            EqualizerControl.BASS -> repository.setBassPercent(value.roundToInt())
            EqualizerControl.TREBLE -> repository.setTreblePercent(value.roundToInt())
            EqualizerControl.OVERBOOST -> repository.setOverboostPercent(value.roundToInt())
            EqualizerControl.SPEED -> repository.setSpeed(value)
        }
    }

    // --- Состояние ---------------------------------------------------------

    private fun publish() {
        _settings.value = displaySettings(persisted)
    }

    private fun displaySettings(base: EqualizerSettings): EqualizerSettings {
        var result = base
        pendingEnabled?.let { result = result.copy(enabled = it) }
        pendingBoostSystemVolume?.let { result = result.copy(boostSystemVolume = it) }
        pendingSliders.forEach { (control, value) ->
            result = when (control) {
                EqualizerControl.BASS -> result.copy(
                    bassPercent = value.roundToInt().coerceIn(
                        EqualizerSettings.TONE_MIN_PERCENT,
                        EqualizerSettings.TONE_MAX_PERCENT
                    )
                )

                EqualizerControl.TREBLE -> result.copy(
                    treblePercent = value.roundToInt().coerceIn(
                        EqualizerSettings.TONE_MIN_PERCENT,
                        EqualizerSettings.TONE_MAX_PERCENT
                    )
                )

                EqualizerControl.OVERBOOST -> result.copy(
                    overboostPercent = value.roundToInt().coerceIn(
                        EqualizerSettings.OVERBOOST_MIN_PERCENT,
                        EqualizerSettings.OVERBOOST_MAX_PERCENT
                    )
                )

                EqualizerControl.SPEED -> result.copy(speed = value)
            }
        }
        return result
    }

    private fun normalize(control: EqualizerControl, rawValue: Float): Float = when (control) {
        EqualizerControl.BASS -> rawValue.roundToInt()
            .coerceIn(EqualizerSettings.TONE_MIN_PERCENT, EqualizerSettings.TONE_MAX_PERCENT)
            .toFloat()

        EqualizerControl.TREBLE -> rawValue.roundToInt()
            .coerceIn(
                EqualizerSettings.TONE_MIN_PERCENT,
                EqualizerSettings.TONE_MAX_PERCENT
            )
            .toFloat()

        EqualizerControl.OVERBOOST -> rawValue.roundToInt()
            .coerceIn(
                EqualizerSettings.OVERBOOST_MIN_PERCENT,
                EqualizerSettings.OVERBOOST_MAX_PERCENT
            )
            .toFloat()

        EqualizerControl.SPEED -> when {
            rawValue.isNaN() -> EqualizerSettings.DEFAULT_SPEED
            else -> rawValue.coerceIn(EqualizerSettings.SPEED_MIN, EqualizerSettings.SPEED_MAX)
        }
    }

    private fun confirmedBy(
        control: EqualizerControl,
        pendingValue: Float,
        settings: EqualizerSettings
    ): Boolean = when (control) {
        EqualizerControl.BASS -> pendingValue.roundToInt() == settings.bassPercent
        EqualizerControl.TREBLE -> pendingValue.roundToInt() == settings.treblePercent
        EqualizerControl.OVERBOOST -> pendingValue.roundToInt() == settings.overboostPercent
        EqualizerControl.SPEED -> abs(pendingValue - settings.speed) < SPEED_EPSILON
    }

    private fun defaultSliderValue(control: EqualizerControl): Float = when (control) {
        EqualizerControl.BASS -> EqualizerSettings.TONE_NEUTRAL_PERCENT.toFloat()
        EqualizerControl.TREBLE -> EqualizerSettings.TONE_NEUTRAL_PERCENT.toFloat()
        EqualizerControl.OVERBOOST -> EqualizerSettings.OVERBOOST_MIN_PERCENT.toFloat()
        EqualizerControl.SPEED -> EqualizerSettings.DEFAULT_SPEED
    }

    companion object {
        /** Пауза conflated-записи во время протяжки слайдера. */
        const val DEBOUNCE_WRITE_MS: Long = 100L

        private const val SPEED_EPSILON = 0.0001f

        private val TONE_CONTROLS =
            setOf(EqualizerControl.BASS, EqualizerControl.TREBLE, EqualizerControl.OVERBOOST)
    }
}
