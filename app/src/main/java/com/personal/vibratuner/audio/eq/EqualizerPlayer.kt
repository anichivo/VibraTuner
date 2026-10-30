package com.personal.vibratuner.audio.eq

/**
 * Минимальная абстракция над плеером, нужная [EqualizerEngine] и его JVM-тестам.
 * Реальная реализация для Media3 — [ExoPlayerEqualizerPlayer].
 */
interface EqualizerPlayer {
    /** Текущий `audioSessionId` плеера; 0 — ещё не назначен (ждать колбэк). */
    val audioSessionId: Int

    /** Применяет скорость/высоту тона; pitch равен speed (ускорение «как у плёнки»). */
    fun setPlaybackParameters(speed: Float, pitch: Float)

    fun addAudioSessionIdListener(listener: AudioSessionIdListener)
    fun removeAudioSessionIdListener(listener: AudioSessionIdListener)
}

fun interface AudioSessionIdListener {
    fun onAudioSessionIdChanged(audioSessionId: Int)
}
