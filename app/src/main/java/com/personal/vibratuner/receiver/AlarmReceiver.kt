package com.personal.vibratuner.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.alarm.AlarmScheduler
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.service.SoundPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ресивер срабатывания будильника AlarmClock.
 * Запускает Foreground Service СРАЗУ в onReceive (без отложенных корутин),
 * обеспечивая соответствие требованиям Android 12+ по запуску сервисов из фона.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_ALARM) return

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 2)
        val trackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L).takeIf { it > 0L }
        val gain = intent.getFloatExtra(EXTRA_GAIN, AlarmEntity.DEFAULT_GAIN)
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)
            ?: AlarmSoundSource.TRACK.value
        val playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L).takeIf { it > 0L }
        // Плейлист каталога «От автора» (без копии в библиотеке): читается из кеша манифеста.
        val catalogPlaylistId = intent.getStringExtra(EXTRA_CATALOG_PLAYLIST_ID)
            ?.takeIf { it.isNotBlank() }
        val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
        val expectedTriggerMs = intent.getLongExtra(EXTRA_EXPECTED_TRIGGER_MS, 0L)
        val nowMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "Сработал будильник id=$alarmId: ожидалось $expectedTriggerMs, фактически $nowMs " +
                "(задержка ${if (expectedTriggerMs > 0L) nowMs - expectedTriggerMs else 0L} мс), " +
                "duration=$durationMinutes мин, trackId=$trackId, gain=$gain, " +
                "source=$sourceType, playlistId=$playlistId, " +
                "catalogPlaylistId=$catalogPlaylistId, shuffle=$shuffle"
        )

        // 1. Запуск Foreground Service СРАЗУ, без отложенных корутин
        val serviceIntent = SoundPlaybackService.startAlarmIntent(
            context = context,
            alarmId = alarmId,
            durationMinutes = durationMinutes,
            trackId = trackId,
            gain = gain,
            sourceType = sourceType,
            playlistId = playlistId,
            shuffle = shuffle,
            catalogPlaylistId = catalogPlaylistId
        )
        val started = PlatformCompat.startForegroundServiceSafely(context, serviceIntent)
        if (!started) {
            Log.e(TAG, "Не удалось запустить сервис воспроизведения для будильника id=$alarmId")
        }

        // 2. Асинхронно обновляем статус будильника (если однократный) и перепланируем
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = VibraTunerApplication.instance.database
                val alarm = db.alarmDao().getAlarmById(alarmId)
                if (alarm != null && alarm.daysOfWeek.isBlank()) {
                    db.alarmDao().updateAlarm(alarm.copy(isEnabled = false))
                }
                AlarmScheduler.rescheduleAll(context, reason = "срабатывание будильника id=$alarmId")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка перепланирования после будильника", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE_ALARM = "com.personal.vibratuner.action.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"
        const val EXTRA_TRACK_ID = "extra_alarm_track_id"
        const val EXTRA_GAIN = "extra_alarm_gain"
        const val EXTRA_SOURCE_TYPE = "extra_alarm_source_type"
        const val EXTRA_PLAYLIST_ID = "extra_alarm_playlist_id"

        /** Плейлист каталога «От автора» без копии в библиотеке (v11). */
        const val EXTRA_CATALOG_PLAYLIST_ID = "extra_alarm_catalog_playlist_id"
        const val EXTRA_SHUFFLE = "extra_alarm_shuffle"

        /** Ожидаемое время срабатывания — только для диагностики (в PendingIntent). */
        const val EXTRA_EXPECTED_TRIGGER_MS = "extra_expected_trigger_ms"
    }
}
