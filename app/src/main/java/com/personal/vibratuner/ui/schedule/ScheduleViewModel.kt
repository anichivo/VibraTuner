package com.personal.vibratuner.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.local.alarm.AlarmCalculator
import com.personal.vibratuner.data.local.alarm.AlarmScheduler
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.service.SoundPlaybackService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication
    private val alarmDao = VibraTunerApplication.instance.database.alarmDao()
    private val playlistRepository = VibraTunerApplication.instance.playlistRepository

    /**
     * Сеансы из Room. `null` — первый ответ БД ещё не пришёл: экран обязан
     * отличать такую загрузку от настоящего пустого списка, иначе на кадр
     * мелькает «Сеансы не настроены». После первого ответа значение уже
     * никогда не сбрасывается в `null` (в том числе при возврате на вкладку).
     */
    val alarms: StateFlow<List<AlarmEntity>?> = alarmDao.getAllAlarmsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Имена плейлистов каталога «От автора» (catalogId -> name) из КЕША манифеста,
     * без сети. Нужны карточке расписания и редактору будильника: плейлист
     * каталога можно запланировать, не добавляя в «Мою коллекцию», поэтому имени
     * в Room нет и схема БД ради него не меняется.
     */
    val catalogPlaylistNames: StateFlow<Map<String, String>> = flow {
        val names = try {
            app.authorManifest.loadCached()
                .orEmpty()
                .associate { it.id to it.name }
        } catch (e: Exception) {
            emptyMap()
        }
        emit(names)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Библиотека треков для выбора мелодии будильника. */
    val tracks: StateFlow<List<TrackEntity>> =
        VibraTunerApplication.instance.database.trackDao().getAllTracksFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Локальные плейлисты (включая «Любимое») для источника PLAYLIST. */
    val playlistList: StateFlow<List<PlaylistSummary>> = playlistRepository.playlistsWithCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            val updated = alarm.copy(isEnabled = !alarm.isEnabled)
            if (!updated.isEnabled) {
                // Если «Включение колонки» уже играет, отменяем остаток последовательности.
                SoundPlaybackService.cancelAlarmSequence(alarm.id)
            }
            alarmDao.updateAlarm(updated)
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = "вкл/выкл будильника id=${alarm.id}"
            )
        }
    }

    fun deleteAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            SoundPlaybackService.cancelAlarmSequence(alarm.id)
            alarmDao.deleteAlarm(alarm)
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = "удаление будильника id=${alarm.id}"
            )
        }
    }

    /**
     * Создание/правка будильника из единого редактора.
     * Для источника PLAYLIST проверяется только валидация формы: существование
     * плейлиста не гарантируется (его могут удалить) — это решается при срабатывании.
     *
     * Плейлист каталога «От автора» хранится в `alarms.catalogPlaylistId` (v11):
     * его не нужно добавлять в библиотеку и скачивать, а треки при срабатывании
     * читаются из кеша манифеста.
     */
    fun saveAlarm(id: Long = 0, draft: AlarmEditResult) {
        viewModelScope.launch {
            val isPlaylist = draft.source == AlarmSoundSource.PLAYLIST
            val daysStr = AlarmCalculator.formatDaysOfWeek(draft.days)
            val alarm = AlarmEntity(
                id = id,
                hour = draft.hour,
                minute = draft.minute,
                durationMinutes = draft.durationMinutes,
                isEnabled = true,
                daysOfWeek = daysStr,
                label = "",
                trackId = draft.trackId,
                gain = draft.gain.coerceIn(0f, 1f),
                sourceType = draft.source.value,
                playlistId = if (isPlaylist) draft.playlistId else null,
                catalogPlaylistId = if (isPlaylist) draft.catalogPlaylistId else null,
                shuffle = isPlaylist && draft.shuffle
            )
            if (id == 0L) {
                alarmDao.insertAlarm(alarm)
            } else {
                // Редактирование отменяет возможный уже идущий предзвук этого будильника.
                SoundPlaybackService.cancelAlarmSequence(id)
                alarmDao.updateAlarm(alarm)
            }
            AlarmScheduler.rescheduleAll(
                getApplication(),
                reason = if (id == 0L) "создание будильника" else "правка будильника id=$id"
            )
        }
    }
}
