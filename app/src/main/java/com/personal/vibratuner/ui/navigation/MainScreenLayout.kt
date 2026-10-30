package com.personal.vibratuner.ui.navigation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.data.download.TrackDownloadEvent
import com.personal.vibratuner.data.local.saf.FolderImportEvent
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.theme.NavLabelStyle
import com.personal.vibratuner.ui.equalizer.EqualizerScreen
import com.personal.vibratuner.ui.equalizer.EqualizerViewModel
import com.personal.vibratuner.ui.microsounds.MicrosoundsScreen
import com.personal.vibratuner.ui.music.MusicScreen
import com.personal.vibratuner.ui.music.MusicTab
import com.personal.vibratuner.ui.music.MusicViewModel
import com.personal.vibratuner.ui.player.MiniPlayer
import com.personal.vibratuner.ui.playlist.AuthorPlaylistScreenRoute
import com.personal.vibratuner.ui.playlist.PlaylistScreenRoute
import com.personal.vibratuner.ui.schedule.ScheduleScreen
import com.personal.vibratuner.ui.settings.SettingsScreen
import com.personal.vibratuner.ui.settings.SettingsViewModel
import com.personal.vibratuner.ui.components.TermsDialog
import com.personal.vibratuner.ui.components.WelcomeDialog
import kotlinx.coroutines.launch

enum class AppTab(val titleRes: Int, val icon: ImageVector) {
    MUSIC(R.string.nav_music, Icons.Default.MusicNote),
    MICROSOUNDS(R.string.nav_microsounds, Icons.Default.GraphicEq),
    SCHEDULE(R.string.nav_schedule, Icons.Default.Schedule),
    EQUALIZER(R.string.nav_equalizer, Icons.Default.Equalizer),
    SETTINGS(R.string.nav_settings, Icons.Default.Tune)
}

