package com.personal.vibratuner.ui.music

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackSource
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.theme.Transparent
import com.personal.vibratuner.ui.components.AppConfirmDialog
import com.personal.vibratuner.ui.components.FavoriteToggleButton
import com.personal.vibratuner.ui.components.TrackPlaybackIndicator
import com.personal.vibratuner.ui.components.TrackRowAction
import com.personal.vibratuner.ui.components.TrackRowContext
import com.personal.vibratuner.ui.components.TrackRowMenu
import com.personal.vibratuner.ui.components.TrackDownloadStatus
import com.personal.vibratuner.ui.components.formatTrackDuration
import com.personal.vibratuner.ui.components.rememberDownloadedTrackUris
import com.personal.vibratuner.ui.playlist.AuthorPlaylistsSection
import com.personal.vibratuner.ui.playlist.AuthorPlaylistsViewModel
import com.personal.vibratuner.ui.playlist.PlaylistPickerHost
import com.personal.vibratuner.ui.playlist.PlaylistPickerMode
import com.personal.vibratuner.ui.playlist.PlaylistPickerRequest
import com.personal.vibratuner.ui.playlist.PlaylistsViewModel
import com.personal.vibratuner.ui.playlist.toPlaylistInput
import com.personal.vibratuner.ui.schedule.AlarmEditDialog
import com.personal.vibratuner.ui.schedule.ScheduleViewModel
import com.personal.vibratuner.ui.components.rememberSoundPreview
import kotlinx.coroutines.launch

/**
 * Две вкладки: «Моя медиатека» (сетка плейлистов + треки) и «Каталог»
 * (плейлисты «От автора» из сети). Выбранная вкладка хранится в
 * MainScreenLayout через rememberSaveable, поэтому переживает поворот
 * и переключение нижних вкладок.
 * Управление воспроизведением — в глобальном мини-плеере (MainScreenLayout);
 * большого статичного плеера на экране больше нет (Part 2).
 */
