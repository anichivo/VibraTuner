package com.personal.vibratuner.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.service.PlaybackNotification
import com.personal.vibratuner.service.SoundPlaybackService

/**
 * Кнопки play/pause и «Остановить» единственного уведомления воспроизведения
 * (смахивание приходит как [PlaybackNotification.ACTION_DISMISSED]).
 *
 * Приёмник НИКОГДА не запускает сервис: он передаёт действие только живому
 * экземпляру [SoundPlaybackService], а тот отправляет команду координатору.
 * Если сервис уже остановлен, действие — безопасный no-op с удалением
 * устаревшего уведомления. Тап по самому уведомлению сюда НЕ приходит: у него
 * отдельный PendingIntent.getActivity на главную Activity.
 */
class PlaybackNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Уведомление: действие $action")
        SoundPlaybackService.handlePlaybackNotificationAction(
            context = context,
            action = action,
            kindName = intent.getStringExtra(PlaybackNotification.EXTRA_SOUND_KIND),
            displayName = intent.getStringExtra(PlaybackNotification.EXTRA_SOUND_NAME)
        )
    }

    companion object {
        private const val TAG = "PlaybackNotificationReceiver"
    }
}
