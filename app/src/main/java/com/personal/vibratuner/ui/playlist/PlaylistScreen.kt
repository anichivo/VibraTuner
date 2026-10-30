package com.personal.vibratuner.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.download.PlaylistDownloadKeys
import com.personal.vibratuner.data.download.PlaylistDownloadState
import com.personal.vibratuner.data.download.SingleTrackDownloadState
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppConfirmDialog
import com.personal.vibratuner.ui.components.AppMenuItem
import com.personal.vibratuner.ui.components.AppOutlinedButton
import com.personal.vibratuner.ui.components.AppOverflowMenuButton
import com.personal.vibratuner.ui.components.AppPrimaryButton
import com.personal.vibratuner.ui.components.EmptyStateView
import com.personal.vibratuner.ui.components.FavoriteToggleButton
import com.personal.vibratuner.ui.components.PlaylistDescription
import com.personal.vibratuner.ui.components.TrackDownloadedMark
import com.personal.vibratuner.ui.components.TrackDownloadProgress
import com.personal.vibratuner.ui.components.TrackDownloadStatus
import com.personal.vibratuner.ui.components.TrackPlaybackIndicator
import com.personal.vibratuner.ui.components.TrackRowAction
import com.personal.vibratuner.ui.components.TrackRowContext
import com.personal.vibratuner.ui.components.TrackRowMenu
import com.personal.vibratuner.ui.components.durationMinutesRoundedUp
import com.personal.vibratuner.ui.components.formatDurationWords
import com.personal.vibratuner.ui.components.formatTrackDuration
import com.personal.vibratuner.ui.components.rememberDownloadedTrackUris
import com.personal.vibratuner.ui.components.singleTrackDownloadStatus
import com.personal.vibratuner.ui.music.MusicViewModel
import com.personal.vibratuner.ui.schedule.AlarmEditDialog
import com.personal.vibratuner.ui.schedule.ScheduleViewModel
import com.personal.vibratuner.ui.components.rememberSoundPreview
import kotlinx.coroutines.launch

/**
 * Маршрут экрана плейлиста: аргумент — [playlistId] (id строки из `playlists`).
 * Этап 4 переиспользует [PlaylistContent] с readOnly-состоянием для «От автора».
 */