@Composable
fun MainScreenLayout(
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.MUSIC) }
    // Вкладка «Моя медиатека» | «Каталог»: переживает поворот и смену нижних вкладок.
    var selectedMusicTab by rememberSaveable { mutableStateOf(MusicTab.LIBRARY) }
    // Маршрут «Экран плейлиста»: аргумент — id плейлиста, null — вкладки.
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Маршрут «Экран плейлиста От автора»: id из манифеста (этап 4).
    var openAuthorPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Part 3: ЕДИНОЕ сообщение о любой ошибке скачивания/воспроизведения
    // (один и тот же ресурс для сетевых сбоев и для общих ошибок загрузки).
    // Источников два — события менеджера загрузок и версия события из сервиса, —
    // а текст один; правило «не чаще одного раза в 10 секунд» действует только
    // для сетевых ошибок, логика обработки не менялась.
    val connectionErrorMessage = stringResource(R.string.error_connection_common)
    var lastConnectionErrorAtMs by remember { mutableStateOf(0L) }
    val snackbarScope = rememberCoroutineScope()
    val showConnectionError: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectionErrorAtMs >= CONNECTION_ERROR_DEDUPE_MS) {
            lastConnectionErrorAtMs = now
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = connectionErrorMessage,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }
    // Stage 2: ошибка отдельной загрузки трека показывается ОДНИМ корневым
    // SnackbarHost — коротко и не блокируя интерфейс, с любой открытой вкладки.
    // Само состояние загрузки живёт в менеджере приложения, поэтому индикатор
    // в строке не застревает (менеджер снимает его до отправки события).
    // Part 3: текст тот же, что у сетевой ошибки, — один ресурс на всё.
    val app = LocalContext.current.applicationContext as VibraTunerApplication
    LaunchedEffect(app) {
        app.trackDownloads.events.collect { event ->
            when (event) {
                is TrackDownloadEvent.Failed -> snackbarHostState.showSnackbar(
                    message = connectionErrorMessage,
                    duration = SnackbarDuration.Short
                )
                // Сеть/HTTP подвели у встроенного онлайн-плейлиста: одно
                // короткое сообщение с общим правилом дедупликации.
                TrackDownloadEvent.ConnectionFailed -> showConnectionError()
            }
        }
    }
    // Автосканирование сохранённой папки импорта: сообщение о потерянном доступе
    // (выбрать папку заново) и о добавленных файлах показывается ТЕМ ЖЕ
    // единственным корневым Snackbar, что и остальные сообщения приложения.
    val folderImportEvent by app.folderImport.event.collectAsState()
    val folderPermissionLostMessage = stringResource(R.string.import_folder_permission_lost)
    LaunchedEffect(folderImportEvent) {
        val event = folderImportEvent ?: return@LaunchedEffect
        val message = when (event) {
            is FolderImportEvent.PermissionLost -> folderPermissionLostMessage
            is FolderImportEvent.Added -> app.resources.getQuantityString(
                R.plurals.import_added_plural,
                event.count,
                event.count
            )
        }
        // Снимаем событие ДО показа: поворот/рекомпозиция не покажут его повторно.
        app.folderImport.consumeEvent()
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
    }
    // Единственный UI-контроллер глобального мини-плеера: связан с тем же
    // SoundPlaybackService/координатором, что и экран «Музыка».
    val musicViewModel: MusicViewModel = viewModel()
    // Приветственный диалог ПЕРВОГО запуска (Part 1 справки): живёт на корневом
    // уровне, чтобы показываться один раз независимо от раздела. Факт показа —
    // флаг DataStore из MusicViewModel; локальный rememberSaveable закрывает
    // диалог сразу при нажатии, пока флаг записывается, и переживает поворот.
    val welcomeShown by musicViewModel.welcomeShown.collectAsState()
    var welcomeDismissedLocally by rememberSaveable { mutableStateOf(false) }
    // Правила первого запуска — СЛЕДУЮЩЕЕ окно после приветствия. Пока они не
    // приняты кнопкой «Принять», окно не закрывается ни Back, ни тапом вне;
    // флаг DataStore пишется в MusicViewModel, локальный флаг гасит окно сразу.
    val termsAccepted by musicViewModel.termsAccepted.collectAsState()
    var termsAcceptedLocally by rememberSaveable { mutableStateOf(false) }
    // Сетевая ошибка воспроизведения (сервис -> ViewModel -> версия события):
    // сообщение показывается, когда UI видим, и НИКОГДА не повторяется для
    // одного и того же значения версии (поворот/рекомпозиция).
    val connectionErrorVersion by musicViewModel.connectionErrorVersion.collectAsState()
    var shownConnectionErrorVersion by remember {
        mutableStateOf(connectionErrorVersion)
    }
    LaunchedEffect(connectionErrorVersion) {
        if (connectionErrorVersion <= shownConnectionErrorVersion) return@LaunchedEffect
        shownConnectionErrorVersion = connectionErrorVersion
        showConnectionError()
    }
    // Тот же ViewModel, что создаёт экран «Эквалайзер» (Activity-скоуп):
    // нижняя навигация показывает живой индикатор включённой обработки, даже
    // когда сам экран не открыт. Второго источника состояния нет — это Flow
    // того же `EqualizerEditor`/репозитория.
    val equalizerViewModel: EqualizerViewModel = viewModel()
    val equalizerSettings by equalizerViewModel.settings.collectAsState()
    // Тот же ViewModel, что и у экрана «Настройки» (Activity-скоуп): точка у
    // раздела «Настройки» показывает живой `updateAvailable` — переключатель
    // включён И последний релиз новее установленной версии. Второго источника
    // состояния нет: это Flow `UpdateRepository`.
    val settingsViewModel: SettingsViewModel = viewModel()
    val updateAvailable by settingsViewModel.updateAvailable.collectAsState()
    // Сохранение состояния разделов между переключениями нижней навигации.
    // NavHost в проекте нет (navigation-библиотеки нет), маршруты задаются
    // состоянием, поэтому saveState/restoreState заменяет SaveableStateHolder:
    // при уходе с раздела его rememberSaveable-состояние сохраняется, а при
    // возврате восстанавливается (позиции списков, страница pager, диалоги).
    // Именно потеря этого состояния и пересоздание контента давали мерцание.
    val sectionStateHolder = rememberSaveableStateHolder()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Одна непрозрачная подложка приложения из темы: между сменой
            // разделов не бывает кадра с фоном окна или прозрачностью.
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isWideScreen = maxWidth >= dimens.wideScreenBreakpoint

        if (isWideScreen) {
            // Адаптивная навигация для планшетов, горизонтальной ориентации и широких экранов
            Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    AppTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        NavigationRailItem(
                            selected = isSelected,
                            onClick = {
                                selectedTab = tab
                                openPlaylistId = null
                                openAuthorPlaylistId = null
                            },
                            icon = {
                                AppTabIcon(
                                    tab = tab,
                                    isSelected = isSelected,
                                    equalizerEnabled = equalizerSettings.enabled,
                                    updateAvailable = updateAvailable
                                )
                            },
                            label = {
                                AppTabLabel(titleRes = tab.titleRes)
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    SectionHost(
                        selectedTab = selectedTab,
                        openPlaylistId = openPlaylistId,
                        openAuthorPlaylistId = openAuthorPlaylistId,
                        contentPadding = PaddingValues(dimens.spacingMd),
                        snackbarHostState = snackbarHostState,
                        musicViewModel = musicViewModel,
                        selectedMusicTab = selectedMusicTab,
                        onMusicTabSelected = { selectedMusicTab = it },
                        onOpenPlaylist = {
                            openAuthorPlaylistId = null
                            openPlaylistId = it
                        },
                        onOpenAuthorPlaylist = {
                            openPlaylistId = null
                            openAuthorPlaylistId = it
                        },
                        onCloseRoute = {
                            openPlaylistId = null
                            openAuthorPlaylistId = null
                        },
                        stateHolder = sectionStateHolder,
                        modifier = Modifier.fillMaxSize()
                    )
                    AppSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding()
                            .padding(dimens.spacingMd)
                    )
                }
            }
                // Глобальный мини-плеер: на широких экранах нет нижней навигации,
                // поэтому панель стоит внизу контента с учётом системной панели.
                MiniPlayer(
                    viewModel = musicViewModel,
                    onBodyClick = musicViewModel::onMiniPlayerBodyClick,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        } else {
            // Стандартная нижняя панель для мобильных телефонов
            Scaffold(
                // Фон контента — ровно фон темы (Scaffold по умолчанию берёт его
                // же): при смене разделов не бывает кадра с другим фоном.
                containerColor = MaterialTheme.colorScheme.background,
                // Единственный на всё приложение SnackbarHost. Material3 Scaffold
                // размещает его НАД bottomBar, поэтому сообщение не перекрывается
                // плавающим мини-плеером; нижний зазор из Dimens отделяет плашку
                // Snackbar от карточки плеера, когда трек есть, и от NavigationBar,
                // когда плеер скрыт. Другого механизма всплывающих сообщений нет.
                snackbarHost = {
                    AppSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .imePadding()
                            .padding(
                                start = dimens.spacingMd,
                                end = dimens.spacingMd,
                                bottom = dimens.spacingSm
                            )
                    )
                },
                bottomBar = {
                    // Мини-плеер — плавающая карточка строго над NavigationBar
                    // и только в одном месте на всё приложение (не дублируется
                    // по экранам). Он остаётся ПЕРВЫМ элементом bottomBar,
                    // поэтому Scaffold сам добавляет спискам нижний отступ
                    // на высоту карточки + зазор + NavigationBar.
                    Column {
                        MiniPlayer(
                            viewModel = musicViewModel,
                            onBodyClick = musicViewModel::onMiniPlayerBodyClick
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            AppTab.entries.forEach { tab ->
                                val isSelected = tab == selectedTab
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        // Идемпотентно (аналог launchSingleTop):
                                        // повторный тап по текущему разделу пишет
                                        // те же значения, рекомпозиции и
                                        // пересоздания контента не происходит.
                                        selectedTab = tab
                                        openPlaylistId = null
                                        openAuthorPlaylistId = null
                                    },
                                    icon = {
                                        AppTabIcon(
                                            tab = tab,
                                            isSelected = isSelected,
                                            equalizerEnabled = equalizerSettings.enabled,
                                            updateAvailable = updateAvailable
                                        )
                                    },
                                    label = {
                                        AppTabLabel(titleRes = tab.titleRes)
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                SectionHost(
                    selectedTab = selectedTab,
                    openPlaylistId = openPlaylistId,
                    openAuthorPlaylistId = openAuthorPlaylistId,
                    contentPadding = innerPadding,
                    snackbarHostState = snackbarHostState,
                    musicViewModel = musicViewModel,
                    selectedMusicTab = selectedMusicTab,
                    onMusicTabSelected = { selectedMusicTab = it },
                    onOpenPlaylist = {
                        openAuthorPlaylistId = null
                        openPlaylistId = it
                    },
                    onOpenAuthorPlaylist = {
                        openPlaylistId = null
                        openAuthorPlaylistId = it
                    },
                    onCloseRoute = {
                        openPlaylistId = null
                        openAuthorPlaylistId = null
                    },
                    stateHolder = sectionStateHolder,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Диалоги первого запуска. Показываются ПОВЕРХ всего приложения строго
        // ПО ОДНОМУ: сначала приветствие (закрывается любым способом через
        // существующий HelpDialog), сразу после него — обязательные правила
        // (закрываются только кнопкой «Принять»). Запрос разрешения на уведомления
        // ждёт закрытия обоих (см. MusicScreen).
        if (welcomeShown == false && !welcomeDismissedLocally) {
            WelcomeDialog(
                onDismiss = {
                    welcomeDismissedLocally = true
                    musicViewModel.onWelcomeDialogDismissed()
                }
            )
        } else if (
            (welcomeShown == true || welcomeDismissedLocally) &&
            termsAccepted == false && !termsAcceptedLocally
        ) {
            TermsDialog(
                onAccept = {
                    termsAcceptedLocally = true
                    musicViewModel.onTermsAccepted()
                }
            )
        }
    }
}

/**
 * Содержимое открытого маршрута: экран плейлиста поверх раздела либо сам раздел.
 *
 * Состояние разделов сохраняется между переключениями нижней навигации через
 * [stateHolder] — это аналог `saveState`/`restoreState` в NavHost: в проекте нет
 * navigation-библиотеки, маршруты задаются состоянием [MainScreenLayout], поэтому
 * сохранение `rememberSaveable`-состояния делает `SaveableStateHolder`. Без него
 * каждый переход «Музыка» -> «Микрозвуки» -> «Музыка» уничтожал поддерево
 * раздела и собирал его заново (сбрасывались позиции списков, страница pager,
 * открытые диалоги), что и давало видимое мерцание при смене разделов.
 */
@Composable
private fun SectionHost(
    selectedTab: AppTab,
    openPlaylistId: Long?,
    openAuthorPlaylistId: String?,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    musicViewModel: MusicViewModel,
    selectedMusicTab: MusicTab,
    onMusicTabSelected: (MusicTab) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAuthorPlaylist: (String) -> Unit,
    onCloseRoute: () -> Unit,
    stateHolder: SaveableStateHolder,
    modifier: Modifier = Modifier
) {
    when {
        // Маршрут read-only плейлиста «От автора» (этап 4).
        openAuthorPlaylistId != null -> AuthorPlaylistScreenRoute(
            authorPlaylistId = openAuthorPlaylistId,
            contentPadding = contentPadding,
            snackbarHostState = snackbarHostState,
            playerViewModel = musicViewModel,
            onBack = onCloseRoute,
            modifier = modifier
        )

        // Маршрут экрана плейлиста заменяет контент вкладки целиком.
        openPlaylistId != null -> PlaylistScreenRoute(
            playlistId = openPlaylistId,
            contentPadding = contentPadding,
            snackbarHostState = snackbarHostState,
            playerViewModel = musicViewModel,
            onBack = onCloseRoute,
            modifier = modifier
        )

        else -> stateHolder.SaveableStateProvider(selectedTab.name) {
            TabContent(
                tab = selectedTab,
                musicViewModel = musicViewModel,
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                musicTab = selectedMusicTab,
                onMusicTabSelected = onMusicTabSelected,
                onOpenPlaylist = onOpenPlaylist,
                onOpenAuthorPlaylist = onOpenAuthorPlaylist,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun TabContent(
    tab: AppTab,
    musicViewModel: MusicViewModel,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    musicTab: MusicTab,
    onMusicTabSelected: (MusicTab) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAuthorPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (tab) {
        AppTab.MUSIC -> {
            MusicScreen(
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                onOpenPlaylist = onOpenPlaylist,
                onOpenAuthorPlaylist = onOpenAuthorPlaylist,
                selectedTab = musicTab,
                onTabSelected = onMusicTabSelected,
                modifier = modifier,
                viewModel = musicViewModel
            )
        }
        AppTab.MICROSOUNDS -> {
            com.personal.vibratuner.ui.microsounds.MicrosoundsScreen(
                contentPadding = contentPadding,
                modifier = modifier
            )
        }
        AppTab.SCHEDULE -> {
            ScheduleScreen(contentPadding = contentPadding, modifier = modifier)
        }
        AppTab.EQUALIZER -> {
            EqualizerScreen(contentPadding = contentPadding, modifier = modifier)
        }
        AppTab.SETTINGS -> {
            SettingsScreen(contentPadding = contentPadding, modifier = modifier)
        }
    }
}

/**
 * Иконка раздела нижней навигации и нижнего rail-меню.
 *
 * Маленькая точка-бейдж (`Badge`) в акценте темы появляется у включённого
 * эквалайзера и у раздела «Настройки», когда доступно обновление. Цвет —
 * `primary` на тёмной панели и `onPrimary` на акцентном индикаторе выбранной
 * вкладки, иначе акцентная точка слилась бы с индикатором. Состояния живые —
 * из тех же Flow, что и экраны. `contentDescription` сообщает, что обработка
 * включена или что доступно обновление; других индикаторов и второго
 * источника состояния нет.
 */
@Composable
private fun AppTabIcon(
    tab: AppTab,
    isSelected: Boolean,
    equalizerEnabled: Boolean,
    updateAvailable: Boolean
) {
    val showEqualizerBadge = tab == AppTab.EQUALIZER && equalizerEnabled
    val showUpdateBadge = tab == AppTab.SETTINGS && updateAvailable
    val description = when {
        showEqualizerBadge -> stringResource(R.string.nav_equalizer_enabled_description)
        showUpdateBadge -> stringResource(R.string.nav_settings_update_available_description)
        else -> stringResource(tab.titleRes)
    }

    BadgedBox(
        badge = {
            if (showEqualizerBadge || showUpdateBadge) {
                Badge(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = description
        )
    }
}

/**
 * Подпись раздела нижней навигации. С пятью разделами подпись обязана
 * помещаться в одну строку: перенос запрещён, при крайне узкой ячейке
 * допускается многоточие (последнее средство), а масштаб системного шрифта
 * ограничен `AppDimens.navLabelMaxFontScale`, чтобы обычные телефоны
 * показывали текст целиком.
 */
@Composable
private fun AppTabLabel(titleRes: Int) {
    val density = LocalDensity.current
    val maxFontScale = AppTheme.dimens.navLabelMaxFontScale
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale.coerceAtMost(maxFontScale)
        )
    ) {
        Text(
            text = stringResource(titleRes),
            style = NavLabelStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            // Explicit dark-theme values: never use Material's light inverse colors.
            Snackbar(
                shape = RoundedCornerShape(dimens.radiusMd),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                content = {
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            )
        }
    )
}

/** Одно сообщение об ошибке соединения — не чаще одного раза в 10 секунд. */
private const val CONNECTION_ERROR_DEDUPE_MS = 10_000L
