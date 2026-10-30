package com.personal.vibratuner.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.vibratuner.audio.volume.SystemVolumeRecovery
import com.personal.vibratuner.data.local.alarm.AlarmScheduler
import com.personal.vibratuner.data.local.microsound.MicrosoundScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ресивер перепланирования расписаний: загрузка устройства, обновление приложения,
 * изменение системного времени и часового пояса.
 *
 * ПРАВИЛО: ТОЛЬКО перепланирование будильников, микрозвуков и фейк-включения.
 * НИКОГДА не запускать Foreground-сервис из этого ресивера: все сигналы — точные
 * будильники AlarmManager, они придут сами.
 *
 * Часть 3: единственное узкое исключение — на BOOT_COMPLETED синхронно (через
 * goAsync) вызывается `SystemVolumeRecovery.recoverIfPending`, чтобы вернуть
 * системную громкость, оставшуюся поднятой после смерти процесса. Сервис при
 * этом НЕ запускается, расписание не меняется.
 *
 * Причина перепланирования передаётся в лог, чтобы после неудачного прогона было
 * видно, из-за чего расписание менялось; старт-время считается той же чистой
 * функцией (`AlarmCalculator` + `PinnedMicrosoundCalculator`), что и всегда.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val reason = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "загрузка устройства"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "обновление приложения"
            Intent.ACTION_TIME_CHANGED -> "изменение системного времени"
            Intent.ACTION_TIMEZONE_CHANGED ->
                "смена часового пояса (${intent.getStringExtra(EXTRA_TIME_ZONE) ?: "?"})"
            else -> action
        }
        Log.i(
            TAG,
            "$action: перепланирование (причина: $reason). Сервис не запускается."
        )
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Часть 3, ЕДИНСТВЕННОЕ разрешённое исключение из инварианта
            // BOOT_COMPLETED: Android сохраняет системную громкость между
            // перезагрузками, поэтому подъём, оставшийся от убитого процесса,
            // нужно вернуть. Сервис по-прежнему НЕ запускается, расписание
            // не меняется; goAsync дожидается записи очищенного резерва.
            // Восстановление идёт ОТДЕЛЬНО от перепланирования: сбой одного
            // не должен отменять другое.
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                try {
                    SystemVolumeRecovery.recoverIfPending(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка восстановления системной громкости после загрузки", e)
                }
            }
            try {
                AlarmScheduler.rescheduleAll(context, reason)
                MicrosoundScheduler.rescheduleAll(context)
                // Фейк-включение колонки живёт по своему интервалу, независимо от будильников.
                MicrosoundScheduler.rescheduleFake(context)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка перепланирования после $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"

        /** Дополнительное поле системного броадкаста смены часового пояса. */
        private const val EXTRA_TIME_ZONE = "time-zone"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
