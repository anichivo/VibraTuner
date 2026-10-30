package com.personal.vibratuner.core.platform

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Единая точка ветвления логики по версиям Android (API 31–37).
 * Все проверки Build.VERSION.SDK_INT обязаны находиться здесь.
 */
object PlatformCompat {
    private const val TAG = "PlatformCompat"

    /**
     * Разрешение на уведомления требуется только начиная с Android 13 (API 33).
     */
    fun canRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Проверка наличия разрешения POST_NOTIFICATIONS.
     * На Android 12 (API 31-32) разрешения в манифесте нет, поэтому возвращаем true.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Проверка возможности установки точных будильников (API 31+).
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка проверки canScheduleExactAlarms", e)
                false
            }
        } else {
            true
        }
    }

    /**
     * На Android 13+ (API 33+) точные будильники предоставляются автоматически через USE_EXACT_ALARM.
     * На Android 12/12L (API 31-32) используется SCHEDULE_EXACT_ALARM и пользователь может его отозвать.
     */
    fun isExactAlarmFixedBySystem(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Чистая функция выбора режима планирования микрозвуков:
     * точный (EXACT) при наличии разрешения или запасной неточный (INEXACT).
     */
    fun determineMicrosoundScheduleMode(canScheduleExact: Boolean): AlarmScheduleMode {
        return if (canScheduleExact) AlarmScheduleMode.EXACT else AlarmScheduleMode.INEXACT
    }

    /**
     * Проверка, отключена ли оптимизация батареи для нашего приложения.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки оптимизации батареи", e)
            false
        }
    }

    /**
     * Переход в настройки точных будильников (актуально для Android 12/12L).
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось открыть ACTION_REQUEST_SCHEDULE_EXACT_ALARM", e)
                openAppDetailsSettings(context)
            }
        }
    }

    /**
     * Переход в настройки уведомлений приложения (на Android 13+).
     */
    fun openNotificationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть настройки уведомлений", e)
            openAppDetailsSettings(context)
        }
    }

    /**
     * Переход в настройки оптимизации батареи.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS", e)
            openAppDetailsSettings(context)
        }
    }

    private fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось открыть ACTION_APPLICATION_DETAILS_SETTINGS", e)
        }
    }

    /**
     * Безопасная установка точного будильника через AlarmManager.setAlarmClock.
     * Защищено от SecurityException.
     */
    fun setAlarmClockSafely(
        context: Context,
        triggerTimeMillis: Long,
        showIntent: android.app.PendingIntent,
        alarmIntent: android.app.PendingIntent
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return try {
            val info = AlarmManager.AlarmClockInfo(triggerTimeMillis, showIntent)
            alarmManager.setAlarmClock(info, alarmIntent)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при установке setAlarmClock", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при установке setAlarmClock: ${e.message}", e)
            false
        }
    }

    /**
     * Безопасная отмена будильника через AlarmManager.
     */
    fun cancelAlarmSafely(context: Context, operation: android.app.PendingIntent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.cancel(operation)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отмены будильника: ${e.message}", e)
        }
    }

    /**
     * Безопасный запуск Foreground Service для воспроизведения аудио.
     * Обернут в try/catch от ForegroundServiceStartNotAllowedException и SecurityException.
     * Возвращает true, если запуск успешно запрошен.
     */
    fun startForegroundServiceSafely(context: Context, intent: Intent): Boolean {
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // Включает ForegroundServiceStartNotAllowedException на Android 12+
            Log.e(TAG, "Отказ запуска Foreground Service: ${e.message}", e)
            false
        }
    }

    /**
     * Вызов ServiceCompat.startForeground с явным указанием типа FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK.
     */
    fun startForegroundMediaPlayback(
        service: Service,
        notificationId: Int,
        notification: Notification
    ) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(service, notificationId, notification, serviceType)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка startForegroundMediaPlayback: ${e.message}", e)
        }
    }

    /**
     * Планирование микрозвука (в том числе старта последовательности «Включение
     * колонки» и фейк-включения) через AlarmManager:
     * - Проверка canScheduleExactAlarms()
     * - При наличии точного режима: setExactAndAllowWhileIdle
     * - При отсутствии разрешения, SecurityException или любой другой ошибке:
     *   обязательный запасной setAndAllowWhileIdle (неточный) с записью в лог;
     * - Если не удалось запланировать и неточный сигнал — [AlarmScheduleMode.FAILED].
     *
     * НИКОГДА не бросает исключений: отказ в разрешении не должен ронять вызов.
     */
    fun scheduleMicrosoundAlarm(
        context: Context,
        triggerTimeMillis: Long,
        operation: android.app.PendingIntent
    ): AlarmScheduleMode {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager недоступен: сигнал на $triggerTimeMillis не запланирован")
            return AlarmScheduleMode.FAILED
        }

        val canExact = canScheduleExactAlarms(context)
        Log.i(
            TAG,
            "Планирование сигнала на $triggerTimeMillis: canScheduleExactAlarms=$canExact"
        )
        if (!canExact) {
            Log.w(
                TAG,
                "Разрешение на точные будильники отсутствует, " +
                    "планирование сигнала на $triggerTimeMillis в неточном режиме"
            )
            return scheduleInexactSafely(alarmManager, triggerTimeMillis, operation)
        }

        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMillis,
                operation
            )
            AlarmScheduleMode.EXACT
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "SecurityException при setExactAndAllowWhileIdle, используем setAndAllowWhileIdle",
                e
            )
            scheduleInexactSafely(alarmManager, triggerTimeMillis, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка точного планирования сигнала: ${e.message}", e)
            scheduleInexactSafely(alarmManager, triggerTimeMillis, operation)
        }
    }

    /** Запасной неточный режим: вызывается на ЛЮБОМ отказе точного планирования. */
    private fun scheduleInexactSafely(
        alarmManager: AlarmManager,
        triggerTimeMillis: Long,
        operation: android.app.PendingIntent
    ): AlarmScheduleMode {
        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMillis,
                operation
            )
            AlarmScheduleMode.INEXACT
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка неточного планирования сигнала: ${e.message}", e)
            AlarmScheduleMode.FAILED
        }
    }

    /**
     * Запрос кратковременного аудиофокуса (AUDIOFOCUS_GAIN_TRANSIENT)
     * перед воспроизведением микрозвука вне музыкальной сессии.
     * Возвращает созданный AudioFocusRequest (API 26+) для последующего освобождения.
     */
    fun requestTransientAudioFocus(
        context: Context,
        listener: android.media.AudioManager.OnAudioFocusChangeListener
    ): android.media.AudioFocusRequest? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return null
        return try {
            val playbackAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(listener)
                .build()

            val result = audioManager.requestAudioFocus(focusRequest)
            if (result != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(
                    TAG,
                    "Кратковременный аудиофокус не получен (результат $result), " +
                        "продолжаем воспроизведение по правилам"
                )
            } else {
                Log.i(TAG, "Кратковременный аудиофокус получен")
            }
            focusRequest
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса аудиофокуса: ${e.message}", e)
            null
        }
    }

    /**
     * Освобождение кратковременного аудиофокуса.
     */
    fun abandonTransientAudioFocus(
        context: Context,
        focusRequest: android.media.AudioFocusRequest?
    ) {
        if (focusRequest == null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return
        try {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка освобождения аудиофокуса: ${e.message}", e)
        }
    }

    /**
     * Отключение принудительного контрастного scrim на системной панели навигации (API 29+).
     */
    fun disableNavigationBarContrastEnforced(window: android.view.Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

enum class AlarmScheduleMode {
    EXACT,
    INEXACT,

    /** Не удалось запланировать ни точно, ни неточно (сигнала не будет). */
    FAILED
}
