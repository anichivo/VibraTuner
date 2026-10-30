package com.personal.vibratuner.audio.volume

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Одноразовое восстановление системной громкости после смерти процесса или
 * перезагрузки устройства.
 *
 * Здесь НЕ создаётся живой контроллер и НЕ запускается сервис: тот же
 * [SystemVolumeController] (единственный класс, который меняет системную
 * громкость) используется как короткоживущий экземпляр с пустым набором
 * потоков — ему достаточно прочитать постоянный резерв и применить то же
 * правило восстановления.
 *
 * Вызывается из `VibraTunerApplication.onCreate` и из `BootCompletedReceiver`
 * (единственное разрешённое исключение для BOOT_COMPLETED).
 */
object SystemVolumeRecovery {

    private const val TAG = "SystemVolume"

    /** Восстановление в уже существующей корутине (дождаться записи резерва). */
    suspend fun recoverIfPending(context: Context) {
        try {
            val controller = SystemVolumeController(
                backend = AndroidVolumeBackend(context.applicationContext),
                backupStore = DataStoreVolumeBackupStore(context.applicationContext),
                streams = emptySet(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                log = { message -> Log.i(TAG, "Recovery: $message") }
            )
            controller.recoverIfPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка восстановления системной громкости", e)
        }
    }

    /** Запуск из Application: процесса ещё нет в работе, ждать некому. */
    fun recoverIfPendingInBackground(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            recoverIfPending(context)
        }
    }
}
