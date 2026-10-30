package com.personal.vibratuner.audio.eq

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/**
 * Реальная реализация [AudioEffectsFactory] поверх `android.media.audiofx.*`.
 * Исключения конструкторов (не поддерживается, превышен лимит экземпляров,
 * неверное состояние) НЕ перехватываются здесь — их ловит [EqualizerEngine].
 */
class AndroidAudioEffectsFactory : AudioEffectsFactory {

    override fun createEqualizer(audioSessionId: Int): EqualizerEffect =
        AndroidEqualizerEffect(Equalizer(/* priority = */ 0, audioSessionId))

    override fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancerEffect =
        AndroidLoudnessEnhancerEffect(LoudnessEnhancer(audioSessionId))
}

private class AndroidEqualizerEffect(private val equalizer: Equalizer) : EqualizerEffect {
    override val numberOfBands: Int get() = equalizer.numberOfBands.toInt()

    override fun centerFreqMilliHz(band: Int): Int = equalizer.getCenterFreq(band.toShort())

    override fun bandLevelRangeMillibels(): IntRange {
        val range = equalizer.bandLevelRange
        return range[0].toInt()..range[1].toInt()
    }

    override fun setBandLevel(band: Int, levelMillibels: Int) {
        equalizer.setBandLevel(band.toShort(), levelMillibels.toShort())
    }

    override fun setEnabled(enabled: Boolean) {
        equalizer.setEnabled(enabled)
    }

    override fun release() {
        equalizer.release()
    }
}

private class AndroidLoudnessEnhancerEffect(
    private val loudness: LoudnessEnhancer
) : LoudnessEnhancerEffect {
    override fun setTargetGain(targetGainMillibels: Int) {
        loudness.setTargetGain(targetGainMillibels)
    }

    override fun setEnabled(enabled: Boolean) {
        loudness.setEnabled(enabled)
    }

    override fun release() {
        loudness.release()
    }
}