@Composable
fun PlaylistScreenRoute(
    playlistId: Long,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    playerViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scheduleViewModel: ScheduleViewModel = viewModel()
) {
    val app = LocalContext.current.applicationContext as VibraTunerApplication
    val downloadManager = app.trackDownloads
    val viewModel: PlaylistViewModel = viewModel(
        key = "playlist-$playlistId",
        factory = viewModelFactory {
            initializer { PlaylistViewModel(app, playlistId) }
        }
    )
    val state by viewModel.state.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    // Stage 2: отдельные загрузки треков живут в менеджере приложения, поэтому
    // индикатор в строке переживает рекомпозицию и повторный вход на экран.
    val singleTrackDownloads by downloadManager.trackStates.collectAsState()
    val downloadedTrackUris = rememberDownloadedTrackUris(
        downloads = downloadManager,
        urls = state.tracks.map { it.trackUri }
    )
    // Текущий трек и play/pause — из единственного `MusicViewModel`
    // (serviceState сервиса/координатора); второй источник не создаётся.
    val playerState by playerViewModel.uiState.collectAsState()
    // Одноразовое событие от тела мини-плеера: прокрутка к играющему треку.
    var scrollToTrackRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(playerViewModel) {
        playerViewModel.scrollToActiveTrack.collect { scrollToTrackRequest++ }
    }
    val favoritesTitle = stringResource(R.string.playlist_favorites_name)
    val displayTitle = if (state.isSystem) favoritesTitle else state.title.orEmpty()

    val scope = rememberCoroutineScope()
    // ОДИН нижний лист выбора плейлиста на экран плейлиста.
    var pickerRequest by remember { mutableStateOf<PlaylistPickerRequest?>(null) }
    val removedMessage = stringResource(R.string.playlist_removed_snackbar)
    val deletedMessage = stringResource(R.string.playlist_deleted_snackbar)

    // «Запланировать»: тот же единый редактор будильника, плейлист уже выбран.
    val alarmTracks by scheduleViewModel.tracks.collectAsState()
    val playlists by scheduleViewModel.playlistList.collectAsState()
    var showAlarmDialog by rememberSaveable { mutableStateOf(false) }
    val alarmCreatedMessage = stringResource(R.string.alarm_created_snackbar)

    // «Переименовать» в меню шапки: только локальный несистемный плейлист,
    // в т.ч. копия каталога «От автора» (у неё `catalogId`, имя — отдельная колонка).
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    val canRename = !state.readOnly && !state.isSystem && !state.isLoading && !state.isMissing

    // Системная кнопка «Назад» возвращает к вкладкам, а не закрывает приложение.
    BackHandler(onBack = onBack)

    LaunchedEffect(state.isMissing) {
        if (state.isMissing) {
            snackbarHostState.showSnackbar(
                message = deletedMessage,
                duration = SnackbarDuration.Short
            )
            onBack()
        }
    }

    PlaylistContent(
        state = state,
        displayTitle = displayTitle,
        contentPadding = contentPadding,
        onBack = onBack,
        onSchedule = { showAlarmDialog = true },
        // Ручное скачивание: отдельная кнопка, не связанная с добавлением
        // плейлиста в библиотеку (оно давно ничего не скачивает).
        onDownloadClick = viewModel::downloadPlaylist,
        onCancelDownloadClick = viewModel::cancelDownload,
        // Part 3, задача 2: удаление скачанных файлов всего плейлиста
        // (подтверждение — в `PlaylistContent`, кнопка меняет только подпись).
        onDeleteDownloadedClick = viewModel::deleteDownloadedPlaylist,
        downloadState = downloadState,
        // Stage 2: отдельная загрузка трека каталога — меню строки и индикатор.
        downloadedTrackUris = downloadedTrackUris,
        singleTrackDownloads = singleTrackDownloads,
        onTrackDownload = viewModel::downloadTrack,
        onRenameClick = if (canRename) {
            { showRenameDialog = true }
        } else {
            null
        },
        onTrackClick = { track ->
            // Тап по активному треку — существующая команда play/pause.
            if (playerState.currentTrack?.uri == track.trackUri) {
                playerViewModel.onPlayPauseClick()
            } else {
                viewModel.onTrackClick(track)
            }
        },
        onFavoriteToggle = viewModel::onFavoriteToggle,
        // «⋮» есть у ВСЕХ списков треков, включая «Любимое»: контекст InPlaylist
        // даёт добавить/переместить/удалить связь с плейлистом.
        trackMenuContext = TrackRowContext.InPlaylist(playlistId),
        onTrackMenuAction = { track, action ->
            when (action) {
                TrackRowAction.ADD_TO_PLAYLIST -> {
                    pickerRequest = PlaylistPickerRequest(
                        mode = PlaylistPickerMode.ADD,
                        track = track.toPlaylistInput()
                    )
                }

                TrackRowAction.MOVE_TO_PLAYLIST -> {
                    pickerRequest = PlaylistPickerRequest(
                        mode = PlaylistPickerMode.MOVE,
                        track = track.toPlaylistInput(),
                        fromPlaylistId = playlistId
                    )
                }

                // Удаление ТОЛЬКО связи: без подтверждения и без удаления файла.
                TrackRowAction.REMOVE_FROM_PLAYLIST -> {
                    viewModel.onRemoveTrack(track, playerState.currentTrack?.uri)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = removedMessage,
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Stage 2: скачивается только этот трек (не плейлист целиком).
                TrackRowAction.DOWNLOAD_TRACK -> viewModel.downloadTrack(track)

                // Part 3, задача 1: удаляется ТОЛЬКО скачанный файл одного трека;
                // связь с плейлистом и строка библиотеки остаются.
                TrackRowAction.DELETE_DOWNLOADED -> viewModel.deleteDownloadedTrack(track)

                // Удаление из библиотеки доступно только в «Моей медиатеке».
                TrackRowAction.DELETE_TRACK -> Unit
            }
        },
        activeTrackUri = playerState.currentTrack?.uri,
        activeTrackIsPlaying = playerState.isPlaying,
        scrollToActiveTrackRequest = scrollToTrackRequest,
        modifier = modifier
    )

    // Предпрослушивание в окне выбора мелодии идёт через тот же координатор
    // сервиса (привязка уже существующего playerViewModel, второго плеера нет).
    val soundPreview = rememberSoundPreview(playerViewModel)
    if (showAlarmDialog) {
        AlarmEditDialog(
            initialAlarm = null,
            tracks = alarmTracks,
            playlists = playlists,
            initialSource = AlarmSoundSource.PLAYLIST,
            initialPlaylistId = playlistId,
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

    // Переименование: тот же единый `PlaylistNameDialog`, что и создание.
    // Диалог валидирует имя и учитывает IME; в БД пишется только `name`.
    if (showRenameDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            initialName = state.title.orEmpty(),
            confirmLabel = stringResource(R.string.playlist_rename_action),
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                showRenameDialog = false
                viewModel.renamePlaylist(name)
            }
        )
    }

    PlaylistPickerHost(
        request = pickerRequest,
        snackbarHostState = snackbarHostState,
        onDismiss = { pickerRequest = null }
    )
}

/**
 * Содержимое экрана плейлиста, управляемое ТОЛЬКО [PlaylistScreenState]:
 * без Room-запросов и без ViewModel. Заголовок передаётся готовой строкой,
 * чтобы локализованное «Любимое» не зависело от БД.
 */
@Composable
fun PlaylistContent(
    state: PlaylistScreenState,
    displayTitle: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /**
     * «Запланировать» в шапке. null — кнопки нет. Плейлист каталога «От автора»
     * планируется так же, как локальный: добавлять его в «Мою коллекцию» и
     * скачивать для этого не нужно.
     */
    onSchedule: (() -> Unit)? = null,
    /**
     * «Скачать» для треков, которых ещё нет на устройстве. null — управления
     * нет (в плейлисте нет скачиваемых https-треков).
     */
    onDownloadClick: (() -> Unit)? = null,
    /** «Отменить загрузку» — показывается вместо «Скачать», пока идёт загрузка. */
    onCancelDownloadClick: (() -> Unit)? = null,
    /**
     * Part 3, задача 2: «Удалить из памяти» — удалить скачанные файлы ВСЕХ
     * треков плейлиста (после подтверждения). null — действия нет, кнопка
     * остаётся «Скачать».
     */
    onDeleteDownloadedClick: (() -> Unit)? = null,
    /** Состояние загрузки из единственного `TrackDownloadManager`; null — покой. */
    downloadState: PlaylistDownloadState? = null,
    /**
     * Stage 2: URL-ы треков, файлы которых уже скачаны на устройство (в том
     * числе загрузкой плейлиста). По ним меню показывает «Скачано», а строка —
     * приглушённую отметку; повторной загрузки и дублей файлов нет.
     */
    downloadedTrackUris: Set<String> = emptySet(),
    /**
     * Stage 2: идущие отдельные загрузки по URL из `TrackDownloadManager`.
     * Состояние живёт в менеджере приложения, поэтому индикатор не сбрасывается
     * при рекомпозиции и повторном входе на экран.
     */
    singleTrackDownloads: Map<String, SingleTrackDownloadState> = emptyMap(),
    /** Stage 2: «Скачать» один трек; null — действие недоступно. */
    onTrackDownload: ((PlaylistTrackUi) -> Unit)? = null,
    onTrackClick: (PlaylistTrackUi) -> Unit,
    onFavoriteToggle: (PlaylistTrackUi) -> Unit,
    /**
     * Контекст единого меню «⋮» строки трека: `InPlaylist` для локального
     * плейлиста, `Catalog` для read-only «От автора». Меню есть у КАЖДОГО
     * списка треков; скрыть его нельзя.
     */
    trackMenuContext: TrackRowContext,
    onTrackMenuAction: (PlaylistTrackUi, TrackRowAction) -> Unit = { _, _ -> },
    /**
     * «Переименовать» в шапке: null — действия нет (read-only «От автора»
     * и системное «Любимое»). Диалог открывает маршрут, а не экран.
     */
    onRenameClick: (() -> Unit)? = null,
    /**
     * «Добавить» в баннере read-only плейлиста «От автора», которого ещё нет
     * в библиотеке: маршрут вызывает существующий `addPlaylistToLibrary` с тем
     * же Snackbar. null — действия нет (плейлист уже добавлен или локальный).
     */
    onAddToLibraryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** URI текущего трека из единственного `MusicViewModel`; null — тишина. */
    activeTrackUri: String? = null,
    activeTrackIsPlaying: Boolean = false,
    /** Счётчик одноразового запроса «прокрутить к текущему треку». */
    scrollToActiveTrackRequest: Long = 0L
) {
    val dimens = AppTheme.dimens
    val bannerAnimationDurationMs = dimens.addBannerAnimationDurationMs
    val listState = rememberLazyListState()
    val latestState by rememberUpdatedState(state)
    val latestActiveTrackUri by rememberUpdatedState(activeTrackUri)

    // Управление загрузкой: «Скачать» имеет смысл только когда в плейлисте есть
    // треки, которые вообще скачиваются (https), — content:// файлы уже на устройстве.
    val canDownload = remember(state.tracks) {
        PlaylistDownloadKeys.hasDownloadableTracks(state.tracks.map { it.trackUri })
    }
    val showScheduleControl = onSchedule != null
    val showDownloadControl = onDownloadClick != null && canDownload
    val isDownloading = downloadState?.isRunning == true
    // Part 3, задача 2: «всё скачано» выводится из ФАКТИЧЕСКИХ файлов (список
    // пересчитывается по `fileGeneration` менеджера), а не из разового
    // `downloadState`. Поэтому подпись кнопки сразу меняется после завершения
    // загрузки и после удаления одного трека через меню «⋮» (задача 1).
    val allDownloaded = PlaylistDownloadKeys.allDownloadableDownloaded(
        state.tracks.map { it.trackUri },
        downloadedTrackUris
    )
    val showDeleteDownloads = allDownloaded &&
        !isDownloading &&
        onDeleteDownloadedClick != null
    // Подтверждение разрушительного удаления файлов всех треков плейлиста.
    var showDeleteDownloadsDialog by rememberSaveable { mutableStateOf(false) }

    // Stage 3: баннер «Добавьте плейлист к себе…» — ЕДИНСТВЕННОЕ место с кнопкой
    // «Добавить». Видимость берётся из РЕАЛЬНОГО признака «плейлист уже
    // в библиотеке» (`state.showAddReminder` = catalogId из
    // `observeAddedCatalogIds`), поэтому после успешного добавления баннер
    // не вернётся при повторном входе, а при ошибке останется на месте.
    // Пункт списка после скрытия остаётся нулевой высоты: так кнопки
    // «Запланировать»/«Скачать» доезжают вверх анимацией без рывка на
    // последнем кадре (удаление пункта сразу сдвинуло бы их на зазор списка).
    // При входе в уже добавленный плейлист пункта нет вовсе — вид прежний.
    var addBannerShownOnce by remember { mutableStateOf(state.showAddReminder) }
    LaunchedEffect(state.showAddReminder) {
        if (state.showAddReminder) addBannerShownOnce = true
    }
    val showAddBannerItem = state.showAddReminder || addBannerShownOnce
    val latestShowAddBannerItem by rememberUpdatedState(showAddBannerItem)

    // Тап по телу мини-плеера: плавно прокрутить список к играющему треку.
    // Если его нет в этом плейлисте — ничего не делаем (без навигации и тостов).
    LaunchedEffect(scrollToActiveTrackRequest) {
        if (scrollToActiveTrackRequest == 0L) return@LaunchedEffect
        val activeUri = latestActiveTrackUri ?: return@LaunchedEffect
        val trackIndex = latestState.tracks.indexOfFirst { it.trackUri == activeUri }
        if (trackIndex < 0) return@LaunchedEffect
        listState.animateScrollToItem(
            playlistTracksStartIndex(
                state = latestState,
                hasScheduleControl = showScheduleControl,
                hasDownloadControl = showDownloadControl,
                hasAddBanner = latestShowAddBannerItem
            ) + trackIndex
        )
    }

    // Фон экрана плейлиста (часть 4): мягкий вертикальный градиент от лёгкого
    // акцента к обычному фону. Градиент живёт в контейнере на весь экран, поэтому
    // уходит под статус-бар (edge-to-edge), а список прокручивается поверх него.
    // Ниже доли `playlistGradientEndFraction` цвет уже сплошной background.
    val background = MaterialTheme.colorScheme.background
    val backgroundBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to MaterialTheme.colorScheme.primary.copy(alpha = dimens.playlistGradientTopAlpha),
            dimens.playlistGradientEndFraction to background,
            1f to background
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimens.spacingMd),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(dimens.spacingSm)
        ) {
            item {
                Spacer(modifier = Modifier.height(dimens.spacingSm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val trackCountText = pluralStringResource(
                            R.plurals.playlist_track_count,
                            state.tracks.size,
                            state.tracks.size
                        )
                        // Итоговая длительность добавляется, только если известна
                        // у всех треков (иначе это была бы неполная сумма).
                        val totalDurationMs = playlistTotalDurationMs(state.tracks)
                        Text(
                            text = if (totalDurationMs != null) {
                                stringResource(
                                    R.string.playlist_summary_format,
                                    trackCountText,
                                    formatDurationWords(
                                        durationMinutesRoundedUp(totalDurationMs)
                                    )
                                )
                            } else {
                                trackCountText
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Меню «⋮» шапки: только «Переименовать» у локального
                    // несистемного плейлиста (включая копию из каталога).
                    if (onRenameClick != null) {
                        AppOverflowMenuButton(
                            items = listOf(
                                AppMenuItem(
                                    label = stringResource(R.string.playlist_rename_action),
                                    onClick = onRenameClick
                                )
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(dimens.spacingMd))
            }

            // Описание «От автора» — первым в шапке, ДО напоминания о добавлении.
            // Пустое/отсутствующее описание не рисует ни контейнера, ни отступа.
            val description = state.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                item {
                    PlaylistDescription(text = description)
                }
            }

            // «От автора», ещё не добавленный в библиотеку: баннер с кнопкой
            // «Добавить» внутри (текст слева, кнопка справа). Скрывается плавно —
            // затухание + уменьшение высоты — ровно тогда, когда плейлист реально
            // появился в библиотеке (признак `state.showAddReminder`).
            if (showAddBannerItem) {
                item {
                    AnimatedVisibility(
                        visible = state.showAddReminder,
                        enter = fadeIn(animationSpec = tween(bannerAnimationDurationMs)) +
                            expandVertically(animationSpec = tween(bannerAnimationDurationMs)),
                        exit = fadeOut(animationSpec = tween(bannerAnimationDurationMs)) +
                            shrinkVertically(animationSpec = tween(bannerAnimationDurationMs))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PlaylistAddBanner(
                                onAdd = { onAddToLibraryClick?.invoke() }
                            )
                            Spacer(modifier = Modifier.height(dimens.spacingSm))
                        }
                    }
                }
            }

            // «Запланировать» — единая кнопка и для локального плейлиста, и для
            // плейлиста каталога «От автора»: в библиотеку его добавлять не нужно.
            if (showScheduleControl) {
                item {
                    AppOutlinedButton(
                        text = stringResource(R.string.playlist_schedule_action),
                        onClick = { onSchedule?.invoke() },
                        icon = Icons.Default.Alarm,
                        enabled = state.tracks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(dimens.spacingSm))
                }
            }

            // Ручное скачивание: отдельная кнопка, доступная и локальному плейлисту,
            // и плейлисту каталога без копии в библиотеке. Пока идёт загрузка, та же
            // кнопка отменяет её; прогресс дублируется строкой «Скачивается… N%».
            if (showDownloadControl) {
                item {
                    AppOutlinedButton(
                        text = stringResource(
                            when {
                                isDownloading -> R.string.playlist_download_cancel_action
                                showDeleteDownloads -> R.string.download_delete_action
                                else -> R.string.playlist_download_action
                            }
                        ),
                        onClick = {
                            when {
                                isDownloading -> onCancelDownloadClick?.invoke()
                                showDeleteDownloads -> showDeleteDownloadsDialog = true
                                else -> onDownloadClick?.invoke()
                            }
                        },
                        icon = when {
                            isDownloading -> Icons.Default.Close
                            showDeleteDownloads -> Icons.Default.Delete
                            else -> Icons.Default.Download
                        },
                        enabled = state.tracks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDownloading && downloadState != null) {
                        Spacer(modifier = Modifier.height(dimens.spacingXs))
                        LinearProgressIndicator(
                            progress = { downloadState.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dimens.playlistDownloadProgressHeight)
                                .clip(RoundedCornerShape(dimens.radiusPill)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = AppTheme.controlTrack
                        )
                        Spacer(modifier = Modifier.height(dimens.spacingXs))
                        Text(
                            text = stringResource(
                                R.string.playlist_downloading_progress,
                                downloadState.progressPercent
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (allDownloaded) {
                        // Все файлы на устройстве: тихая отметка под кнопкой
                        // «Удалить из памяти» (та же строка, что и раньше).
                        Spacer(modifier = Modifier.height(dimens.spacingXs))
                        Text(
                            text = stringResource(R.string.playlist_download_done),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (downloadState?.isFailed == true) {
                        // Неполная загрузка: повтор — та же кнопка «Скачать» выше.
                        // Part 3: ЕДИНЫЙ текст ошибки скачивания из strings.xml.
                        Spacer(modifier = Modifier.height(dimens.spacingXs))
                        Text(
                            text = stringResource(R.string.error_connection_common),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(dimens.spacingSm))
                }
            }

            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dimens.spacingMd),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dimens.iconSizeLg)
                        )
                    }
                }
            }

            if (!state.isLoading && state.tracks.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = stringResource(R.string.playlist_empty_title),
                        description = if (state.readOnly) {
                            stringResource(R.string.author_playlist_empty_desc)
                        } else {
                            stringResource(R.string.playlist_empty_desc)
                        }
                    )
                }
            }

            if (!state.isLoading) {
                items(
                    items = state.tracks,
                    key = { it.trackUri }
                ) { track ->
                    // Активность — по стабильному URI из единственного serviceState.
                    val isActive = activeTrackUri != null && activeTrackUri == track.trackUri
                    // Stage 2: статус отдельной загрузки берётся из менеджера и
                    // факта наличия файла; для content:// треков он Unavailable.
                    val downloadStatus = singleTrackDownloadStatus(
                        trackUri = track.trackUri,
                        downloaded = track.trackUri in downloadedTrackUris,
                        singleStates = singleTrackDownloads,
                        canDownload = onTrackDownload != null
                    )
                    PlaylistTrackRow(
                        track = track,
                        isActive = isActive,
                        isPlaying = isActive && activeTrackIsPlaying,
                        menuContext = trackMenuContext,
                        downloadStatus = downloadStatus,
                        // У плейлистов «От автора» ♥ нет: треки не добавляются в «Любимое».
                        showFavorite = !state.readOnly,
                        onClick = { onTrackClick(track) },
                        onFavoriteToggle = { onFavoriteToggle(track) },
                        onMenuAction = { action -> onTrackMenuAction(track, action) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(dimens.spacingXl))
            }
        }
    }

    // Part 3, задача 2: удаление скачанных файлов разрушительно для хранилища
    // (сами треки остаются), поэтому только здесь есть подтверждение.
    // Для одного трека (задача 1) подтверждения нет.
    if (showDeleteDownloadsDialog) {
        AppConfirmDialog(
            title = stringResource(R.string.playlist_download_delete_title),
            message = stringResource(R.string.playlist_download_delete_message),
            confirmLabel = stringResource(R.string.download_delete_confirm),
            onConfirm = {
                showDeleteDownloadsDialog = false
                onDeleteDownloadedClick?.invoke()
            },
            onDismiss = { showDeleteDownloadsDialog = false }
        )
    }
}

/**
 * Баннер в плейлисте «От автора», которого ещё нет в библиотеке (Stage 3):
 * текст слева занимает всю оставшуюся ширину и переносится, акцентная кнопка
 * «Добавить» — справа. Кнопка — существующая [AppPrimaryButton] (акцент темы,
 * нажатое состояние, область нажатия >= 48 dp); добавление — тот же
 * `addPlaylistToLibrary`, что и у карточки «Каталога», без нового пути.
 */
@Composable
private fun PlaylistAddBanner(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.author_add_reminder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(dimens.spacingSm))
            AppPrimaryButton(
                text = stringResource(R.string.author_add_banner_action),
                onClick = onAdd,
                icon = Icons.Default.Add
            )
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: PlaylistTrackUi,
    isActive: Boolean,
    isPlaying: Boolean,
    menuContext: TrackRowContext,
    downloadStatus: TrackDownloadStatus,
    showFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMenuAction: (TrackRowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(dimens.trackIconContainerSize),
                contentAlignment = Alignment.Center
            ) {
                // «♪» у обычных строк, эквалайзер/пауза — только у текущего трека.
                TrackPlaybackIndicator(
                    isActive = isActive,
                    isPlaying = isPlaying
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Фидбек: вместо исполнителя — длительность трека на той же
                // строке и тем же стилем. Неизвестная длительность не выводится.
                val durationText = formatTrackDuration(track.durationMs)
                if (durationText != null) {
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Stage 2: пока трек качается — тот же компактный индикатор, что
                // у загрузки плейлиста; после успеха его сменяет тихая отметка.
                val running = downloadStatus as? TrackDownloadStatus.Running
                if (running != null) {
                    Spacer(modifier = Modifier.height(dimens.spacingXs))
                    TrackDownloadProgress(
                        fraction = running.progressFraction,
                        percent = running.progressPercent
                    )
                }
            }

            Spacer(modifier = Modifier.width(dimens.spacingXs))

            // «Скачано»: маленькая приглушённая иконка вместо второго текста.
            if (downloadStatus == TrackDownloadStatus.Downloaded) {
                TrackDownloadedMark()
                Spacer(modifier = Modifier.width(dimens.spacingXs))
            }

            if (showFavorite) {
                FavoriteToggleButton(
                    isFavorite = track.isFavorite,
                    onToggle = onFavoriteToggle
                )
            }

            // Единое меню строки трека: для плейлиста — «Добавить», «Переместить»,
            // «Удалить из плейлиста»; для «От автора» — только «Добавить».
            // Stage 2 добавляет «Скачать»/«Скачивается…»/«Скачано» для https-треков.
            TrackRowMenu(
                context = menuContext,
                downloadStatus = downloadStatus,
                onAction = onMenuAction
            )
        }
    }
}

/**
 * Индекс первого трека в списке плейлиста: выше идут заголовок с «назад»,
 * описание «От автора» (если оно есть), баннер «Добавьте плейлист к себе…»
 * (если он вообще показывался на этом экране, [hasAddBanner]; после добавления
 * пункт остаётся нулевой высоты, но всё равно считается), кнопка
 * «Запланировать», кнопка «Скачать», во время загрузки — ещё индикатор.
 * Нужен только для прокрутки к играющему треку из мини-плеера; порядок должен
 * совпадать с порядком `item { }` в [PlaylistContent], поэтому признаки
 * управления передаются теми же булевыми значениями, что и в разметке.
 */
private fun playlistTracksStartIndex(
    state: PlaylistScreenState,
    hasScheduleControl: Boolean,
    hasDownloadControl: Boolean,
    hasAddBanner: Boolean
): Int {
    var index = 1 // заголовок + строка с «назад»
    if (!state.description.isNullOrBlank()) index += 1
    if (hasAddBanner) index += 1
    if (hasScheduleControl) index += 1
    if (hasDownloadControl) index += 1
    if (state.isLoading) index += 1
    return index
}
