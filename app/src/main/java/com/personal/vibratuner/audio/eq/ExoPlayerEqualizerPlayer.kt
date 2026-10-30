package com.personal.vibratuner.audio.eq

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Адаптер Media3 [ExoPlayer] -> [EqualizerPlayer]. Только то, что нужно движку
 * эквалайзера: audioSessionId, скорость и колбэк смены audioSessionId.
 * НЕ создаёт второй путь воспроизведения и не трогает координатор.
 */
class ExoPlayerEqualizerPlayer(private val player: ExoPlayer) : EqualizerPlayer {

    private val sessionListeners = mutableSetOf<AudioSessionIdListener>()

    private val media3Listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            sessionListeners.toList().forEach { it.onAudioSessionIdChanged(audioSessionId) }
        }
    }

    override val audioSessionId: Int get() = player.audioSessionId

    override fun setPlaybackParameters(speed: Float, pitch: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed, pitch))
    }

    override fun addAudioSessionIdListener(listener: AudioSessionIdListener) {
        val wasEmpty = sessionListeners.isEmpty()
        sessionListeners.add(listener)
        if (wasEmpty && sessionListeners.isNotEmpty()) {
            player.addListener(media3Listener)
        }
    }

    override fun removeAudioSessionIdListener(listener: AudioSessionIdListener) {
        sessionListeners.remove(listener)
        if (sessionListeners.isEmpty()) {
            player.removeListener(media3Listener)
        }
    }
}
