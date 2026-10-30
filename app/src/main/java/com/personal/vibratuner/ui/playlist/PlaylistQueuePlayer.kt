package com.personal.vibratuner.ui.playlist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.service.SoundPlaybackService

/**
 * Единственный помощник запуска очереди плейлиста из UI (этапы 2 и 4).
 *
 * Сам ничего не воспроизводит: подключается к СУЩЕСТВУЮЩЕМУ
 * [SoundPlaybackService] и вызывает его [SoundPlaybackService.playQueue],
 * то есть тот же плеер и тот же координатор, что у обычной музыки. Второго
 * пути воспроизведения нет — класс только убирает дублирование подключения
 * между экраном локального плейлиста и экраном «От автора».
 */
class PlaylistQueuePlayer(
    private val app: VibraTunerApplication,
    private val tag: String
) {

    private var playbackService: SoundPlaybackService? = null
    private var bindRequested = false

    /** Очередь, если сервис ещё подключается (защитный путь). */
    private var pendingQueue: Pair<List<TrackEntity>, Int>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as? SoundPlaybackService.LocalBinder)?.getService()
            val pending = pendingQueue
            pendingQueue = null
            if (pending != null) {
                playbackService?.playQueue(pending.first, pending.second)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    fun bind() {
        bindRequested = true
        val intent = Intent(app, SoundPlaybackService::class.java)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Запуск списка: [startIndex] — индекс в [tracks] (тот же порядок).
     */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        ensureServiceStarted()
        val service = playbackService
        if (service == null) {
            // Сервис только поднимается: очередь отдадим при подключении.
            pendingQueue = tracks to startIndex
            return
        }
        service.playQueue(tracks, startIndex)
    }

    fun unbind() {
        pendingQueue = null
        if (!bindRequested) return
        try {
            app.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(tag, "Не удалось отключиться от сервиса: ${e.message}")
        }
        bindRequested = false
        playbackService = null
    }

    private fun ensureServiceStarted() {
        val intent = SoundPlaybackService.startServiceIntent(
            app,
            action = SoundPlaybackService.ACTION_PLAY_QUEUE
        )
        PlatformCompat.startForegroundServiceSafely(app, intent)
    }
}
