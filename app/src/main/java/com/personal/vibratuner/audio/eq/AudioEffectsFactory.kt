package com.personal.vibratuner.audio.eq

/**
 * Тонкая фабрика Android-эффектов (`android.media.audiofx.*`). Обёртки нужны,
 * чтобы [EqualizerEngine] и его JVM-тесты работали с фейками без Robolectric.
 *
 * Каждый эффект создаётся под конкретный `audioSessionId` плеера (НИКОГДА
 * session 0 / глобальный микшер). Методы создания намеренно НЕ возвращают null:
 * при невозможности создать эффект (не поддерживается, превышен лимит
 * экземпляров устройства, ошибка состояния) они выбрасывают исключение —
 * движок ловит его и пропускает эффект, продолжая с остальными.
 */
interface AudioEffectsFactory {
    fun createEqualizer(audioSessionId: Int): EqualizerEffect
    fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancerEffect
}

/** Обёртка над `android.media.audiofx.Equalizer`. */
interface EqualizerEffect {
    val numberOfBands: Int

    /** Центральная частота полосы в миллигерцах. */
    fun centerFreqMilliHz(band: Int): Int

    /** Диапазон уровней полос в миллибелах (min..max). */
    fun bandLevelRangeMillibels(): IntRange

    fun setBandLevel(band: Int, levelMillibels: Int)
    fun setEnabled(enabled: Boolean)
    fun release()
}

/** Обёртка над `android.media.audiofx.LoudnessEnhancer`. */
interface LoudnessEnhancerEffect {
    fun setTargetGain(targetGainMillibels: Int)
    fun setEnabled(enabled: Boolean)
    fun release()
}
