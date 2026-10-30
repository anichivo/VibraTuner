package com.personal.vibratuner.data.local.microsound

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.MicrosoundCalculator
import com.personal.vibratuner.audio.microsound.MicrosoundQuietHoursConflict
import com.personal.vibratuner.audio.microsound.MicrosoundQuietHoursConflictCalculator
import com.personal.vibratuner.audio.microsound.MicrosoundWindowCalculator
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursCalculator
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.core.platform.AlarmScheduleMode
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.MicrosoundEntity
import com.personal.vibratuner.receiver.MicrosoundReceiver
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Планировщик периодических микрозвуков.
 * Каждый активный микрозвук планируется независимо со своим случайным интервалом
 * через setExactAndAllowWhileIdle (или запасной setAndAllowWhileIdle).
 *
 * Здесь же планируется фейк-включение колонки: это тот же механизм случайного
 * интервала min..max, а не отдельный планировщик. Фейк не зависит от будильников
 * и перепланируется при изменении своих настроек/состояния звука и после загрузки.
 * С этапа 4 фейк подчиняется глобальным «Часам тишины» так же, как обычный
 * микрозвук: ни одно срабатывание не планируется в тишину.
 */
object MicrosoundScheduler {
    private const val TAG = "MicrosoundScheduler"
    private const val REQUEST_CODE_BASE = 4000
    private const val FAKE_PINNED_REQUEST_CODE = 3100

    /**
     * Запланировать следующее срабатывание для конкретного микрозвука.
     */
    suspend fun scheduleNext(
        context: Context,
        microsound: MicrosoundEntity,
        fromTimestampMs: Long = System.currentTimeMillis()
    ): Long {
        if (!microsound.isEnabled) {
            cancel(context, microsound.id)
            return -1L
        }

        val calculatedTriggerMs = MicrosoundCalculator.calculateNextTriggerTime(
            nowEpochMs = fromTimestampMs,
            minMinutes = microsound.minIntervalMinutes,
            maxMinutes = microsound.maxIntervalMinutes
        )

        val zoneId = ZoneId.systemDefault()
        val quietHours = loadQuietHoursSettings()
        val window = microsound.window

        // Этап 4: глобальные «Часы тишины» ВСЕГДА важнее собственного окна.
        // Окно, целиком лежащее в тишине, не даст ни одного срабатывания,
        // пока настройки не изменятся: сигнал не ставим вовсе, чтобы не
        // создавать «мёртвых» сигналов и петель перепланирования на каждом
        // срабатывании. Изменение часов тишины/окна перепланирует микрозвук.
        if (window != null &&
            MicrosoundQuietHoursConflictCalculator.conflict(quietHours, window) ==
            MicrosoundQuietHoursConflict.FULL
        ) {
            cancel(context, microsound.id)
            Log.i(
                TAG,
                "Микрозвук id=${microsound.id}: окно работы " +
                    "${window.startMinuteOfDay}-${window.endMinuteOfDay} целиком попадает " +
                    "в часы тишины — сигнал не планируется до изменения настроек"
            )
            return -1L
        }

        // Собственное окно работы микрозвука (Stage 3): срабатывание может быть
        // только внутри него. Время вне окна переносится на начало следующего
        // окна чистой функцией MicrosoundWindowCalculator (без разброса).
        val windowedTriggerMs = MicrosoundWindowCalculator.clipToWindow(
            candidateEpochMs = calculatedTriggerMs,
            window = window,
            zoneId = zoneId
        )
        if (windowedTriggerMs != calculatedTriggerMs) {
            Log.i(
                TAG,
                "Микрозвук id=${microsound.id}: время $calculatedTriggerMs вне окна работы " +
                    "(${microsound.windowStartMinutes}-${microsound.windowEndMinutes}), " +
                    "перенесено на начало следующего окна $windowedTriggerMs"
            )
        }

        // Затем глобальные «Часы тишины»:
        // - окно есть: берём БЛИЖАЙШИЙ реально играемый момент (конец тишины
        //   или начало окна — что позже), иначе прежний сдвиг «конец тишины +
        //   случайный интервал» мог вынести срабатывание из окна и зациклить
        //   планирование;
        // - окна нет («весь день»): прежнее поведение этапа 3 — конец тишины
        //   плюс случайный интервал min..max.
        val triggerTimeMs = if (window != null) {
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = windowedTriggerMs,
                quietHours = quietHours,
                window = window,
                zoneId = zoneId
            ) ?: run {
                cancel(context, microsound.id)
                Log.e(
                    TAG,
                    "Микрозвук id=${microsound.id}: внутри окна нет момента вне часов " +
                        "тишины — сигнал не планируется до изменения настроек"
                )
                return -1L
            }
        } else {
            QuietHoursCalculator.shiftIfWithinQuietHours(
                triggerEpochMs = windowedTriggerMs,
                settings = quietHours,
                minIntervalMinutes = microsound.minIntervalMinutes,
                maxIntervalMinutes = microsound.maxIntervalMinutes,
                zoneId = zoneId
            )
        }
        if (triggerTimeMs != windowedTriggerMs) {
            Log.i(
                TAG,
                "Микрозвук id=${microsound.id}: время попало в часы тишины, " +
                    "сдвинуто с $windowedTriggerMs на $triggerTimeMs"
            )
        }

