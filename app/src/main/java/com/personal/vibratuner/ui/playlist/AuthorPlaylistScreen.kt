package com.personal.vibratuner.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.data.remote.AuthorManifestPlaylist
import com.personal.vibratuner.ui.components.TrackRowAction
import com.personal.vibratuner.ui.components.TrackRowContext
import com.personal.vibratuner.ui.components.rememberDownloadedTrackUris
import com.personal.vibratuner.ui.music.MusicViewModel
import com.personal.vibratuner.ui.schedule.AlarmEditDialog
import com.personal.vibratuner.ui.schedule.CatalogPlaylistRef
import com.personal.vibratuner.ui.schedule.ScheduleViewModel
import com.personal.vibratuner.ui.components.rememberSoundPreview
import kotlinx.coroutines.launch

/**
 * Экран сетевого плейлиста «От автора» (этап 4).
 *
 * Переиспользует ровно тот же [PlaylistContent], что и локальный плейлист,
 * в режиме `readOnly`: нет ♥, меню «⋮» и «Запланировать», тап по треку
 * запускает плейлист с этого трека. Воспроизведение — существующий
 * список-очередь сервиса ([SoundPlaybackService.playQueue]), треки — https.
 */
@Composable
fun AuthorPlaylistScreenRoute(
    authorPlaylistId: String,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    playerViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorPlaylistsViewModel = viewModel(),
    scheduleViewModel: ScheduleViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val playlist = state.playlists.firstOrNull { it.id == authorPlaylistId }
    // Stage 2: отдельные загрузки треков — из менеджера приложения (индикатор
    // переживает рекомпозицию и повторный вход), «скачано» — по наличию файла.
    val app = LocalContext.current.applicationContext as VibraTunerApplication
    val downloadManager = app.trackDownloads
    val singleTrackDownloads by downloadManager.trackStates.collectAsState()
    // Stage 3: изменение набора скачанных файлов — повод дочитать длительности.
    val downloadGeneration by downloadManager.fileGeneration.collectAsState()
    val downloadedTrackUris = rememberDownloadedTrackUris(
        downloads = downloadManager,
        urls = remember(playlist) { playlist?.tracks?.map { it.url } ?: emptyList() }
    )
    // Признак «уже добавлен в библиотеку»: у добавленного плейлиста баннера нет.
    val addedPlaylistIds by viewModel.addedPlaylistIds.collectAsState()
    // Stage 3: длительности скачанных файлов этого плейлиста — для строк, где
    // в манифесте нет `duration`. Читается в фоне только для открытого плейлиста.
    val localDurations by viewModel.localDurations.collectAsState()
    LaunchedEffect(playlist?.id, downloadGeneration) {
        playlist?.let { target -> viewModel.resolveDurations(target.tracks.map { it.url }) }
    }
    // catalogId -> id локальной копии: у добавленного плейлиста загрузка идёт
    // под ключом библиотеки (общий прогресс с сеткой «Моей коллекции»).
    val catalogLocalIds by viewModel.catalogLocalIds.collectAsState()
    // Ручное скачивание: состояния по ключу задания (см. PlaylistDownloadKeys).
    val downloadStates by viewModel.downloadStates.collectAsState()
    // Результат «+» / «Добавить к себе» — тот же одноразовый Snackbar-паттерн,
    // что и у карточки в «Каталоге» (событие формирует ViewModel).
    val copyEvent by viewModel.copyEvent.collectAsState()
    val copiedSnackbarFormat = stringResource(R.string.author_copied_snackbar)
    val copyFailedMessage = stringResource(R.string.author_copy_failed_snackbar)
    // Текущий трек и play/pause — из единственного `MusicViewModel`
    // (serviceState сервиса/координатора); второй источник не создаётся.
    val playerState by playerViewModel.uiState.collectAsState()
    // Одноразовое событие от тела мини-плеера: прокрутка к играющему треку.
    var scrollToTrackRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(playerViewModel) {
        playerViewModel.scrollToActiveTrack.collect { scrollToTrackRequest++ }
    }
    val scope = rememberCoroutineScope()
    // ОДИН нижний лист выбора плейлиста на read-only экран «От автора».
    // Удалённый трек здесь можно только добавить в локальный плейлист.
    var pickerRequest by remember { mutableStateOf<PlaylistPickerRequest?>(null) }
    val missingMessage = stringResource(R.string.author_playlist_missing)
    // Part 3: ЕДИНЫЙ текст ошибки обновления каталога «От автора».
    val errorMessage = stringResource(R.string.error_connection_common)

    // «Запланировать» у плейлиста каталога: тот же единый AlarmEditDialog,
    // плейлист уже выбран и НЕ добавляется в библиотеку и НЕ скачивается.
    val alarmTracks by scheduleViewModel.tracks.collectAsState()
    val alarmPlaylists by scheduleViewModel.playlistList.collectAsState()
    var showAlarmDialog by remember { mutableStateOf(false) }
    val alarmCreatedMessage = stringResource(R.string.alarm_created_snackbar)

    // Ключ задания скачивания: у добавленной копии — id локального плейлиста
    // (общий прогресс с сеткой «Моей коллекции»), иначе — ключ каталога.
    val downloadKey = playlist?.let { target ->
        val localId = catalogLocalIds[target.id]
        if (localId != null) {
            PlaylistDownloadKeys.libraryPlaylist(localId)
        } else {
            PlaylistDownloadKeys.catalogPlaylist(target.id)
        }
    }
    val downloadState = downloadKey?.let { downloadStates[it] }

    // Системная кнопка «Назад» возвращает к вкладкам, а не закрывает приложение.
    BackHandler(onBack = onBack)

    // Экран мог быть восстановлен после смерти процесса: данных ещё нет.
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    // «Добавить» в баннере: плейлист добавляется в библиотеку ViewModel (без
    // скачивания), здесь только показываем результат и потребляем событие.
    LaunchedEffect(copyEvent?.eventId) {
        val event = copyEvent ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(
                message = if (event.success) {
                    copiedSnackbarFormat.format(event.playlistName)
                } else {
                    copyFailedMessage
                },
                duration = SnackbarDuration.Short
            )
        } finally {
            viewModel.consumeCopyEvent()
        }
    }

    LaunchedEffect(state.hasLoaded, state.loadFailed, playlist?.id) {
        val message = when {
            state.hasLoaded && playlist == null -> missingMessage
            state.loadFailed && playlist == null -> errorMessage
            else -> null
        }
        if (message != null) {
            scope.launch {
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            }
            onBack()
        }
    }

    val screenState = playlist?.let { authorPlaylist ->
        authorPlaylist.toScreenState(
            showAddReminder = authorPlaylist.id !in addedPlaylistIds,
            localDurations = localDurations
        )
    } ?: PlaylistScreenState(readOnly = true, isLoading = !state.hasLoaded)

    PlaylistContent(
        state = screenState,
        displayTitle = playlist?.name.orEmpty(),
        contentPadding = contentPadding,
        onBack = onBack,
        // «Запланировать»: плейлист каталога планируется напрямую, без добавления
        // в «Мою коллекцию» и без скачивания. Сохраняется стабильный catalogId.
        onSchedule = if (playlist != null) {
            { showAlarmDialog = true }
        } else {
            null
        },
        // Ручное скачивание доступно и плейлисту, которого нет в библиотеке.
        onDownloadClick = playlist?.let { target -> { viewModel.downloadCatalogPlaylist(target) } },
        onCancelDownloadClick = playlist?.let { target ->
            { viewModel.cancelCatalogDownload(target) }
        },
        // Part 3, задача 2: удаление скачанных файлов плейлиста каталога
        // (подтверждение — в `PlaylistContent`); библиотека и треки не меняются.
        onDeleteDownloadedClick = playlist?.let { target ->
            { viewModel.deleteDownloadedPlaylist(target) }
        },
        downloadState = downloadState,
        // Stage 2: отдельная загрузка одного трека — меню строки и индикатор.
        // Плейлист в библиотеку не добавляется, загрузка плейлиста не стартует.
        downloadedTrackUris = downloadedTrackUris,
        singleTrackDownloads = singleTrackDownloads,
        onTrackDownload = viewModel::downloadTrack,
        // Stage 3: кнопка «Добавить» живёт ТОЛЬКО в баннере, пока копии каталога
        // нет в библиотеке. Нажатие вызывает ТОТ ЖЕ addPlaylistToLibrary, что и
        // «Добавить к себе» в «Каталоге»: копия с catalogId и треками одной
        // транзакцией, без скачивания. Верхняя панель — только «Назад» и заголовок.
        onAddToLibraryClick = playlist
            ?.takeIf { it.id !in addedPlaylistIds }
            ?.let { target -> { viewModel.addPlaylistToLibrary(target) } },
        onTrackClick = { track ->
            // Тап по активному треку — существующая команда play/pause.
            if (playerState.currentTrack?.uri == track.trackUri) {
                playerViewModel.onPlayPauseClick()
            } else {
                playlist?.let { viewModel.playAuthorPlaylist(it, startUrl = track.trackUri) }
            }
        },
        onFavoriteToggle = {},
        // «⋮» у «От автора»: только «Добавить в плейлист…» (каталог, не плейлист)
        // и «Скачать» одного трека (Stage 2).
        trackMenuContext = TrackRowContext.Catalog,
        onTrackMenuAction = { track, action ->
            when (action) {
                TrackRowAction.ADD_TO_PLAYLIST -> {
                    // https-URI трека сохраняется как есть — решение части 1.
                    pickerRequest = PlaylistPickerRequest(
                        mode = PlaylistPickerMode.ADD,
                        track = track.toPlaylistInput()
                    )
                }

                TrackRowAction.DOWNLOAD_TRACK -> viewModel.downloadTrack(track)
                // Part 3, задача 1: только скачанный приложением файл; трек
                // остаётся в сетевом плейлисте и играет из сети.
                TrackRowAction.DELETE_DOWNLOADED -> viewModel.deleteDownloadedTrack(track)
                else -> Unit
            }
        },
        activeTrackUri = playerState.currentTrack?.uri,
        activeTrackIsPlaying = playerState.isPlaying,
        scrollToActiveTrackRequest = scrollToTrackRequest,
        modifier = modifier
    )

    // «Запланировать»: тот же единый редактор, плейлист каталога уже выбран.
    // В библиотеку он при этом НЕ добавляется и НЕ скачивается.
    // Предпрослушивание в окне выбора мелодии — через тот же координатор сервиса.
    val soundPreview = rememberSoundPreview(playerViewModel)
    if (showAlarmDialog && playlist != null) {
        AlarmEditDialog(
            initialAlarm = null,
            tracks = alarmTracks,
            playlists = alarmPlaylists,
            initialSource = AlarmSoundSource.PLAYLIST,
            initialCatalogPlaylist = CatalogPlaylistRef(
                catalogId = playlist.id,
                name = playlist.name,
                trackCount = playlist.tracks.size
            ),
            preview = soundPreview,
            onImportFiles = playerViewModel::onAddFiles,
            onDismiss = { showAlarmDialog = false },
            onSave = { draft ->
                showAlarmDialog = false
                scheduleViewModel.saveAlarm(id = 0, draft = draft)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = alarmCreatedMessage,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }

    PlaylistPickerHost(
        request = pickerRequest,
        snackbarHostState = snackbarHostState,
        onDismiss = { pickerRequest = null }
    )
}

private fun AuthorManifestPlaylist.toScreenState(
    showAddReminder: Boolean,
    localDurations: Map<String, Long>
): PlaylistScreenState =
    PlaylistScreenState(
        title = name,
        description = description,
        readOnly = true,
        showAddReminder = showAddReminder,
        isLoading = false,
        tracks = tracks.map { track ->
            PlaylistTrackUi(
                trackUri = track.url,
                title = track.title,
                artist = track.artist,
                isFavorite = false,
                // Длительность: необязательное поле манифеста, а если его нет —
                // длительность уже скачанного файла (Stage 3). 0 — неизвестна,
                // тогда строка не показывает длительность и итог не считается.
                durationMs = track.durationMs.takeIf { it > 0L }
                    ?: localDurations[track.url]
                    ?: 0L
            )
        }
    )
