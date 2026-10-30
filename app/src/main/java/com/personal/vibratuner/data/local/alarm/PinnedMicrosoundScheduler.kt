package com.personal.vibratuner.data.local.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.audio.PlaybackTiming
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.MicrosoundWindowCalculator
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundCalculator
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursCalculator
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.audio.microsound.SpeakerOnDurationReader
import com.personal.vibratuner.core.platform.AlarmScheduleMode
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.receiver.AlarmReceiver
import com.personal.vibratuner.receiver.MicrosoundReceiver
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Планировщик старта последовательности «Включение колонки».
 *
 * Для ближайшего будильника на T создаётся отдельный setExactAndAllowWhileIdle
 * на sequenceStart = T − (РЕАЛЬНАЯ длительность звука + 10 с). Длительность
 * файла — медиавремя; при включённом эквалайзере реальное время звучания
 * равно `media / speed` (часть 5). Основной будильник остаётся setAlarmClock
 * на T и служит страховкой/моментом старта музыки.
 * RequestCode 3001 не пересекается с будильником (2001/2002)
 * и с обычными микрозвуками (4000 + id % 5000), поэтому отмена всегда находит
 * именно этот PendingIntent.
 */
object PinnedMicrosoundScheduler {
    private const val TAG = "PinnedMicrosoundScheduler"
    private const val PREALARM_REQUEST_CODE = 3001

    /**
     * Пересоздать старт последовательности для будильника на [alarmTriggerMs].
     * Старый сигнал всегда сначала отменяется (переезжает вместе с будильником).
     * [playbackSpeed] — действующая скорость эквалайзера (1x, если он выключен).
     * Любая ошибка гасится здесь: основной будильник уже запланирован и не страдает.
     */
    suspend fun schedule(
        context: Context,
        settings: PinnedMicrosoundSettings,
        alarmId: Long,
        alarmTriggerMs: Long,
        playbackSpeed: Float = 1.0f
    ): Boolean {
        return try {
            scheduleInternal(context, settings, alarmId, alarmTriggerMs, playbackSpeed)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования «Включения колонки», будильник не затронут", e)
            false
        }
    }

    private suspend fun scheduleInternal(
        context: Context,
        settings: PinnedMicrosoundSettings,
        alarmId: Long,
        alarmTriggerMs: Long,
        playbackSpeed: Float
    ): Boolean {
        cancel(context)

        if (!settings.canPlay) {
            Log.i(TAG, "«Включение колонки» выключено или звук не выбран — предзвук не планируется")
            return false
        }

        val durationMs = SpeakerOnDurationReader.readDurationMs(context, settings.uri)
        if (durationMs <= 0L) {
            Log.w(
                TAG,
                "Длительность звука «Включение колонки» неизвестна (источник: " +
                    "MediaMetadataRetriever вернул 0) — предзвук пропущен, " +
                    "будильник сработает как обычно в T=$alarmTriggerMs"
            )
            return false
        }

        val nowMs = System.currentTimeMillis()
        if (alarmTriggerMs <= nowMs) {
            Log.w(
                TAG,
                "Будильник T=$alarmTriggerMs уже наступил — предзвук не планируется"
            )
            return false
        }

        // Старт считается от РЕАЛЬНОГО времени звучания (media / speed), иначе
        // при 0.25x предзвук начинался бы слишком поздно, а при 4x — слишком рано.
        val wallDurationMs = PlaybackTiming.wallClockMs(durationMs, playbackSpeed)
        if (wallDurationMs <= 0L) {
            Log.w(TAG, "Реальная длительность звука равна 0 — предзвук не планируется")
            return false
        }

        val sequenceStartMs = PinnedMicrosoundCalculator.calculateSequenceStartMillis(
            alarmTriggerMs = alarmTriggerMs,
            speakerOnDurationMs = wallDurationMs,
            nowMs = nowMs
        ) ?: return false

        // Этап 4: глобальные «Часы тишины» важнее собственного окна.
        // Если старт звука попадает в тишину, предзвук НЕ планируется
        // (запись в лог), а основной будильник срабатывает в T как обычно —
        // ровно как при выключенном «Включении колонки».
        val quietHours = loadQuietHoursSettings()
        val zoneId = ZoneId.systemDefault()
        if (QuietHoursCalculator.isWithinQuietHours(sequenceStartMs, quietHours, zoneId)) {
            Log.i(
                TAG,
                "Старт «Включения колонки» $sequenceStartMs попадает в часы тишины " +
                    "(${quietHours.startHour}:${quietHours.startMinute}–" +
                    "${quietHours.endHour}:${quietHours.endMinute}) — предзвук не планируется, " +
                    "будильник сработает в T=$alarmTriggerMs"
            )
            return false
        }

        // Собственное окно работы «Включения колонки» (Stage 3): звук может
        // начинаться только внутри него. Вне окна предзвук НЕ планируется
        // (запись в лог), а основной будильник срабатывает в T как обычно —
        // без предзвука, ровно как при выключенном «Включении колонки».
        if (!MicrosoundWindowCalculator.isWithinWindow(
                epochMs = sequenceStartMs,
                window = settings.window,
                zoneId = zoneId
            )
        ) {
            Log.i(
                TAG,
                "Старт «Включения колонки» $sequenceStartMs вне его окна работы " +
                    "(начало ${settings.window?.startMinuteOfDay}, " +
                    "конец ${settings.window?.endMinuteOfDay}) — " +
                    "предзвук не планируется, будильник сработает в T=$alarmTriggerMs"
            )
            return false
        }

        val pendingIntent = createPendingIntent(
            context = context,
            alarmId = alarmId,
            settings = settings,
            alarmTriggerMs = alarmTriggerMs,
            speakerOnDurationMs = durationMs,
            sequenceStartMs = sequenceStartMs
        )
        val mode = PlatformCompat.scheduleMicrosoundAlarm(context, sequenceStartMs, pendingIntent)
        if (mode == AlarmScheduleMode.FAILED) {
            Log.e(
                TAG,
                "Не удалось запланировать старт «Включения колонки» на $sequenceStartMs " +
                    "(T=$alarmTriggerMs, длительность=$durationMs мс, wall=$wallDurationMs мс): " +
                    "предзвука не будет, будильник на T не затронут"
            )
            return false
        }
        Log.i(
            TAG,
            "Включение колонки запланировано: sequenceStart=$sequenceStartMs " +
                "(T=$alarmTriggerMs, длительность=$durationMs мс, скорость=${playbackSpeed}x, " +
                "wall=$wallDurationMs мс, пауза=${PinnedMicrosoundCalculator.SPEAKER_ON_POST_PAUSE_MS} мс, " +
                "режим: $mode, будильник id=$alarmId)"
        )
        if (mode == AlarmScheduleMode.INEXACT) {
            Log.w(
                TAG,
                "Точное планирование недоступно: старт «Включения колонки» может " +
                    "задержаться, а после T предзвук будет пропущен"
            )
        }
        return true
    }

