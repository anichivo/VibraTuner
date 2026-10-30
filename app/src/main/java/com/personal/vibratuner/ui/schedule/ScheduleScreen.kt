package com.personal.vibratuner.ui.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.alarm.AlarmCalculator
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard
import com.personal.vibratuner.ui.components.AppPrimaryButton
import com.personal.vibratuner.ui.components.EmptyStateView
import com.personal.vibratuner.ui.components.endTimeMinutesOfDay
import com.personal.vibratuner.ui.components.formatDurationWords
import com.personal.vibratuner.ui.components.formatTimeOfDay
import com.personal.vibratuner.ui.components.rememberSoundPreview
import com.personal.vibratuner.ui.music.MusicViewModel

@Composable
fun ScheduleScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel = viewModel(),
    // Тот же Activity-scoped MusicViewModel, что у мини-плеера: окно выбора
    // мелодии прослушивает треки через существующую привязку к сервису.
    playerViewModel: MusicViewModel = viewModel()
) {
    val dimens = AppTheme.dimens
    val alarmsState by viewModel.alarms.collectAsState()
    // null — Room ещё отвечает: пустое состояние не показываем, иначе на кадр
    // мелькали бы «Сеансы не настроены» и большая карточка создания.
    val alarmsLoaded = alarmsState != null
    val alarms = alarmsState.orEmpty()
    val tracks by viewModel.tracks.collectAsState()
    val playlists by viewModel.playlistList.collectAsState()
    val catalogPlaylistNames by viewModel.catalogPlaylistNames.collectAsState()
    val favoritesPlaylistTitle = stringResource(R.string.playlist_favorites_name)
    val playlistMissingLabel = stringResource(R.string.schedule_playlist_missing)
    val trackMissingLabel = stringResource(R.string.schedule_track_missing)
    val allLibraryLabel = stringResource(R.string.schedule_all_library)

    var editingAlarmId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingAlarm = alarms.firstOrNull { it.id == editingAlarmId }
    var isAddingNew by rememberSaveable { mutableStateOf(false) }
    // ЕДИНЫЙ callback создания сеанса: его вызывают и пустое состояние, и FAB.
    // Второй обработчик создал бы дублирующую логику открытия редактора.
    val onCreateAlarm: () -> Unit = { isAddingNew = true }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Данные ещё читаются: ни пустого состояния, ни FAB.
            !alarmsLoaded -> Unit

            alarms.isEmpty() -> {
                // Сеансов нет: ТОЛЬКО большая карточка с описанием и кнопкой.
                EmptyStateView(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.schedule_empty_title),
                    description = stringResource(R.string.schedule_empty_desc),
                    actionButton = {
                        AppPrimaryButton(
                            text = stringResource(R.string.schedule_add_alarm),
                            onClick = onCreateAlarm
                        )
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + dimens.spacingMd,
                    // 48 (spacingXxl) + 48 (minTouchTarget) под FAB: последняя
                    // карточка не прячется за кнопкой.
                    bottom = contentPadding.calculateBottomPadding() + dimens.spacingXxl + dimens.minTouchTarget,
                    start = dimens.spacingMd,
                    end = dimens.spacingMd
                ),
                verticalArrangement = Arrangement.spacedBy(dimens.spacingMd),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = stringResource(R.string.schedule_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                items(alarms, key = { it.id }) { alarm ->
                    // Источник звука на карточке: для PLAYLIST-будильника —
                    // имя плейлиста, для TRACK-будильника — выбранный трек
                    // (название и, если есть, исполнитель). Если трек удалён
                    // и не находится в библиотеке, показываем нейтральную
                    // подпись — пустого места на карточке быть не должно.
                    val sourceLabel = if (alarm.isPlaylistSource) {
                        val catalogId = alarm.catalogPlaylistId
                        if (catalogId != null) {
                            // Плейлист каталога «От автора» (в библиотеке его может
                            // не быть): имя берётся из кеша манифеста, а не из Room,
                            // поэтому до чтения кеша показывается нейтральная подпись,
                            // а не ложное «Плейлист не найден».
                            val name = catalogPlaylistNames[catalogId]
                            if (name == null) {
                                stringResource(R.string.schedule_catalog_playlist_label)
                            } else {
                                stringResource(
                                    R.string.schedule_playlist_source_format,
                                    name
                                )
                            }
                        } else {
                            val summary = playlists.firstOrNull { it.id == alarm.playlistId }
                            if (summary == null) {
                                playlistMissingLabel
                            } else {
                                stringResource(
                                    R.string.schedule_playlist_source_format,
                                    if (summary.isSystem) favoritesPlaylistTitle else summary.name
                                )
                            }
                        }
                    } else {
                        val track = alarm.trackId?.let { trackId ->
                            tracks.firstOrNull { it.id == trackId }
                        }
                        when {
                            // null в trackId означает «вся библиотека».
                            alarm.trackId == null -> stringResource(
                                R.string.schedule_track_source_format,
                                allLibraryLabel
                            )
                            track == null || track.title.isBlank() -> trackMissingLabel
                            // Фидбек: исполнитель не выводится — только название.
                            else -> stringResource(
                                R.string.schedule_track_source_format,
                                track.title
                            )
                        }
                    }
                    AlarmCard(
                        alarm = alarm,
                        sourceLabel = sourceLabel,
                        onToggle = { viewModel.toggleAlarm(alarm) },
                        onEdit = { editingAlarmId = alarm.id },
                        onDelete = { viewModel.deleteAlarm(alarm) }
                    )
                }
            }
        }

        // Маленькая круглая кнопка добавления — ТОЛЬКО когда сеансы уже есть:
        // при пустом списке её роль выполняет большая карточка выше.
        if (alarmsLoaded && alarms.isNotEmpty()) {
            FloatingActionButton(
                onClick = onCreateAlarm,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = dimens.spacingLg,
                        bottom = contentPadding.calculateBottomPadding() + dimens.spacingLg
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.schedule_add_alarm)
                )
            }
        }
    }

    // Предпрослушивание в окне выбора мелодии — через тот же координатор сервиса.
    val soundPreview = rememberSoundPreview(playerViewModel)

    if (isAddingNew) {
        AlarmEditDialog(
            initialAlarm = null,
            tracks = tracks,
            playlists = playlists,
            preview = soundPreview,
            onImportFiles = playerViewModel::onAddFiles,
            onDismiss = { isAddingNew = false },
            onSave = { draft ->
                viewModel.saveAlarm(id = 0, draft = draft)
                isAddingNew = false
            }
        )
    }

    editingAlarm?.let { alarm ->
        AlarmEditDialog(
            initialAlarm = alarm,
            tracks = tracks,
            playlists = playlists,
            // Правка будильника каталога не требует добавления плейлиста в
            // библиотеку: ссылка и имя передаются в тот же единый редактор.
            initialCatalogPlaylist = alarm.catalogPlaylistId?.let { catalogId ->
                CatalogPlaylistRef(
                    catalogId = catalogId,
                    name = catalogPlaylistNames[catalogId],
                    trackCount = 0
                )
            },
            preview = soundPreview,
            onImportFiles = playerViewModel::onAddFiles,
            onDismiss = { editingAlarmId = null },
            onSave = { draft ->
                viewModel.saveAlarm(id = alarm.id, draft = draft)
                editingAlarmId = null
            }
        )
    }
}

