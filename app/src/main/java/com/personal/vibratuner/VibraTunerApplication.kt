package com.personal.vibratuner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.personal.vibratuner.audio.volume.SystemVolumeRecovery
import com.personal.vibratuner.data.download.DownloadedDurationBackfill
import com.personal.vibratuner.data.download.MediaStoreDownloadStore
import com.personal.vibratuner.data.download.TrackDownloadManager
import com.personal.vibratuner.data.download.TrackDownloadStore
import com.personal.vibratuner.data.local.datastore.DataStoreEqualizerSettingsStore
import com.personal.vibratuner.data.local.datastore.DataStoreSavedFolderStore
import com.personal.vibratuner.data.local.datastore.MusicPreferencesDataStore
import com.personal.vibratuner.data.local.db.AppDatabase
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackSource
import com.personal.vibratuner.data.local.saf.FolderImportSync
import com.personal.vibratuner.data.local.saf.SafSavedFolderScanner
import com.personal.vibratuner.data.local.saf.TrackImportInserter
import com.personal.vibratuner.data.remote.AuthorManifestRepository
import com.personal.vibratuner.data.remote.PlaylistIconCache
import com.personal.vibratuner.data.remote.PlaylistIconFiles
import com.personal.vibratuner.data.repository.EqualizerRepository
import com.personal.vibratuner.data.repository.PlaylistRepository
import com.personal.vibratuner.data.repository.UpdateRepository
import com.personal.vibratuner.data.remote.UpdateChecker
import com.personal.vibratuner.service.SoundPlaybackService
import com.personal.vibratuner.core.config.AppConfig
import com.personal.vibratuner.core.config.AppVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class VibraTunerApplication : Application() {

    lateinit var database: AppDatabase
        private set

    /**
     * Скоуп уровня приложения: действия, которые НЕ должны отменяться при
     * закрытии экрана или уничтожении ViewModel (очистка скачанных файлов
     * удалённого плейлиста). Живёт столько же, сколько процесс.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var playlistRepository: PlaylistRepository
        private set

    lateinit var preferencesDataStore: MusicPreferencesDataStore
        private set

    /**
     * Настройки эквалайзера (Part 1). Один репозиторий на приложение; значения
     * хранятся в том же `DataStore<Preferences>`, что и остальные настройки.
     */
    lateinit var equalizerRepository: EqualizerRepository
        private set

    /**
     * Проверка обновлений по GitHub Releases (часть 1: данные и логика, без UI).
     * Один репозиторий на приложение: его наблюдает UI («Настройки» и, с части 2,
     * нижняя навигация), стартовую проверку запускает `onCreate`.
     */
    lateinit var updateRepository: UpdateRepository
        private set

    /**
     * Автоматическое дочитывание новых файлов из сохранённой папки импорта
     * (SAF tree-URI в существующем DataStore). Один экземпляр на приложение:
     * его дергают MainActivity (старт/возврат в foreground) и экран «Звуки».
     */
    lateinit var folderImport: FolderImportSync
        private set

    /**
     * Скачивание треков добавленных плейлистов (часть 3). Один менеджер на
     * приложение: сервис берёт из него локальный файл для воспроизведения,
     * UI — состояние загрузки.
     */
    lateinit var trackDownloads: TrackDownloadManager
        private set

    /**
     * Stage 3: ленивое дозаполнение длительности уже скачанных треков из их
     * локальных файлов. Один проход при старте процесса, в фоне; UI не ждёт.
     */
    lateinit var downloadedDurationBackfill: DownloadedDurationBackfill
        private set

    /**
     * Кеш PNG-иконок каталога «От автора» (Stage 2): файлы в
     * `cacheDir/playlist_icons`, скачиваются после обновления каталога.
     * Один на приложение; UI читает StateFlow. Сервис и координатор
     * воспроизведения иконки НЕ используют.
     */
    lateinit var playlistIcons: PlaylistIconCache
        private set

    /**
     * Манифест каталога «От автора»: сеть и кеш `filesDir/author_manifest.json`.
     * Один экземпляр на приложение — его используют вкладка «Каталог», экран
     * расписания (имена плейлистов каталога) и сервис воспроизведения
     * (треки будильника-плейлиста каталога, только чтение кеша).
     */
    val authorManifest: AuthorManifestRepository by lazy {
        AuthorManifestRepository(
            manifestUrl = AppConfig.AUTHOR_MANIFEST_URL,
            cacheFile = File(filesDir, AuthorManifestRepository.CACHE_FILE_NAME)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        playlistRepository = PlaylistRepository(database.playlistDao(), database.alarmDao())
        preferencesDataStore = MusicPreferencesDataStore(this)
        equalizerRepository = EqualizerRepository(DataStoreEqualizerSettingsStore(this))
        // Часть 1 проверки обновлений: данные GitHub Releases в существующем
        // DataStore. WorkManager в проекте нет, поэтому «раз в сутки» проверяется
        // при старте процесса (один раз); сервис, AlarmManager и BOOT_COMPLETED
        // в этом не участвуют.
        updateRepository = UpdateRepository(
            preferences = preferencesDataStore,
            checker = UpdateChecker(),
            installedVersionName = { AppVersion.currentName(this) },
            scope = appScope
        )
        updateRepository.checkOnAppStartIfDue()
        trackDownloads = TrackDownloadManager(
            store = TrackDownloadStore(File(filesDir, TrackDownloadStore.DIRECTORY_NAME)),
            // Новые файлы — в общую папку Download/VibraTuner через MediaStore.
            publicStore = MediaStoreDownloadStore(this),
            // Stage 2: трек, скачанный по ОДНОМУ, становится самостоятельной
            // строкой библиотеки. URI — тот же https-адрес, поэтому файл не
            // дублируется, а воспроизведение находит локальную копию по URL.
            // Дубликат по уникальному URI игнорируется (id = -1).
            onTrackDownloaded = { track ->
                val dao = database.trackDao()
                val ids = dao.insertTracks(
                    listOf(
                        TrackEntity(
                            uri = track.url,
                            title = track.title,
                            artist = track.artist.orEmpty(),
                            durationMs = track.durationMs,
                            source = TrackSource.DOWNLOADED.value
                        )
                    )
                )
                if (ids.firstOrNull() == -1L) {
                    // Stage 3: строка уже есть (повторная загрузка). Длительность
                    // дозаполняется, если её не было: повторный файл не создаёт дубль.
                    if (track.durationMs > 0L) {
                        dao.updateDurationIfMissing(track.url, track.durationMs)
                    }
                    Log.i(TAG, "Скачанный трек уже есть в библиотеке: ${track.url}")
                }
            },
            // Part 3: файлы скачанных треков удалены из памяти. Если играет
            // именно этот трек, живой сервис остановит его существующим путём
            // координатора; сервис НЕ запускается, второго пути нет.
            onDownloadedFilesDeleted = { uris ->
                SoundPlaybackService.notifyDownloadedFilesDeleted(uris)
            }
        )
        playlistIcons = PlaylistIconCache(File(cacheDir, PlaylistIconFiles.DIRECTORY_NAME))
        // Stage 3: ленивое дозаполнение длительности уже скачанных треков.
        // Один проход в фоне при старте процесса; ошибки только логируются.
        downloadedDurationBackfill = DownloadedDurationBackfill(
            trackDao = database.trackDao(),
            durationMsOf = { url -> trackDownloads.localDurationMs(url) },
            logger = { message, error ->
                if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
            }
        )
        appScope.launch(Dispatchers.IO) {
            try {
                downloadedDurationBackfill.runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Дозаполнение длительностей прервано: ${e.message}")
            }
        }
        folderImport = FolderImportSync(
            savedFolder = DataStoreSavedFolderStore(preferencesDataStore),
            scanner = SafSavedFolderScanner(this),
            inserter = TrackImportInserter(database.trackDao()),
            logger = { message, error ->
                if (error != null) {
                    Log.w(TAG, message, error)
                } else {
                    Log.i(TAG, message)
                }
            }
        )
        createNotificationChannel()
        // Часть 3: если предыдущий процесс умер, не вернув системную громкость,
        // восстанавливаем сохранённый исходный уровень при старте приложения.
        // Живой сервисный контроллер создаётся позже и продолжит подъём сам.
        SystemVolumeRecovery.recoverIfPendingInBackground(this)
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.notification_channel_name)
        val descriptionText = getString(R.string.notification_channel_desc)
        // Единственный канал воспроизведения: тихий, без звука, вибрации и бейджа.
        // IMPORTANCE_LOW не показывает heads-up. Поведение канала нельзя изменить
        // после создания, поэтому «тихие» флаги задаются сразу при установке.
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sound_playback_channel"

        /** Тег логов приложения (сохранение скачанных треков в библиотеку). */
        private const val TAG = "VibraTunerApplication"

        lateinit var instance: VibraTunerApplication
            private set
    }
}
