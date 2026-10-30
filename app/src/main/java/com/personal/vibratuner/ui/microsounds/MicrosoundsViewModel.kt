package com.personal.vibratuner.ui.microsounds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.microsound.MicrosoundWindow
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.data.local.alarm.AlarmScheduler
import com.personal.vibratuner.data.local.db.MicrosoundEntity
import com.personal.vibratuner.data.local.microsound.MicrosoundScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MicrosoundsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication
    private val microsoundDao = app.database.microsoundDao()
    private val preferences = app.preferencesDataStore

    val microsounds: StateFlow<List<MicrosoundEntity>> = microsoundDao.getAllMicrosoundsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quietHours: StateFlow<QuietHoursSettings> = preferences.quietHoursFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QuietHoursSettings())

    /** Закреплённый микрозвук «Включение колонки» (DataStore). */
    val pinnedMicrosound: StateFlow<PinnedMicrosoundSettings> = preferences.pinnedMicrosoundFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PinnedMicrosoundSettings()
        )

    fun toggleMicrosound(microsound: MicrosoundEntity) {
        viewModelScope.launch {
            val updated = microsound.copy(isEnabled = !microsound.isEnabled)
            microsoundDao.updateMicrosound(updated)
            if (updated.isEnabled) {
                MicrosoundScheduler.scheduleNext(getApplication(), updated)
            } else {
                MicrosoundScheduler.cancel(getApplication(), updated.id)
            }
        }
    }

    fun deleteMicrosound(microsound: MicrosoundEntity) {
        viewModelScope.launch {
            MicrosoundScheduler.cancel(getApplication(), microsound.id)
            microsoundDao.deleteMicrosound(microsound)
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setQuietHoursEnabled(enabled)
            rescheduleAfterQuietHoursChange()
        }
    }

    fun setQuietHours(startMinuteOfDay: Int, endMinuteOfDay: Int) {
        viewModelScope.launch {
            val saved = preferences.setQuietHours(startMinuteOfDay, endMinuteOfDay)
            if (saved) {
                rescheduleAfterQuietHoursChange()
            }
        }
    }

    /**
     * С этапа 4 «Часы тишины» важнее собственного окна ЛЮБОГО микрозвука,
     * поэтому их изменение перепланирует всё, что зависит от тишины:
     * обычные микрозвуки, фейк-включение и предзвук «Включение колонки»
     * (его сигнал ставится отдельно вместе с будильниками).
     */
    private suspend fun rescheduleAfterQuietHoursChange() {
        MicrosoundScheduler.rescheduleAll(getApplication())
        MicrosoundScheduler.rescheduleFake(getApplication())
        AlarmScheduler.rescheduleAll(
            getApplication(),
            reason = "смена «Часов тишины»"
        )
    }

    /**
     * Любое изменение закреплённого микрозвука требует перепланирования будильников:
     * старт последовательности вычисляется из фактической длительности файла и T,
     * а URI/gain/длительность хранятся в PendingIntent предзвука.
     * Фейк-включение перепланируется отдельно: оно не зависит от будильников, но
     * обязано учитывать включение звука и смену файла/усиления.
     */
    fun setPinnedMicrosoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setPinnedMicrosoundEnabled(enabled)
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = "вкл/выкл «Включения колонки» ($enabled)"
            )
            MicrosoundScheduler.rescheduleFake(getApplication())
        }
    }

    /**
     * Свой файл «Включения колонки». Фейк-включение играет этот же звук,
     * поэтому перепланируется вместе с предзвуком будильника.
     */
    fun setPinnedMicrosoundFile(uri: String, fileName: String) {
        viewModelScope.launch {
            preferences.setPinnedMicrosoundFile(uri, fileName)
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = "смена файла «Включения колонки»"
            )
            MicrosoundScheduler.rescheduleFake(getApplication())
        }
    }

    /**
     * Встроенный звук «Включения колонки». Фейк-включение собственного выбора
     * звука не имеет и всегда использует этот же звук, поэтому меняется и оно.
     */
    fun setPinnedMicrosoundBuiltinSound(storedUri: String) {
        viewModelScope.launch {
            if (preferences.setPinnedMicrosoundBuiltin(storedUri)) {
                AlarmScheduler.rescheduleAll(
                    getApplication(),
                    reason = "смена встроенного звука «Включения колонки»"
                )
                MicrosoundScheduler.rescheduleFake(getApplication())
            }
        }
    }

    fun setPinnedMicrosoundGain(gain: Float) {
        viewModelScope.launch {
            preferences.setPinnedMicrosoundGain(gain)
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = "смена усиления «Включения колонки»"
            )
            MicrosoundScheduler.rescheduleFake(getApplication())
        }
    }

    /**
     * Собственное окно работы «Включения колонки» (Stage 3). Время старта
     * предзвука зависит от будильников, поэтому окно требует перепланирования
     * расписания: вне окна предзвук не планируется, сам будильник остаётся в T.
     */
    fun setPinnedMicrosoundWindow(window: MicrosoundWindow?) {
        viewModelScope.launch {
            if (preferences.setPinnedMicrosoundWindow(window)) {
                AlarmScheduler.rescheduleAll(
                    getApplication(),
                    reason = "смена окна работы «Включения колонки»"
                )
            }
        }
    }

    fun setFakePinnedMicrosoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setFakePinnedMicrosoundEnabled(enabled)
            MicrosoundScheduler.rescheduleFake(getApplication())
        }
    }

    fun setFakePinnedMicrosoundIntervals(minIntervalMinutes: Int, maxIntervalMinutes: Int) {
        viewModelScope.launch {
            val saved = preferences.setFakePinnedMicrosoundIntervals(
                minIntervalMinutes = minIntervalMinutes,
                maxIntervalMinutes = maxIntervalMinutes
            )
            if (saved) {
                MicrosoundScheduler.rescheduleFake(getApplication())
            }
        }
    }

    /**
     * Собственное окно работы фейк-включения колонки (Stage 3). `null` — «весь
     * день». После сохранения фейк перепланируется: окно ограничивает время
     * срабатывания так же, как у обычного микрозвука.
     */
    fun setFakePinnedMicrosoundWindow(window: MicrosoundWindow?) {
        viewModelScope.launch {
            if (preferences.setFakePinnedMicrosoundWindow(window)) {
                MicrosoundScheduler.rescheduleFake(getApplication())
            }
        }
    }

    fun saveMicrosound(
        id: Long = 0,
        title: String,
        uri: String,
        gain: Float,
        minIntervalMinutes: Int,
        maxIntervalMinutes: Int,
        isEnabled: Boolean = true,
        window: MicrosoundWindow? = null
    ) {
        viewModelScope.launch {
            val entity = MicrosoundEntity(
                id = id,
                title = title,
                uri = uri,
                gain = gain,
                minIntervalMinutes = minIntervalMinutes,
                maxIntervalMinutes = maxIntervalMinutes,
                isEnabled = isEnabled,
                windowStartMinutes = window?.startMinuteOfDay,
                windowEndMinutes = window?.endMinuteOfDay
            )

            val savedId = if (id == 0L) {
                microsoundDao.insertMicrosound(entity)
            } else {
                microsoundDao.updateMicrosound(entity)
                id
            }

            // Окно/интервалы могли измениться: микрозвук всегда перепланируется
            // (и при выключении — отменяется).
            if (isEnabled) {
                val toSchedule = entity.copy(id = savedId)
                MicrosoundScheduler.scheduleNext(getApplication(), toSchedule)
            } else {
                MicrosoundScheduler.cancel(getApplication(), savedId)
            }
        }
    }
}