/**
 * Карточка будильника. Иконок на карточке НЕТ: тап открывает редактор,
 * долгое нажатие показывает меню с единственным пунктом «Удалить» (существующая
 * логика удаления, без нового подтверждения).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    sourceLabel: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    val hapticFeedback = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val startMinuteOfDay = alarm.hour * 60 + alarm.minute
    val timeFormatted = formatTimeOfDay(startMinuteOfDay)
    val daysSet = remember(alarm.daysOfWeek) { AlarmCalculator.parseDaysOfWeek(alarm.daysOfWeek) }
    val daysLabel = formatDaysSummary(daysSet)
    val durationLabel = formatDurationWords(alarm.durationMinutes)
    // Конец сигнала считается тем же 24-часовым форматом, что и время старта.
    val endTimeFormatted = formatTimeOfDay(
        endTimeMinutesOfDay(startMinuteOfDay, alarm.durationMinutes)
    )
    val longClickLabel = stringResource(R.string.schedule_alarm_long_press_label)

    Box {
        AppCard(
            modifier = modifier
                .fillMaxWidth()
                // Тап — редактирование, долгое нажатие — меню «Удалить».
                .combinedClickable(
                    onClick = onEdit,
                    onLongClickLabel = longClickLabel,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    }
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.displaySmall,
                        color = if (alarm.isEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingXs))

                    Text(
                        text = stringResource(
                            R.string.schedule_meta_format,
                            durationLabel,
                            endTimeFormatted,
                            daysLabel
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingXs))

                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(dimens.spacingSm))

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            }
        }

        // Единственный пункт меню — «Удалить». Цвета и типографика из темы.
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.schedule_delete),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}