    /** Отменить предзвук (вызывается вместе с отменой/перепланированием будильника). */
    fun cancel(context: Context) {
        try {
            val intent = Intent(context, MicrosoundReceiver::class.java).apply {
                action = MicrosoundReceiver.ACTION_FIRE_PINNED_MICROSOUND
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PREALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                PlatformCompat.cancelAlarmSafely(context, pendingIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отмены предзвука: ${e.message}", e)
        }
    }

    /** Часы тишины: ошибка чтения не отменяет предзвук — работаем как при выключенных. */
    private suspend fun loadQuietHoursSettings(): QuietHoursSettings {
        return try {
            VibraTunerApplication.instance.preferencesDataStore.quietHoursFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать часы тишины: ${e.message}", e)
            QuietHoursSettings()
        }
    }

    private fun createPendingIntent(
        context: Context,
        alarmId: Long,
        settings: PinnedMicrosoundSettings,
        alarmTriggerMs: Long,
        speakerOnDurationMs: Long,
        sequenceStartMs: Long
    ): PendingIntent {
        val intent = Intent(context, MicrosoundReceiver::class.java).apply {
            action = MicrosoundReceiver.ACTION_FIRE_PINNED_MICROSOUND
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(MicrosoundReceiver.EXTRA_URI, settings.uri)
            putExtra(MicrosoundReceiver.EXTRA_GAIN, settings.gain)
            putExtra(MicrosoundReceiver.EXTRA_NAME, displayName(context, settings))
            putExtra(MicrosoundReceiver.EXTRA_ALARM_TRIGGER_MS, alarmTriggerMs)
            putExtra(MicrosoundReceiver.EXTRA_SPEAKER_ON_DURATION_MS, speakerOnDurationMs)
            // Для диагностики: во сколько сигнал должен был сработать (сравнивается в ресивере).
            putExtra(MicrosoundReceiver.EXTRA_EXPECTED_TRIGGER_MS, sequenceStartMs)
        }
        return PendingIntent.getBroadcast(
            context,
            PREALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Отображаемое имя звука «Включения колонки» для уведомления: у встроенного
     * звука — строка каталога (`strings.xml`), у своего файла — сохранённое имя.
     */
    private fun displayName(context: Context, settings: PinnedMicrosoundSettings): String {
        val builtin = BuiltinMicrosoundCatalog.findByStored(settings.uri)
        return builtin?.let { context.getString(it.nameRes) } ?: settings.fileName
    }
}
