package com.personal.vibratuner.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.MicrosoundWindowCalculator
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursCalculator
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.audio.microsound.SpeakerOnDurationReader
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.MicrosoundEntity
import com.personal.vibratuner.data.local.microsound.MicrosoundScheduler
import com.personal.vibratuner.service.SoundPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Ресивер срабатывания микрозвуков:
 * 1. Периодический таймер микрозвука (ACTION_FIRE_MICROSOUND):
 *    проверяет глобальные «Часы тишины», запускает Foreground-сервис и планирует следующий.
 * 2. Старт последовательности «Включение колонки» (ACTION_FIRE_PINNED_MICROSOUND):
 *    запускается за (длительность звука + 10 с) до будильника и не
 *    перепланируется. С этапа 4 подчиняется глобальным «Часам тишины»: в тишину
 *    предзвук не планируется, а перед воспроизведением его отсекает сервис
 *    и координатор (будильник на T при этом не страдает).
 * 3. Фейк-включение колонки (ACTION_FIRE_FAKE_PINNED_MICROSOUND): ВСЕГДА звук
 *    «Включения колонки» (своего звука у фейка нет), по случайному интервалу.
 *    С этапа 4 фейк подчиняется «Часам тишины» как обычный микрозвук.
 *    В момент срабатывания читает ТЕКУЩИЕ настройки (звук и усиление) и всегда
 *    планирует следующее срабатывание — даже если звук пропущен или недоступен.
 */
class MicrosoundReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE_PINNED_MICROSOUND -> handlePinnedMicrosound(context, intent)
            ACTION_FIRE_FAKE_PINNED_MICROSOUND -> handleFakePinnedMicrosound(context, intent)
            ACTION_FIRE_MICROSOUND -> handleScheduledMicrosound(context, intent)
        }
    }

    /**
     * Старт последовательности «Включение колонки» (sequenceStart).
     *
     * Особенности:
     * - «Часы тишины» проверяются планировщиком при постановке сигнала, а
     *   непосредственно перед звуком — сервисом и координатором (в ресивере
     *   проверка синхронная невозможна без задержки старта FGS). Сигнал,
     *   пришедший в тишину, не играет, будильник на T не затронут;
     * - НЕ перепланирует себя: живёт ровно один раз вместе с будильником;
     * - если сигнал опоздал (T уже наступил), длительность неизвестна или файл пуст —
     *   тихо пропускается, а основной будильник на T сработает как обычно.
     */
    private fun handlePinnedMicrosound(context: Context, intent: Intent) {
        val soundUri = intent.getStringExtra(EXTRA_URI) ?: ""
        val gain = intent.getFloatExtra(EXTRA_GAIN, 0.8f)
        val soundName = intent.getStringExtra(EXTRA_NAME)
        val alarmTriggerMs = intent.getLongExtra(EXTRA_ALARM_TRIGGER_MS, 0L)
        val speakerOnDurationMs = intent.getLongExtra(EXTRA_SPEAKER_ON_DURATION_MS, 0L)
        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        val expectedTriggerMs = intent.getLongExtra(EXTRA_EXPECTED_TRIGGER_MS, 0L)
        val nowMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "Сработал старт «Включения колонки»: ожидалось $expectedTriggerMs, " +
                "фактически $nowMs " +
                "(задержка ${if (expectedTriggerMs > 0L) nowMs - expectedTriggerMs else 0L} мс), " +
                "T=$alarmTriggerMs, длительность=$speakerOnDurationMs мс, будильник id=$alarmId"
        )

        if (soundUri.isBlank()) {
            Log.w(TAG, "Включение колонки: пустой URI, пропуск (будильник не затронут)")
            return
        }
        if (alarmTriggerMs <= 0L) {
            Log.w(TAG, "Включение колонки: неизвестно время будильника, пропуск")
            return
        }
        if (speakerOnDurationMs <= 0L) {
            Log.w(TAG, "Включение колонки: неизвестна длительность звука, пропуск")
            return
        }
        if (System.currentTimeMillis() >= alarmTriggerMs) {
            Log.w(
                TAG,
                "Включение колонки пропущено: время будильника T=$alarmTriggerMs уже наступило"
            )
            return
        }

        val serviceIntent = SoundPlaybackService.startPinnedMicrosoundIntent(
            context = context,
            alarmId = alarmId,
            uri = soundUri,
            gain = gain,
            alarmTriggerMs = alarmTriggerMs,
            speakerOnDurationMs = speakerOnDurationMs,
            name = soundName
        )
        val started = PlatformCompat.startForegroundServiceSafely(context, serviceIntent)
        if (!started) {
            Log.w(
                TAG,
                "Запуск сервиса для «Включения колонки» отклонён системой. " +
                    "Будильник на T сработает отдельно."
            )
        }
    }

    /**
     * Фейк-включение колонки по интервалу.
     *
     * СВОЕГО ЗВУКА У ФЕЙКА НЕТ: при каждом срабатывании читаются ТЕКУЩИЕ
     * настройки «Включения колонки» — тот же URI (встроенный звук или свой файл)
     * и то же усиление, поэтому отдельного хранилища звука фейка не существует.
     * Следующее срабатывание планируется в finally при любом исходе (пропуск,
     * ошибка, отказ запуска сервиса), чтобы планировщик не встал.
     */
    private fun handleFakePinnedMicrosound(context: Context, intent: Intent) {
        val expectedTriggerMs = intent.getLongExtra(EXTRA_EXPECTED_TRIGGER_MS, 0L)
        val firedAtMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "Сработало фейк-включение колонки: ожидалось $expectedTriggerMs, " +
                "фактически $firedAtMs " +
                "(задержка ${if (expectedTriggerMs > 0L) firedAtMs - expectedTriggerMs else 0L} мс)"
        )
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = loadPinnedMicrosoundSettings()
                if (!settings.fakeCanPlay) {
                    Log.i(
                        TAG,
                        "Фейк-включение колонки пропущено: выключен звук «Включения колонки», " +
                            "звук не выбран или выключен фейк"
                    )
                    return@launch
                }

                // Окно работы фейка (Stage 3): та же проверка, что у обычного
                // микрозвука. Сигнал после конца окна (Doze) не играет.
                if (!MicrosoundWindowCalculator.isWithinWindow(
                        epochMs = firedAtMs,
                        window = settings.fakeWindow,
                        zoneId = ZoneId.systemDefault()
                    )
                ) {
                    Log.i(
                        TAG,
                        "Фейк-включение колонки пропущено: сейчас вне его окна работы"
                    )
                    return@launch
                }

                // Этап 4: глобальные «Часы тишины» важнее окна фейка.
                // Сигнал, пришедший в тишину, пропускается; следующее
                // срабатывание планируется в finally по обычному интервалу.
                val quietHours = loadQuietHours()
                if (QuietHoursCalculator.isWithinQuietHours(
                        firedAtMs,
                        quietHours,
                        ZoneId.systemDefault()
                    )
                ) {
                    Log.i(
                        TAG,
                        "Фейк-включение колонки пропущено: сейчас часы тишины " +
                            "(${quietHours.startHour}:${quietHours.startMinute}–" +
                            "${quietHours.endHour}:${quietHours.endMinute})"
                    )
                    return@launch
                }

                // Фейк всегда играет звук «Включения колонки»: встроенный звук
                // (`builtin:<id>`) или свой файл (content://) — без своего выбора.
                val soundUri = settings.uri
                val soundBuiltin = BuiltinMicrosoundCatalog.findByStored(soundUri)
                val soundName = soundBuiltin?.let { context.getString(it.nameRes) }
                    ?: settings.fileName
                val durationMs = SpeakerOnDurationReader.readDurationMs(context, soundUri)
                if (durationMs <= 0L) {
                    Log.w(
                        TAG,
                        "Фейк-включение колонки пропущено: звук недоступен или длительность неизвестна"
                    )
                    return@launch
                }

                Log.i(
                    TAG,
                    "Фейк-включение колонки: запуск звука «Включения колонки» " +
                        "(gain=${settings.gain}, длительность=$durationMs мс, " +
                        "звук='$soundName', uri=$soundUri)"
                )
                val serviceIntent = SoundPlaybackService.startFakePinnedMicrosoundIntent(
                    context = context,
                    uri = soundUri,
                    gain = settings.gain,
                    speakerOnDurationMs = durationMs,
                    name = soundName
                )
                val started = PlatformCompat.startForegroundServiceSafely(context, serviceIntent)
                if (!started) {
                    Log.w(
                        TAG,
                        "Запуск сервиса для фейк-включения колонки отклонён системой. " +
                            "Фейк пропущен, следующее срабатывание будет запланировано."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка срабатывания фейк-включения колонки: ${e.message}", e)
            } finally {
                try {
                    MicrosoundScheduler.scheduleNextFake(context)
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Ошибка перепланирования фейк-включения колонки: ${e.message}",
                        e
                    )
                }
                pendingResult.finish()
            }
        }
    }

    private fun handleScheduledMicrosound(context: Context, intent: Intent) {
        val microsoundId = intent.getLongExtra(EXTRA_MICROSOUND_ID, -1L)
        val soundUri = intent.getStringExtra(EXTRA_URI) ?: ""
        val gain = intent.getFloatExtra(EXTRA_GAIN, 0.8f)
        val soundName = intent.getStringExtra(EXTRA_NAME)

        // goAsync даёт время на чтение DataStore (часы тишины) и перепланирование
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleFire(context, microsoundId, soundUri, gain, soundName)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки микрозвука id=$microsoundId: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Срабатывание микрозвука: проверка глобальных часов тишины (страховка от
     * задержек Doze), безопасный запуск Foreground-сервиса и планирование
     * следующего срабатывания.
     */
    private suspend fun handleFire(
        context: Context,
        microsoundId: Long,
        soundUri: String,
        gain: Float,
        soundName: String?
    ) {
        val entity = loadMicrosound(microsoundId)
        val nowMs = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()

        // Окно работы микрозвука (Stage 3): страховка от задержек Doze — сигнал,
        // пришедший уже после конца окна, не играет. Пропуск с записью в лог,
        // следующее срабатывание планируется внутри окна. Битое окно не мешает.
        if (!MicrosoundWindowCalculator.isWithinWindow(nowMs, entity?.window, zoneId)) {
            Log.i(
                TAG,
                "Микрозвук id=$microsoundId пропущен: сейчас вне его окна работы " +
                    "(начало ${entity?.windowStartMinutes}, конец ${entity?.windowEndMinutes})"
            )
            rescheduleNext(context, microsoundId, entity)
            return
        }

        val quietHours = loadQuietHours()
        val now = ZonedDateTime.now()
        val currentMinuteOfDay = now.hour * 60 + now.minute
        val isQuietNow = quietHours.enabled && quietHours.isValid &&
            QuietHoursCalculator.isWithinQuietHours(
                minuteOfDay = currentMinuteOfDay,
                startMinuteOfDay = quietHours.startMinuteOfDay,
                endMinuteOfDay = quietHours.endMinuteOfDay
            )

        if (isQuietNow) {
            Log.i(
                TAG,
                "Микрозвук id=$microsoundId пропущен: сейчас часы тишины " +
                    "${quietHours.startHour}:${quietHours.startMinute} - " +
                    "${quietHours.endHour}:${quietHours.endMinute}"
            )
            rescheduleNext(context, microsoundId, entity)
            return
        }

        if (soundUri.isBlank()) {
            Log.w(TAG, "Микрозвук id=$microsoundId имеет пустой URI, пропуск")
            rescheduleNext(context, microsoundId, entity)
            return
        }

        Log.i(TAG, "Сработал микрозвук id=$microsoundId, запуск воспроизведения (gain=$gain)")
        val serviceIntent = SoundPlaybackService.startMicrosoundIntent(
            context = context,
            microsoundId = microsoundId,
            uri = soundUri,
            gain = gain,
            name = soundName
        )

        val started = PlatformCompat.startForegroundServiceSafely(context, serviceIntent)
        if (!started) {
            // Android 12+ ForegroundServiceStartNotAllowedException или отказ системы
            Log.w(
                TAG,
                "Запуск сервиса для микрозвука id=$microsoundId отклонён системой (ForegroundServiceStartNotAllowedException). Пропуск микрозвука."
            )
        }

        // Планируем следующее срабатывание (в том числе при пропуске или отказе старта)
        rescheduleNext(context, microsoundId, entity)
    }

    /** Строка микрозвука для проверки окна работы; ошибка чтения — не повод падать. */
    private suspend fun loadMicrosound(microsoundId: Long): MicrosoundEntity? {
        return try {
            VibraTunerApplication.instance.database.microsoundDao().getMicrosoundById(microsoundId)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать микрозвук id=$microsoundId: ${e.message}", e)
            null
        }
    }

    private suspend fun rescheduleNext(
        context: Context,
        microsoundId: Long,
        loadedEntity: MicrosoundEntity? = null
    ) {
        try {
            val db = VibraTunerApplication.instance.database
            val entity = loadedEntity ?: db.microsoundDao().getMicrosoundById(microsoundId)
            if (entity != null && entity.isEnabled) {
                MicrosoundScheduler.scheduleNext(context, entity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перепланирования микрозвука id=$microsoundId: ${e.message}", e)
        }
    }

    private suspend fun loadQuietHours(): QuietHoursSettings {
        return try {
            VibraTunerApplication.instance.preferencesDataStore.quietHoursFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать часы тишины: ${e.message}", e)
            QuietHoursSettings()
        }
    }

    private suspend fun loadPinnedMicrosoundSettings(): PinnedMicrosoundSettings {
        return VibraTunerApplication.instance.preferencesDataStore.pinnedMicrosoundFlow.first()
    }

    companion object {
        private const val TAG = "MicrosoundReceiver"

        const val ACTION_FIRE_MICROSOUND = "com.personal.vibratuner.action.FIRE_MICROSOUND"
        const val ACTION_FIRE_PINNED_MICROSOUND =
            "com.personal.vibratuner.action.FIRE_PINNED_MICROSOUND"
        const val ACTION_FIRE_FAKE_PINNED_MICROSOUND =
            "com.personal.vibratuner.action.FIRE_FAKE_PINNED_MICROSOUND"
        const val EXTRA_MICROSOUND_ID = "extra_microsound_id"
        const val EXTRA_URI = "extra_microsound_uri"
        const val EXTRA_GAIN = "extra_microsound_gain"
        const val EXTRA_NAME = "extra_sound_name"
        const val EXTRA_ALARM_TRIGGER_MS = "extra_alarm_trigger_ms"
        const val EXTRA_SPEAKER_ON_DURATION_MS = "extra_speaker_on_duration_ms"

        /** Ожидаемое время срабатывания — только для диагностики (в PendingIntent). */
        const val EXTRA_EXPECTED_TRIGGER_MS = "extra_expected_trigger_ms"
    }
}