enum class MusicTab(val titleRes: Int) {
    LIBRARY(R.string.music_tab_library),
    CATALOG(R.string.music_tab_catalog)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAuthorPlaylist: (String) -> Unit,
    selectedTab: MusicTab,
    onTabSelected: (MusicTab) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicViewModel = viewModel(),
    scheduleViewModel: ScheduleViewModel = viewModel(),
    playlistsViewModel: PlaylistsViewModel = viewModel(),
    authorViewModel: AuthorPlaylistsViewModel = viewModel()
) {
    val context = LocalContext.current
    val dimens = AppTheme.dimens
    val uiState by viewModel.uiState.collectAsState()
    val favoriteUris = uiState.favoriteUris
    val scope = rememberCoroutineScope()
    // Part 3, задача 1: «скачан» выводится из фактического файла (в Room такого
    // поля нет); список пересчитывается по `fileGeneration` менеджера, поэтому
    // после удаления из памяти меню строки сразу показывает «Скачать»/скрывает
    // «Удалить из памяти».
    val downloadedLibraryUris = rememberDownloadedTrackUris(
        downloads = (context.applicationContext as VibraTunerApplication).trackDownloads,
        urls = uiState.tracks.map { it.uri }
    )

    // Открытие библиотеки «Звуки»: дочитываем новые файлы из сохранённой папки
    // импорта в фоне (без дублей, существующие записи не трогаются). Повторные
    // вызовы на старте/возврате в foreground схлопываются внутри синка.
    LaunchedEffect(Unit) {
        viewModel.onLibraryScreenOpened()
    }

    // Две страницы-подраздела: свайп синхронизирован с переключателем вкладок.
    // ЕДИНСТВЕННЫЙ источник выбранной вкладки — pagerState: TabRow, Tab и все
    // эффекты читают `currentPage`, поэтому индикатор двигается вместе со
    // свайпом и не откатывается назад, а сохранённое `selectedTab` из
    // MainScreenLayout задаёт только стартовую страницу (rememberSaveable).
    // Состояния списков подняты сюда, поэтому прокрутка каждого подраздела
    // переживает свайп и поворот экрана.
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { MusicTab.entries.size }
    )
    val selectedTabIndex = pagerState.currentPage.coerceIn(0, MusicTab.entries.lastIndex)
    val currentTab = MusicTab.entries[selectedTabIndex]
    val libraryListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    val latestOnTabSelected by rememberUpdatedState(onTabSelected)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            latestOnTabSelected(MusicTab.entries[page])
        }
    }

    val alarmCreatedMessage = stringResource(R.string.alarm_created_snackbar)
    val playlists by playlistsViewModel.playlistList.collectAsState()
    val favoritesTitle = stringResource(R.string.playlist_favorites_name)
    // Сетка «Моей медиатеки»: «Любимое» всегда первым, остальные — в порядке
    // репозитория. Ряды по две карточки собираются ниже в LazyColumn.
    val orderedPlaylists = remember(playlists) { favoritesFirst(playlists) }
    // Часть 3: состояние скачивания добавленных плейлистов (индикатор в сетке)
    // и одноразовое событие удаления для Snackbar.
    val downloadStates by playlistsViewModel.downloadStates.collectAsState()
    val playlistDeleteEvent by playlistsViewModel.deleteEvent.collectAsState()
    val alarmUsage by playlistsViewModel.alarmUsage.collectAsState()
    val playlistDeletedMessage = stringResource(R.string.playlist_deleted_snackbar)
    // Плейлист, выбранный долгим нажатием, ждёт подтверждения удаления.
    var playlistDeleteTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    // Меню «⋮» и нижний лист выбора плейлиста — ОДИН лист на экран.
    var pickerRequest by remember { mutableStateOf<PlaylistPickerRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<TrackEntity?>(null) }
    // Меню кнопки «+ Добавить» в строке «Треки»: «Выбрать файлы» |
    // «Выбрать папку целиком». Ключ по вкладке закрывает меню при переключении
    // и не открывает его заново.
    var showAddAudioMenu by remember(currentTab) { mutableStateOf(false) }

    // Stage 3: фильтр списка «Треки». Выбранный фильтр переживает поворот и
    // рекомпозицию (rememberSaveable), по умолчанию — «Все». Подсказка фильтра
    // «Скачанные» показывается ТОЛЬКО при первом открытии за всё время: флаг
    // «видел» хранится в существующем DataStore, а локальное состояние держит
    // текст на экране до ухода с фильтра (запись флага не прячет его в тот же кадр).
    var selectedTrackFilter by rememberSaveable { mutableStateOf(LibraryTrackFilter.ALL) }
    var showDownloadedFilterHint by remember { mutableStateOf(false) }
    var downloadedFilterHintRequested by remember { mutableStateOf(false) }
    val filteredTracks = remember(uiState.tracks, selectedTrackFilter) {
        filterLibraryTracks(uiState.tracks, selectedTrackFilter)
    }
    val downloadedFilterHintShown = uiState.downloadedFilterHintShown
    LaunchedEffect(selectedTrackFilter, downloadedFilterHintShown) {
        if (selectedTrackFilter != LibraryTrackFilter.DOWNLOADED) {
            showDownloadedFilterHint = false
            downloadedFilterHintRequested = false
            return@LaunchedEffect
        }
        // Флаг ещё читается из DataStore — ждём значения, чтобы не показать
        // подсказку повторно тому, кто её уже видел.
        if (downloadedFilterHintRequested || downloadedFilterHintShown == null) {
            return@LaunchedEffect
        }
        downloadedFilterHintRequested = true
        if (!downloadedFilterHintShown) {
            showDownloadedFilterHint = true
            viewModel.onDownloadedFilterHintShown()
        }
    }

    // «От автора» (этап 4): одна загрузка при первом открытии вкладки в сессии,
    // дальше — только кнопкой «обновить». Автоповторов нет.
    val authorState by authorViewModel.state.collectAsState()
    val authorAddedIds by authorViewModel.addedPlaylistIds.collectAsState()
    val authorCopyEvent by authorViewModel.copyEvent.collectAsState()
    // Stage 3: иконки каталога. `catalogIconUrls` — catalogId -> https-адрес PNG
    // из манифеста, `catalogIconFiles` — уже скачанные файлы из кеша Stage 2;
    // обе Flow перерисовывают карточки, когда иконка догрузилась.
    val catalogIconUrls by authorViewModel.catalogIconUrls.collectAsState()
    val catalogIconFiles by authorViewModel.iconFiles.collectAsState()
    val authorCopiedSnackbarFormat = stringResource(R.string.author_copied_snackbar)
    val authorCopyFailedMessage = stringResource(R.string.author_copy_failed_snackbar)

    // Stage 3: адреса иконок для копий каталога читаются из кеша манифеста
    // (без сети), поэтому уже скачанные иконки видны сразу после холодного
    // старта и в офлайне. Сеть не трогается — обновление только по кнопке.
    LaunchedEffect(Unit) {
        authorViewModel.loadCachedIconsIfNeeded()
    }

    LaunchedEffect(currentTab) {
        if (currentTab == MusicTab.CATALOG) {
            authorViewModel.loadIfNeeded()
        }
    }

    LaunchedEffect(authorCopyEvent?.eventId) {
        val event = authorCopyEvent ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(
                message = if (event.success) {
                    authorCopiedSnackbarFormat.format(event.playlistName)
                } else {
                    authorCopyFailedMessage
                },
                duration = SnackbarDuration.Short
            )
        } finally {
            authorViewModel.consumeCopyEvent()
        }
    }

    // Удаление плейлиста (часть 3): короткое сообщение. Сами данные уже удалены
    // во ViewModel, воспроизведение остановлено сервисом при необходимости.
    LaunchedEffect(playlistDeleteEvent?.eventId) {
        val event = playlistDeleteEvent ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(
                message = playlistDeletedMessage,
                duration = SnackbarDuration.Short
            )
        } finally {
            playlistsViewModel.consumeDeleteEvent()
        }
    }

    // Долгое нажатие на трек открывает тот же редактор будильника, что и «Расписание»
    var showAlarmEditor by remember { mutableStateOf(false) }
    var alarmEditorTrackId by remember { mutableStateOf<Long?>(null) }

    // SAF Лаунчеры для выбора файлов и папки
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onAddFiles(uris)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onAddFolder(treeUri)
        }
    }

    // Запрос разрешения POST_NOTIFICATIONS (Android 13+). Показывается ОДИН раз:
    // при отказе звук работает, включить разрешение можно на экране «Надёжность».
    var showNotificationPermissionRationale by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Log.i(
                "MusicScreen",
                "Разрешение на уведомления не выдано: звук работает без уведомления"
            )
        }
    }
    val dismissNotificationPermissionPrompt = {
        showNotificationPermissionRationale = false
        viewModel.onNotificationPermissionPromptHandled()
    }

    val notificationPromptShown = uiState.notificationPermissionPromptShown
    // Диалоги первого запуска показываются РАНЬШЕ запроса разрешения: пока не
    // закрыто приветствие и не приняты правила, диалог разрешения не открывается,
    // поэтому стартовые окна никогда не появляются одновременно.
    val welcomeShown by viewModel.welcomeShown.collectAsState()
    val termsAccepted by viewModel.termsAccepted.collectAsState()
    LaunchedEffect(notificationPromptShown, welcomeShown, termsAccepted) {
        if (welcomeShown == true &&
            termsAccepted == true &&
            notificationPromptShown == false &&
            PlatformCompat.canRequestNotificationPermission() &&
            !PlatformCompat.hasNotificationPermission(context)
        ) {
            showNotificationPermissionRationale = true
        }
    }

    if (showNotificationPermissionRationale) {
        AlertDialog(
            onDismissRequest = dismissNotificationPermissionPrompt,
            title = {
                Text(
                    text = stringResource(R.string.notification_permission_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.notification_permission_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissNotificationPermissionPrompt()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.btn_allow),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = dismissNotificationPermissionPrompt) {
                    Text(
                        text = stringResource(R.string.btn_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    // Итог импорта: «Пропущено …» упоминается ТОЛЬКО если реально что-то
    // пропущено; при нуле пропусков — только «Добавлено N» (без «пропущено 0»).
    val importResult = uiState.lastImportResult
    val snackbarMessage = importResult?.let { result ->
        val addedText = pluralStringResource(R.plurals.import_added_plural, result.added, result.added)
        if (result.skipped > 0) {
            val skippedText =
                pluralStringResource(R.plurals.import_skipped_plural, result.skipped, result.skipped)
            stringResource(R.string.import_result_format, addedText, skippedText)
        } else {
            addedText
        }
    }

    LaunchedEffect(importResult?.eventId) {
        if (snackbarMessage != null) {
            try {
                snackbarHostState.showSnackbar(
                    message = snackbarMessage,
                    duration = SnackbarDuration.Short
                )
            } finally {
                // Consume even when navigation cancels the suspended snackbar,
                // so the old import result is never replayed as a new event.
                viewModel.consumeImportResult()
            }
        }
    }

    // Тап по телу мини-плеера: одноразовое событие — плавно прокрутить
    // открытый список «Моей медиатеки» к играющему треку. На вкладке «Каталог»
    // списка треков нет, поэтому событие игнорируется.
    val latestTracksState by rememberUpdatedState(uiState)
    val latestPlaylists by rememberUpdatedState(playlists)
    val latestSelectedTab by rememberUpdatedState(currentTab)
    val latestTrackFilter by rememberUpdatedState(selectedTrackFilter)
    val latestDownloadedHintVisible by rememberUpdatedState(showDownloadedFilterHint)
    var scrollToTrackRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(viewModel) {
        viewModel.scrollToActiveTrack.collect { scrollToTrackRequest++ }
    }
    LaunchedEffect(scrollToTrackRequest) {
        if (scrollToTrackRequest == 0L) return@LaunchedEffect
        if (latestSelectedTab != MusicTab.LIBRARY) return@LaunchedEffect
        val activeUri = latestTracksState.currentTrack?.uri ?: return@LaunchedEffect
        // Текущий фильтр — часть списка: если трек им скрыт, прокручивать нечего.
        val visibleTracks = filterLibraryTracks(latestTracksState.tracks, latestTrackFilter)
        val trackIndex = visibleTracks.indexOfFirst { it.uri == activeUri }
        if (trackIndex < 0) return@LaunchedEffect
        libraryListState.animateScrollToItem(
            libraryTracksStartIndex(
                uiState = latestTracksState,
                playlistCount = latestPlaylists.size,
                filter = latestTrackFilter,
                visibleTrackCount = visibleTracks.size,
                downloadedHintVisible = latestDownloadedHintVisible
            ) + trackIndex
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .padding(horizontal = dimens.spacingMd)
    ) {
        Spacer(modifier = Modifier.height(dimens.spacingSm))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // Переключатель вкладок: «Моя медиатека» | «Каталог». Контейнер
        // прозрачный (без квадратной плашки), выбранная вкладка отмечена
        // скруглённым индикатором в стиле Material 3.
        PrimaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            indicator = {
                // Индикатор встроен в scope PrimaryTabRow (Material 3 1.4):
                // ширина — по содержимому вкладки, позиция анимируется самим
                // компонентом. selectedTabIndex производен от pagerState,
                // поэтому индикатор не отстаёт от свайпа. Скругление и высота —
                // только из темы.
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(
                            selectedTabIndex = selectedTabIndex,
                            matchContentSize = true
                        )
                        .fillMaxWidth()
                        .height(dimens.tabIndicatorHeight)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(dimens.radiusPill)
                        )
                )
            }
        ) {
            MusicTab.entries.forEach { tab ->
                Tab(
                    selected = tab == currentTab,
                    onClick = {
                        // Тап только анимирует pager: выбранная вкладка и
                        // индикатор остаются производными от pagerState.
                        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                    },
                    text = {
                        Text(
                            text = stringResource(tab.titleRes),
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // Свайп между подразделами. Pager — фиксированный контейнер под
        // переключателем: он получает остаток высоты через weight(1f) и
        // clipToBounds, поэтому содержимое страниц не выезжает за его границы
        // (в TabRow, под мини-плеер и нижнюю панель). Корень КАЖДОЙ страницы —
        // на весь размер, с собственным clipToBounds и непрозрачной заливкой
        // из темы, поэтому страницы с разными раскладками не перекрывают друг
        // друга во время свайпа; смещений и alpha на корне нет.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) { page ->
            when (MusicTab.entries[page]) {
                MusicTab.LIBRARY -> LazyColumn(
                    state = libraryListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingSm)
                ) {
                    // Главное содержимое вкладки — сетка плейлистов 2×N. Вертикальная
                    // прокрутка здесь ОДНА (LazyColumn); каждый ряд — пара карточек,
                    // у нечётного остатка справа пустой слот той же ширины.
                    item(key = "library_playlists_title") {
                        Text(
                            text = stringResource(R.string.library_playlists_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    items(
                        items = orderedPlaylists.chunked(2),
                        key = { row -> row.joinToString("_") { it.id.toString() } }
                    ) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm)
                        ) {
                            row.forEach { playlist ->
                                val isSystem = playlist.isSystem ||
                                    playlist.id == PlaylistEntity.FAVORITES_PLAYLIST_ID
                                // Stage 3: иконка копии каталога привязана по
                                // `catalogId` (без колонки в Room): адрес берётся
                                // из манифеста, файл — из кеша Stage 2. Нет адреса
                                // или файла — карточка покажет запасную иконку.
                                val catalogIconUrl = playlist.catalogId
                                    ?.let(catalogIconUrls::get)
                                PlaylistGridCard(
                                    title = if (isSystem) favoritesTitle else playlist.name,
                                    trackCount = playlist.trackCount,
                                    isSystem = isSystem,
                                    isCatalogCopy = !isSystem && playlist.catalogId != null,
                                    catalogIconFile = catalogIconUrl
                                        ?.let(catalogIconFiles::get),
                                    downloadState = if (isSystem) null else downloadStates[playlist.id],
                                    onClick = { onOpenPlaylist(playlist.id) },
                                    onDelete = if (isSystem) null else ({ playlistDeleteTarget = playlist }),
                                    onRetryDownload = if (isSystem) null else ({ playlistsViewModel.retryDownload(playlist.id) }),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Stage 3: заголовок «Треки» и компактная кнопка «+ Добавить»
                    // в одной строке. Кнопка открывает ПРЕЖНЕЕ меню «Выбрать файлы» /
                    // «Выбрать папку целиком» — SAF-лаунчеры и логика импорта не
                    // менялись; громоздкая карточка «Файлы с устройства» убрана.
                    item(key = "library_tracks_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = dimens.minTouchTarget),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.library_tracks_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                TextButton(
                                    onClick = { showAddAudioMenu = true },
                                    modifier = Modifier.defaultMinSize(
                                        minHeight = dimens.minTouchTarget
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.library_files_add),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                DropdownMenu(
                                    expanded = showAddAudioMenu,
                                    onDismissRequest = { showAddAudioMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.btn_choose_files),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showAddAudioMenu = false
                                            filePickerLauncher.launch(
                                                arrayOf(
                                                    "audio/*",
                                                    "application/ogg",
                                                    "application/x-flac"
                                                )
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.btn_choose_folder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showAddAudioMenu = false
                                            folderPickerLauncher.launch(null)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Stage 3: фильтр самостоятельных треков. Без иконок (в том
                    // числе без галочки у выбранного): выбор показывается только
                    // цветом и контейнером из темы. FlowRow переносит длинную
                    // подпись на крупном системном шрифте вместо обрезки.
                    item(key = "library_track_filters") {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                            verticalArrangement = Arrangement.spacedBy(dimens.spacingXs)
                        ) {
                            LibraryTrackFilter.entries.forEach { filter ->
                                val selected = filter == selectedTrackFilter
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedTrackFilter = filter },
                                    label = {
                                        Text(
                                            text = stringResource(filter.labelRes),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    },
                                    modifier = Modifier.height(dimens.minTouchTarget),
                                    shape = RoundedCornerShape(dimens.radiusPill),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = MaterialTheme.colorScheme.outline,
                                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                                        borderWidth = dimens.cardBorderWidth,
                                        selectedBorderWidth = dimens.cardBorderWidth
                                    )
                                )
                            }
                        }
                    }

                    // Подсказка о содержимом фильтра «Скачанные»: тихая, мелкая,
                    // показывается только один раз за всё время (флаг в DataStore).
                    if (selectedTrackFilter == LibraryTrackFilter.DOWNLOADED &&
                        showDownloadedFilterHint
                    ) {
                        item(key = "library_downloaded_filter_hint") {
                            Text(
                                text = stringResource(R.string.library_filter_downloaded_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Индикатор загрузки при парсинге файлов
                    if (uiState.isLoading) {
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

                    // Подсказка про долгое нажатие исчезает после первого использования
                    if (filteredTracks.isNotEmpty() && uiState.showLongPressHint) {
                        item {
                            Text(
                                text = stringResource(R.string.music_long_press_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(dimens.spacingSm))
                        }
                    }

                    // Список треков выбранного фильтра. Иконка происхождения видна
                    // ТОЛЬКО в «Все»: в «Скачанных»/«Импортированных» источник и
                    // так очевиден.
                    items(
                        items = filteredTracks,
                        key = { it.id }
                    ) { track ->
                        // Активность — по стабильному URI: то же значение используют
                        // строки плейлистов, состояние — из единственного serviceState.
                        val isActive = uiState.currentTrack?.uri == track.uri
                        TrackItemRow(
                            track = track,
                            isActive = isActive,
                            isPlaying = isActive && uiState.isPlaying,
                            isFavorite = track.uri in favoriteUris,
                            source = if (selectedTrackFilter == LibraryTrackFilter.ALL) {
                                TrackSource.fromValue(track.source)
                            } else {
                                null
                            },
                            onClick = { viewModel.onTrackRowClick(track) },
                            onLongClick = {
                                viewModel.onLongPressHintUsed()
                                alarmEditorTrackId = track.id
                                showAlarmEditor = true
                            },
                            onFavoriteClick = { viewModel.onFavoriteToggle(track) },
                            // Part 3: «Удалить из памяти» появляется только у
                            // реально скачанного файла (https + локальная
                            // копия); у импортированных файлов — никогда.
                            downloadStatus = if (track.uri in downloadedLibraryUris) {
                                TrackDownloadStatus.Downloaded
                            } else {
                                TrackDownloadStatus.Unavailable
                            },
                            onMenuAction = { action ->
                                when (action) {
                                    TrackRowAction.ADD_TO_PLAYLIST -> {
                                        pickerRequest = PlaylistPickerRequest(
                                            mode = PlaylistPickerMode.ADD,
                                            track = track.toPlaylistInput()
                                        )
                                    }

                                    TrackRowAction.DELETE_TRACK -> deleteTarget = track

                                    // Part 3, задача 1: удаляется только файл;
                                    // строка библиотеки остаётся.
                                    TrackRowAction.DELETE_DOWNLOADED ->
                                        viewModel.onDeleteDownloadedTrack(track)

                                    // Перемещение/удаление из плейлиста и одиночное
                                    // скачивание в библиотеке недоступны: здесь
                                    // самостоятельные треки библиотеки.
                                    TrackRowAction.MOVE_TO_PLAYLIST,
                                    TrackRowAction.REMOVE_FROM_PLAYLIST,
                                    TrackRowAction.DOWNLOAD_TRACK -> Unit
                                }
                            }
                        )
                    }

                    // Пустое состояние — своё для каждого фильтра, тихое и короткое.
                    if (filteredTracks.isEmpty() && !uiState.isLoading) {
                        item(key = "library_tracks_empty") {
                            Text(
                                text = stringResource(selectedTrackFilter.emptyTextRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(dimens.spacingXl))
                    }
                }

                MusicTab.CATALOG -> LazyColumn(
                    state = catalogListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingSm)
                ) {
                    // «Каталог» — плейлисты «От автора» из сети. Загрузка, ошибка
                    // и «Повторить» остались прежними (AuthorPlaylistsSection).
                    item {
                        AuthorPlaylistsSection(
                            state = authorState,
                            addedPlaylistIds = authorAddedIds,
                            iconFiles = catalogIconFiles,
                            onRefresh = authorViewModel::refresh,
                            onOpenPlaylist = { onOpenAuthorPlaylist(it.id) },
                            onAddPlaylist = authorViewModel::addPlaylistToLibrary
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(dimens.spacingXl))
                    }
                }
            }
        }
    }

    // Тот же редактор, что используется в «Расписании»: трек уже выбран.
    // Предпрослушивание в окне выбора мелодии идёт через тот же координатор
    // сервиса (существующая привязка MusicViewModel, второго плеера нет).
    val soundPreview = rememberSoundPreview(viewModel)
    if (showAlarmEditor) {
        AlarmEditDialog(
            initialAlarm = null,
            tracks = uiState.tracks,
            playlists = playlists,
            initialTrackId = alarmEditorTrackId,
            preview = soundPreview,
            onImportFiles = viewModel::onAddFiles,
            onDismiss = { showAlarmEditor = false },
            onSave = { draft ->
                scheduleViewModel.saveAlarm(id = 0, draft = draft)
                showAlarmEditor = false
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = alarmCreatedMessage,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }

    // ОДИН нижний лист выбора плейлиста на экран «Музыка»;
    // «Любимое» в списке нет — для него ♥.
    PlaylistPickerHost(
        request = pickerRequest,
        snackbarHostState = snackbarHostState,
        onDismiss = { pickerRequest = null }
    )

    // «Удалить» в меню трека: подтверждение, затем существующая логика удаления.
    // Файлы пользователя не удаляются — только запись БД и её связи.
    val trackToDelete = deleteTarget
    if (trackToDelete != null) {
        AppConfirmDialog(
            title = stringResource(R.string.track_delete_title),
            message = stringResource(R.string.track_delete_message, trackToDelete.title),
            confirmLabel = stringResource(R.string.action_delete_track),
            onConfirm = {
                deleteTarget = null
                viewModel.onDeleteTrack(trackToDelete)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // Часть 3: удаление плейлиста по долгому нажатию на карточку карусели.
    // Тот же диалог подтверждения, что и у других разрушающих действий; если
    // плейлист используют будильники — предупреждение (удаление остаётся возможным).
    // «Любимое» сюда не попадает: меню у него нет, репозиторий тоже защищает.
    val playlistToDelete = playlistDeleteTarget
    if (playlistToDelete != null) {
        val usedByAlarms = (alarmUsage[playlistToDelete.id] ?: 0) > 0
        AppConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            message = if (usedByAlarms) {
                stringResource(R.string.playlist_delete_message_alarms, playlistToDelete.name)
            } else {
                stringResource(R.string.playlist_delete_message, playlistToDelete.name)
            },
            confirmLabel = stringResource(R.string.playlist_delete_action),
            onConfirm = {
                playlistDeleteTarget = null
                playlistsViewModel.deletePlaylist(playlistToDelete.id)
            },
            onDismiss = { playlistDeleteTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackItemRow(
    track: TrackEntity,
    isActive: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    /** Stage 3: происхождение трека; null — иконку не показывать (фильтры). */
    source: TrackSource?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    /** Part 3: «скачан приложением» (файл есть) — по нему пункт «Удалить из памяти». */
    downloadStatus: TrackDownloadStatus,
    onMenuAction: (TrackRowAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = dimens.cardBorderWidth,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(dimens.trackIconContainerSize),
                contentAlignment = Alignment.Center
            ) {
                // «♪» у обычных строк, эквалайзер/пауза — только у играющего трека.
                TrackPlaybackIndicator(
                    isActive = isActive,
                    isPlaying = isPlaying
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Фидбек: исполнитель больше НЕ показывается нигде. На его
                // месте — читаемая длительность трека («3 мин», «1 час 3 мин»)
                // через ЕДИНЫЙ форматтер `formatTrackDuration`: секунд в списке
                // нет, неизвестная длительность не даёт placeholder'а.
                // Тихая иконка происхождения (Stage 3) остаётся перед
                // длительностью и ТОЛЬКО в фильтре «Все»; размер и прозрачность —
                // из AppDimens, цвет — onSurfaceVariant темы, подпись для
                // TalkBack — из strings.xml.
                val durationText = formatTrackDuration(track.durationMs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (source != null) {
                        Icon(
                            imageVector = if (source == TrackSource.DOWNLOADED) {
                                Icons.Outlined.Download
                            } else {
                                Icons.Outlined.Smartphone
                            },
                            contentDescription = stringResource(
                                if (source == TrackSource.DOWNLOADED) {
                                    R.string.track_source_downloaded
                                } else {
                                    R.string.track_source_imported
                                }
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = dimens.trackSourceIconAlpha
                            ),
                            modifier = Modifier.size(dimens.trackSourceIconSize)
                        )
                        Spacer(modifier = Modifier.width(dimens.spacingXs))
                    }
                    if (durationText != null) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(dimens.spacingXs))

            // ♥ «Любимое»: общий компонент с экраном плейлиста, трек не запускает
            FavoriteToggleButton(
                isFavorite = isFavorite,
                onToggle = onFavoriteClick
            )

            // Единое меню строки трека: «Добавить в плейлист…», «Удалить»
            // и «Удалить из памяти» только для скачанного файла (Part 3).
            TrackRowMenu(
                context = TrackRowContext.Library,
                downloadStatus = downloadStatus,
                onAction = onMenuAction
            )
        }
    }
}

/**
 * Индекс первого трека в списке «Моей медиатеки»: заголовок экрана и
 * переключатель вкладок вынесены над pager, поэтому выше треков идут заголовок
 * «Плейлисты», ряды карточек сетки (по две), строка «Треки» с кнопкой
 * «+ Добавить», ряд фильтров, одноразовая подсказка фильтра «Скачанные» и
 * необязательные служебные строки (загрузка, подсказка о долгом нажатии).
 * Нужен только для прокрутки к играющему треку из мини-плеера — порядок
 * элементов должен совпадать с порядком `item { }`/`items` в странице pager.
 */
private fun libraryTracksStartIndex(
    uiState: MusicUiState,
    playlistCount: Int,
    filter: LibraryTrackFilter,
    visibleTrackCount: Int,
    downloadedHintVisible: Boolean
): Int {
    var index = 1 // заголовок «Плейлисты»
    index += (playlistCount + 1) / 2 // ряды карточек сетки (по две)
    index += 1 // строка «Треки» с кнопкой «+ Добавить»
    index += 1 // ряд фильтров
    if (filter == LibraryTrackFilter.DOWNLOADED && downloadedHintVisible) index += 1
    if (uiState.isLoading) index += 1
    if (visibleTrackCount > 0 && uiState.showLongPressHint) index += 1
    return index
}
