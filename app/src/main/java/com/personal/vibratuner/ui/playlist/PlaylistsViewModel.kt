package com.personal.vibratuner.ui.playlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.download.PlaylistDownloadState
import com.personal.vibratuner.data.local.db.PlaylistTrackInput
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.TrackSource
import com.personal.vibratuner.data.repository.PlaylistRepository
import com.personal.vibratuner.service.SoundPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Локальные плейлисты «Моей медиатеки» (карусель вкладки) и операции
 * создания/переименования/удаления. Треки добавляются и переносятся между
 * плейлистами через нижний лист выбора плейлиста (часть 3,
 * [PlaylistPickerHost]): [addTrackToPlaylist], [moveTrack],
 * [createPlaylistWithTrack], [createPlaylistWithMovedTrack].
 *
 * Валидация имени — общая чистая функция `PlaylistNameValidator`; репозиторий
 * проверяет имя повторно и не бросает исключений.
 */
class PlaylistsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VibraTunerApplication
    private val playlists = app.playlistRepository
    private val downloads = app.trackDownloads
    private val trackDao = app.database.trackDao()

    /**
     * Состояния скачивания плейлистов (индикатор в сетке «Моей коллекции»
     * и на экране плейлиста). Признак «скачан» хранится на диске (наличие
     * файла), не в БД. Загрузка — ТОЛЬКО ручная: автодогрузки при старте нет.
     */
    val downloadStates: StateFlow<Map<Long, PlaylistDownloadState>> = downloads.states

    private val _deleteEvent = MutableStateFlow<PlaylistDeleteEvent?>(null)

    /** Результат удаления для одноразового Snackbar на «Музыке». */
    val deleteEvent: StateFlow<PlaylistDeleteEvent?> = _deleteEvent.asStateFlow()

    private var deleteEventCounter = 0L

    /**
     * Локальные плейлисты библиотеки — «Любимое», пользовательские и добавленные
     * из каталога, в порядке репозитория. Источник тот же Flow, что и
     * [PlaylistRepository.observeLibraryPlaylists]: карусель «Моей медиатеки»
     * обновляется автоматически после создания/добавления плейлиста.
     */
    val playlistList: StateFlow<List<PlaylistSummary>> = playlists.observeLibraryPlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    /**
     * Сколько будильников используют каждый плейлист (источник PLAYLIST).
     * Живой Flow из DAO: предупреждение при удалении актуально сразу после
     * создания/правки будильника, даже пока вкладка уже открыта.
     */
    val alarmUsage: StateFlow<Map<Long, Int>> = playlists.alarmUsageByPlaylist()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyMap()
        )

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val id = playlists.createPlaylist(name)
            if (id <= 0L) {
                Log.w(TAG, "Плейлист не создан: имя отклонено валидацией")
            }
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            if (!playlists.renamePlaylist(playlistId, name)) {
                Log.w(TAG, "Плейлист id=$playlistId не переименован")
            }
        }
    }

    /**
     * Удаление плейлиста (часть 3): связи и строки БД, скачанные файлы, которые
     * больше не используются ни одним плейлистом, и безопасная остановка музыки,
     * если играет именно этот плейлист. «Любимое» защищено репозиторием.
     *
     * Работа запускается в [VibraTunerApplication.appScope], а не в `viewModelScope`:
     * закрытие экрана/уничтожение ViewModel НЕ должны отменять удаление данных
     * (иначе часть файлов остаётся на устройстве навсегда — их URL уже удалены
     * из БД). Очистка файлов дополнительно идёт в `NonCancellable`.
     *
     * Общие с другими плейлистами треки сохраняют свои файлы.
     */
    fun deletePlaylist(playlistId: Long) {
        app.appScope.launch {
            try {
                // catalogId нужен ДО удаления: загрузка копии каталога могла быть
                // запущена из read-only экрана под другим (отрицательным) ключом.
                val playlist = try {
                    playlists.getPlaylistOnce(playlistId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось прочитать плейлист id=$playlistId: ${e.message}")
                    null
                }
                val trackUris = try {
                    playlists.getTracksOnce(playlistId).map { it.trackUri }.toSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось прочитать треки плейлиста id=$playlistId: ${e.message}")
                    emptySet()
                }
                if (!playlists.deletePlaylist(playlistId)) {
                    Log.w(TAG, "Плейлист id=$playlistId не удалён")
                    return@launch
                }
                // Всё, что после удаления строк БД, НЕ отменяется вместе с ViewModel:
                // пользователь уже подтвердил удаление, поэтому отмена загрузки и
                // очистка файлов обязаны дойти до конца (иначе часть треков останется).
                withContext(NonCancellable) {
                    // 1) Если этот плейлист сейчас играет — останавливаем ЕГО существующим
                    //    путём сервиса/координатора. Сервис не запускается.
                    SoundPlaybackService.notifyPlaylistDeleted(trackUris)
                    // 2) Отменяем незавершённую загрузку и ДОЖИДАЕМСЯ её остановки,
                    //    чтобы она не опубликовала файл уже после удаления. Для копии
                    //    каталога отменяем и загрузку, запущенную из read-only экрана
                    //    под отрицательным ключом: она пишет те же файлы.
                    downloads.cancelPlaylist(playlistId)
                    playlist?.catalogId?.let { catalogId ->
                        downloads.cancelPlaylist(PlaylistDownloadKeys.catalogPlaylist(catalogId))
                    }
                    // 3) Файлы удаляем только у треков, которых больше нет в плейлистах,
                    //    и которые НЕ были скачаны по одному в библиотеку (Stage 2):
                    //    самостоятельный скачанный трек остаётся доступным и после
                    //    удаления плейлиста.
                    val sharedUris = try {
                        playlists.referencedTrackUris(trackUris)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось проверить общие треки плейлиста: ${e.message}")
                        trackUris
                    }
                    val standaloneDownloadedUris = try {
                        trackDao.getUrisBySource(TrackSource.DOWNLOADED.value).toSet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось прочитать отдельно скачанные треки: ${e.message}")
                        emptySet()
                    }
                    // Внутри менеджера каждый файл удаляется независимо, результат
                    // проверяется и провалившиеся файлы повторяются ещё раз.
                    downloads.deleteDownloadedFiles(
                        trackUris - sharedUris - standaloneDownloadedUris
                    )
                    Log.i(
                        TAG,
                        "Плейлист id=$playlistId удалён: треков ${trackUris.size}, " +
                            "общих с другими плейлистами ${sharedUris.size}, " +
                            "скачанных по одному ${standaloneDownloadedUris.size}"
                    )
                    _deleteEvent.value = PlaylistDeleteEvent(
                        eventId = ++deleteEventCounter,
                        playlistId = playlistId,
                        trackUris = trackUris
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Никогда не роняем приложение из-за удаления: пишем лог.
                Log.e(TAG, "Ошибка удаления плейлиста id=$playlistId: ${e.message}", e)
            }
        }
    }

    fun consumeDeleteEvent() {
        _deleteEvent.value = null
    }

    /**
     * «Повторить» после ошибки загрузки (значок ошибки на карточке карусели):
     * повторно запускает скачивание отсутствующих файлов того же плейлиста.
     * Уже скачанные треки пропускаются менеджером, ошибки не бросаются в UI.
     */
    fun retryDownload(playlistId: Long) {
        viewModelScope.launch {
            try {
                val uris = playlists.getTracksOnce(playlistId).map { it.trackUri }
                Log.i(TAG, "Повторная загрузка плейлиста id=$playlistId: треков ${uris.size}")
                downloads.startPlaylistDownload(playlistId, uris)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось повторить загрузку плейлиста id=$playlistId: ${e.message}")
            }
        }
    }

    /**
     * Добавляет трек в пользовательский плейлист.
     * @return true, если трек добавлен; false — дубликат или плейлист не найден.
     */
    suspend fun addTrackToPlaylist(playlistId: Long, track: PlaylistTrackInput): Boolean {
        return playlists.addTrackToPlaylist(playlistId, track)
    }

    /**
     * «+ Новый плейлист» в режиме добавления: имя, плейлист и трек — одной
     * транзакцией [PlaylistRepository.createPlaylistWithTracks].
     * @return id нового плейлиста или [PlaylistRepository.INVALID_PLAYLIST_ID].
     */
    suspend fun createPlaylistWithTrack(name: String, track: PlaylistTrackInput): Long {
        return playlists.createPlaylistWithTracks(name, listOf(track))
    }

    /**
     * «+ Новый плейлист» в режиме перемещения: создаёт плейлист и сразу
     * переносит в него трек. Если перенос не удался, пустой плейлист удаляется,
     * чтобы не оставлять мусор.
     * @return id нового плейлиста или [PlaylistRepository.INVALID_PLAYLIST_ID].
     */
    suspend fun createPlaylistWithMovedTrack(
        name: String,
        trackUri: String,
        fromPlaylistId: Long
    ): Long {
        val newPlaylistId = playlists.createPlaylist(name)
        if (newPlaylistId == PlaylistRepository.INVALID_PLAYLIST_ID) {
            Log.w(TAG, "Новый плейлист не создан: имя отклонено валидацией")
            return PlaylistRepository.INVALID_PLAYLIST_ID
        }
        if (!playlists.moveTrack(trackUri, fromPlaylistId, newPlaylistId)) {
            Log.w(
                TAG,
                "Трек не перенесён в новый плейлист id=$newPlaylistId: удаляю пустой плейлист"
            )
            playlists.deletePlaylist(newPlaylistId)
            return PlaylistRepository.INVALID_PLAYLIST_ID
        }
        return newPlaylistId
    }

    /** «Переместить в…»: одна транзакция `moveTrack`; false — связи в источнике нет. */
    suspend fun moveTrack(trackUri: String, fromPlaylistId: Long, toPlaylistId: Long): Boolean {
        return playlists.moveTrack(trackUri, fromPlaylistId, toPlaylistId)
    }

    private companion object {
        const val TAG = "PlaylistsViewModel"
    }
}

/**
 * Одноразовое событие «плейлист удалён»: по нему «Музыка» показывает Snackbar.
 * К этому моменту БД и скачанные файлы уже удалены, воспроизведение остановлено.
 */
data class PlaylistDeleteEvent(
    val eventId: Long,
    val playlistId: Long,
    val trackUris: Set<String>
)