        val pendingIntent = createPendingIntent(context, microsound)
        val mode = PlatformCompat.scheduleMicrosoundAlarm(context, triggerTimeMs, pendingIntent)

        val intervalMin = ((triggerTimeMs - fromTimestampMs) / 60_000L).toInt()
        Log.i(
            TAG,
            "Микрозвук id=${microsound.id} ('${displayName(context, microsound)}') " +
                "запланирован через $intervalMin мин (режим: $mode)"
        )
        return triggerTimeMs
    }

    /**
     * Отменить запланированное срабатывание микрозвука по ID.
     */
    fun cancel(context: Context, microsoundId: Long) {
        val dummyEntity = MicrosoundEntity(
            id = microsoundId,
            title = "",
            uri = "",
            minIntervalMinutes = 1,
            maxIntervalMinutes = 2
        )
        val pendingIntent = createPendingIntent(context, dummyEntity)
        PlatformCompat.cancelAlarmSafely(context, pendingIntent)
        Log.i(TAG, "Микрозвук id=$microsoundId отменен")
    }

    /**
     * Запланировать следующее срабатывание фейк-включения колонки.
     * Возвращает время срабатывания или -1, если фейк выключен (тогда старый
     * сигнал отменяется).
     */
    suspend fun scheduleNextFake(
        context: Context,
        fromTimestampMs: Long = System.currentTimeMillis()
    ): Long {
        val settings = loadPinnedMicrosoundSettings()
        return scheduleNextFake(context, settings, fromTimestampMs)
    }

    suspend fun scheduleNextFake(
        context: Context,
        settings: PinnedMicrosoundSettings,
        fromTimestampMs: Long = System.currentTimeMillis()
    ): Long {
        if (!settings.fakeCanPlay) {
            cancelFake(context)
            Log.i(
                TAG,
                "Фейк-включение колонки не планируется: выключен звук «Включения колонки», " +
                    "звук не выбран или выключен фейк"
            )
            return -1L
        }

        val zoneId = ZoneId.systemDefault()
        val quietHours = loadQuietHoursSettings()
        val window = settings.fakeWindow
        val rawTriggerMs = MicrosoundCalculator.calculateNextTriggerTime(
            nowEpochMs = fromTimestampMs,
            minMinutes = settings.fakeMinIntervalMinutes,
            maxMinutes = settings.fakeMaxIntervalMinutes
        )

        // Этап 4: часы тишины важнее окна фейка. Окно целиком в тишине —
        // сигнал не нужен до изменения настроек (без петель перепланирования).
        if (window != null &&
            MicrosoundQuietHoursConflictCalculator.conflict(quietHours, window) ==
            MicrosoundQuietHoursConflict.FULL
        ) {
            cancelFake(context)
            Log.i(
                TAG,
                "Фейк-включение колонки: окно работы целиком попадает в часы тишины — " +
                    "сигнал не планируется до изменения настроек"
            )
            return -1L
        }

        // Окно работы фейка — та же чистая функция, что у обычного микрозвука.
        val windowedTriggerMs = MicrosoundWindowCalculator.clipToWindow(
            candidateEpochMs = rawTriggerMs,
            window = window,
            zoneId = zoneId
        )
        if (windowedTriggerMs != rawTriggerMs) {
            Log.i(
                TAG,
                "Фейк-включение колонки: время $rawTriggerMs вне окна работы, " +
                    "перенесено на начало следующего окна $windowedTriggerMs"
            )
        }

        // Часы тишины для фейка действуют так же, как для обычного микрозвука:
        // с окном — ближайший реально играемый момент, без окна — конец тишины
        // плюс случайный интервал.
        val triggerTimeMs = if (window != null) {
            MicrosoundQuietHoursConflictCalculator.nextPlayableEpochMs(
                candidateEpochMs = windowedTriggerMs,
                quietHours = quietHours,
                window = window,
                zoneId = zoneId
            ) ?: run {
                cancelFake(context)
                Log.e(
                    TAG,
                    "Фейк-включение колонки: внутри окна нет момента вне часов тишины — " +
                        "сигнал не планируется до изменения настроек"
                )
                return -1L
            }
        } else {
            QuietHoursCalculator.shiftIfWithinQuietHours(
                triggerEpochMs = windowedTriggerMs,
                settings = quietHours,
                minIntervalMinutes = settings.fakeMinIntervalMinutes,
                maxIntervalMinutes = settings.fakeMaxIntervalMinutes,
                zoneId = zoneId
            )
        }
        if (triggerTimeMs != windowedTriggerMs) {
            Log.i(
                TAG,
                "Фейк-включение колонки: время попало в часы тишины, " +
                    "сдвинуто с $windowedTriggerMs на $triggerTimeMs"
            )
        }
        val pendingIntent = createFakePendingIntent(context, triggerTimeMs)
        val mode = PlatformCompat.scheduleMicrosoundAlarm(context, triggerTimeMs, pendingIntent)

        val intervalMin = ((triggerTimeMs - fromTimestampMs) / 60_000L).toInt()
        if (mode == AlarmScheduleMode.FAILED) {
            Log.e(
                TAG,
                "Фейк-включение колонки НЕ запланировано на $triggerTimeMs: точное и " +
                    "неточное планирование недоступны, следующего срабатывания не будет"
            )
        } else {
            Log.i(
                TAG,
                "Фейк-включение колонки запланировано на $triggerTimeMs " +
                    "(через $intervalMin мин, режим: $mode)"
            )
        }
        return triggerTimeMs
    }

    /** Отменить запланированное срабатывание фейк-включения колонки. */
    fun cancelFake(context: Context) {
        try {
            val intent = Intent(context, MicrosoundReceiver::class.java).apply {
                action = MicrosoundReceiver.ACTION_FIRE_FAKE_PINNED_MICROSOUND
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                FAKE_PINNED_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                PlatformCompat.cancelAlarmSafely(context, pendingIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отмены фейк-включения колонки: ${e.message}", e)
        }
    }

    /**
     * Перепланировать фейк по текущим настройкам «Включения колонки»
     * (включение/выключение, звук, усиление, интервалы фейка). Вызывается после
     * изменения настроек и после загрузки.
     */
    suspend fun rescheduleFake(context: Context) {
        try {
            scheduleNextFake(context)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перепланирования фейк-включения колонки: ${e.message}", e)
        }
    }

    /**
     * Перепланировать все активные микрозвуки (например, после перезагрузки или изменения настроек).
     */
    suspend fun rescheduleAll(context: Context) {
        try {
            val db = VibraTunerApplication.instance.database
            val enabledList = db.microsoundDao().getEnabledMicrosounds()
            Log.i(TAG, "Перепланирование активных микрозвуков: ${enabledList.size} шт.")
            for (microsound in enabledList) {
                scheduleNext(context, microsound)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перепланирования микрозвуков: ${e.message}", e)
        }
    }

    private suspend fun loadQuietHoursSettings(): QuietHoursSettings {
        return try {
            VibraTunerApplication.instance.preferencesDataStore.quietHoursFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать часы тишины, микрозвук планируется без сдвига", e)
            QuietHoursSettings()
        }
    }

    private suspend fun loadPinnedMicrosoundSettings(): PinnedMicrosoundSettings {
        return try {
            VibraTunerApplication.instance.preferencesDataStore.pinnedMicrosoundFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать настройки «Включения колонки»", e)
            PinnedMicrosoundSettings()
        }
    }

    /**
     * PendingIntent фейка не содержит URI/gain: в момент срабатывания читаются
     * текущие настройки «Включения колонки» (фейк играет ЕГО звук), поэтому
     * смена звука не требует копирования данных. Ожидаемое время срабатывания
     * кладётся только для диагностики (extras не входят в идентичность
     * PendingIntent, отмена находит его так же).
     */
    private fun createFakePendingIntent(context: Context, expectedTriggerMs: Long): PendingIntent {
        val intent = Intent(context, MicrosoundReceiver::class.java).apply {
            action = MicrosoundReceiver.ACTION_FIRE_FAKE_PINNED_MICROSOUND
            putExtra(MicrosoundReceiver.EXTRA_EXPECTED_TRIGGER_MS, expectedTriggerMs)
        }
        return PendingIntent.getBroadcast(
            context,
            FAKE_PINNED_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createPendingIntent(context: Context, microsound: MicrosoundEntity): PendingIntent {
        val intent = Intent(context, MicrosoundReceiver::class.java).apply {
            action = MicrosoundReceiver.ACTION_FIRE_MICROSOUND
            putExtra(MicrosoundReceiver.EXTRA_MICROSOUND_ID, microsound.id)
            putExtra(MicrosoundReceiver.EXTRA_URI, microsound.uri)
            putExtra(MicrosoundReceiver.EXTRA_GAIN, microsound.gain)
            putExtra(MicrosoundReceiver.EXTRA_NAME, displayName(context, microsound))
        }
        val requestCode = (REQUEST_CODE_BASE + (microsound.id % 5000)).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Отображаемое имя микрозвука для уведомления: у встроенного — строка из
     * каталога (strings.xml), у своего файла — прежнее сохранённое название.
     */
    private fun displayName(context: Context, microsound: MicrosoundEntity): String {
        val builtin = BuiltinMicrosoundCatalog.findByStored(microsound.uri)
        return builtin?.let { context.getString(it.nameRes) } ?: microsound.title
    }
}
