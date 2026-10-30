package com.personal.vibratuner.data.local.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.MainActivity
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.PlaybackTiming
import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.receiver.AlarmReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Планировщик будильников приложения через AlarmManager.setAlarmClock.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    private const val ALARM_PENDING_INTENT_REQ = 2001
    private const val SHOW_PENDING_INTENT_REQ = 2002

    /**
     * Перепланирование не должно выполняться параллельно: иначе два вызова
     * (например, срабатывание будильника и правка настроек) могут переставить
     * отмену и постановку сигнала, и предзвук останется отменённым.
     */
    private val rescheduleMutex = Mutex()

    /**
     * Перепланирование ближайшего активного будильника и старта последовательности
     * «Включение колонки» (T − длительность звука − фиксированные 10 с).
     * Предзвук всегда сначала отменяется, поэтому он «переезжает» вместе
     * с будильником при любом изменении, включая смену файла/включение настройки.
     *
     * @param reason причина перепланирования — только для строки в логе
     * (загрузка / правка будильника / смена настроек / срабатывание).
     */
    suspend fun rescheduleAll(context: Context, reason: String) {
        rescheduleMutex.withLock {
            rescheduleAllLocked(context, reason)
        }
    }

    private suspend fun rescheduleAllLocked(context: Context, reason: String) {
        val app = VibraTunerApplication.instance
        val alarms = app.database.alarmDao().getEnabledAlarms()
        val now = System.currentTimeMillis()
        val pinnedSettings = loadPinnedMicrosoundSettings()
        Log.i(
            TAG,
            "Перепланирование (причина: $reason): now=$now, включённых будильников: " +
                "${alarms.size}, точные будильники доступны: " +
                PlatformCompat.canScheduleExactAlarms(context)
        )

        // Старый предзвук больше не актуален: отменяем до выбора нового будильника
        PinnedMicrosoundScheduler.cancel(context)

        var nextAlarm: AlarmEntity? = null
        var earliestTriggerMillis: Long = Long.MAX_VALUE

        for (alarm in alarms) {
            val days = AlarmCalculator.parseDaysOfWeek(alarm.daysOfWeek)
            val trigger = AlarmCalculator.calculateNextTriggerMillis(
                hour = alarm.hour,
                minute = alarm.minute,
                daysOfWeek = days,
                nowMillis = now
            )
            if (trigger < earliestTriggerMillis) {
                earliestTriggerMillis = trigger
                nextAlarm = alarm
            }
        }

        if (nextAlarm != null && earliestTriggerMillis < Long.MAX_VALUE) {
            Log.i(
                TAG,
                "Ближайший будильник id=${nextAlarm.id} на T=$earliestTriggerMillis " +
                    "(через ${(earliestTriggerMillis - now) / 1000} с)"
            )
            scheduleAlarmClock(context, nextAlarm, earliestTriggerMillis)
            // Скорость нужна только предзвуку: его старт считается от реального
            // времени звучания файла (media / speed).
            val playbackSpeed = PlaybackTiming.effectiveSpeed(loadEqualizerSettings())
            PinnedMicrosoundScheduler.schedule(
                context = context,
                settings = pinnedSettings,
                alarmId = nextAlarm.id,
                alarmTriggerMs = earliestTriggerMillis,
                playbackSpeed = playbackSpeed
            )
        } else {
            Log.i(TAG, "Нет включённых будильников: будильник и предзвук отменены")
            cancelCurrentAlarm(context)
        }
    }

    private suspend fun loadPinnedMicrosoundSettings(): PinnedMicrosoundSettings {
        return try {
            VibraTunerApplication.instance.preferencesDataStore.pinnedMicrosoundFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать настройки закреплённого микрозвука", e)
            PinnedMicrosoundSettings()
        }
    }

    /**
     * Текущая скорость эквалайзера нужна предзвуку: его старт считается от
     * РЕАЛЬНОГО времени звучания файла (media / speed). Ошибка чтения не должна
     * отменять планирование будильника — работаем как на 1x.
     */
    private suspend fun loadEqualizerSettings(): EqualizerSettings {
        return try {
            VibraTunerApplication.instance.equalizerRepository.settings.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать настройки эквалайзера для предзвука", e)
            EqualizerSettings.DEFAULT
        }
    }

    private fun scheduleAlarmClock(context: Context, alarm: AlarmEntity, triggerTime: Long) {
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_ALARM
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmReceiver.EXTRA_DURATION_MINUTES, alarm.durationMinutes)
            // null = вся библиотека; -1L — маркер «трек не выбран»
            putExtra(AlarmReceiver.EXTRA_TRACK_ID, alarm.trackId ?: -1L)
            putExtra(AlarmReceiver.EXTRA_GAIN, alarm.gain)
            // Источник звука: extras обновляются FLAG_UPDATE_CURRENT при перепланировании.
            putExtra(AlarmReceiver.EXTRA_SOURCE_TYPE, alarm.sourceType)
            putExtra(AlarmReceiver.EXTRA_PLAYLIST_ID, alarm.playlistId ?: -1L)
            // Плейлист каталога «От автора»: extras обновляются FLAG_UPDATE_CURRENT,
            // поэтому после правки будильника в PendingIntent нет устаревшего значения.
            putExtra(AlarmReceiver.EXTRA_CATALOG_PLAYLIST_ID, alarm.catalogPlaylistId)
            putExtra(AlarmReceiver.EXTRA_SHUFFLE, alarm.shuffle)
            // Для диагностики: во сколько будильник должен был сработать.
            putExtra(AlarmReceiver.EXTRA_EXPECTED_TRIGGER_MS, triggerTime)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_PENDING_INTENT_REQ,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            SHOW_PENDING_INTENT_REQ,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val success = PlatformCompat.setAlarmClockSafely(
            context = context,
            triggerTimeMillis = triggerTime,
            showIntent = showPendingIntent,
            alarmIntent = pendingIntent
        )
        if (success) {
            Log.i(TAG, "Успешно запланирован будильник id=${alarm.id} на $triggerTime")
        } else {
            Log.w(TAG, "Не удалось установить будильник id=${alarm.id}")
        }
    }

    fun cancelCurrentAlarm(context: Context) {
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_PENDING_INTENT_REQ,
            alarmIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            PlatformCompat.cancelAlarmSafely(context, pendingIntent)
        }
        // Второй точный сигнал отменяется вместе с будильником
        PinnedMicrosoundScheduler.cancel(context)
    }
}
