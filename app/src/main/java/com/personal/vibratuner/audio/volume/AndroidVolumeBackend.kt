package com.personal.vibratuner.audio.volume

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Реальная обёртка над AudioManager. ЕДИНСТВЕННОЕ место в приложении, где
 * вызывается `setStreamVolume`: передаём flags = 0, поэтому системный UI
 * громкости не появляется.
 *
 * Все возможные исключения (SecurityException, IllegalArgumentException для
 * неподдерживаемого потока, отказ binder'а) перехватываются в
 * [SystemVolumeController]: падать или задерживать воспроизведение нельзя.
 */
class AndroidVolumeBackend(context: Context) : VolumeBackend {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun getVolume(stream: Int): Int = requireAudioManager().getStreamVolume(stream)

    override fun getMaxVolume(stream: Int): Int = requireAudioManager().getStreamMaxVolume(stream)

    override fun setVolume(stream: Int, index: Int) {
        requireAudioManager().setStreamVolume(stream, index, /* flags = */ 0)
    }

    private fun requireAudioManager(): AudioManager =
        audioManager ?: throw IllegalStateException("AudioManager недоступен")
}

/**
 * Преобразование `AudioAttributes.usage` в поток системной громкости по
 * правилам Android (`AudioAttributes.getVolumeControlStream()`), а не
 * захардкоженный STREAM_MUSIC.
 *
 * Потоки вычисляются из атрибутов, с которыми РЕАЛЬНО созданы плееры
 * приложения (музыка и микрозвук); значения usage у Media3 `C.USAGE_*`
 * совпадают с `android.media.AudioAttributes.USAGE_*`.
 */
object AudioUsageStreams {

    /** null — usage неподдерживаемый/невалидный: поток пропускается. */
    fun streamForUsage(usage: Int): Int? = try {
        AudioAttributes.Builder()
            .setUsage(usage)
            .build()
            .volumeControlStream
            .takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    /** Различные потоки для набора usage'ов (без дублей). */
    fun streamsForUsages(usages: Collection<Int>): Set<Int> =
        usages.asSequence()
            .mapNotNull(::streamForUsage)
            .toSet()
}
