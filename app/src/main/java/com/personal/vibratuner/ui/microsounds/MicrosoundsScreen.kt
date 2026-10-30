package com.personal.vibratuner.ui.microsounds

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.microsound.BuiltinMicrosoundCatalog
import com.personal.vibratuner.audio.microsound.MicrosoundIntervalRule
import com.personal.vibratuner.audio.microsound.MicrosoundQuietHoursConflict
import com.personal.vibratuner.audio.microsound.MicrosoundQuietHoursConflictCalculator
import com.personal.vibratuner.audio.microsound.MicrosoundWindow
import com.personal.vibratuner.audio.microsound.PinnedMicrosoundSettings
import com.personal.vibratuner.audio.microsound.QuietHoursSettings
import com.personal.vibratuner.data.local.db.MicrosoundEntity
import com.personal.vibratuner.data.local.saf.SafTrackImporter
import com.personal.vibratuner.ui.music.MusicViewModel
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard
import com.personal.vibratuner.ui.components.AppOutlinedButton
import com.personal.vibratuner.ui.components.AppPrimaryButton
import com.personal.vibratuner.ui.components.AppTimeValue
import com.personal.vibratuner.ui.components.EmptyStateView
import com.personal.vibratuner.ui.components.HelpIcon
import com.personal.vibratuner.ui.components.HelpTopic
import com.personal.vibratuner.ui.components.SoundPickerDialog
import com.personal.vibratuner.ui.components.SoundPickerItem
import com.personal.vibratuner.ui.components.SoundPreviewState
import com.personal.vibratuner.ui.components.TimePickerSheet
import com.personal.vibratuner.ui.components.dismissKeyboardOnTapOutside
import com.personal.vibratuner.ui.components.formatTimeOfDay
import com.personal.vibratuner.ui.components.rememberDoneKeyboardActions
import com.personal.vibratuner.ui.components.rememberSoundPreview

@Composable
fun MicrosoundsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: MicrosoundsViewModel = viewModel(),
    // Тот же Activity-scoped MusicViewModel, что у мини-плеера и «Расписания»:
    // предпрослушивание в ОБЩЕМ окне выбора идёт существующим координатором
    // сервиса (второй привязки, плеера и пути воспроизведения нет).
    playerViewModel: MusicViewModel = viewModel()
) {
    val dimens = AppTheme.dimens
    val microsounds by viewModel.microsounds.collectAsState()
    val quietHours by viewModel.quietHours.collectAsState()
    val pinnedMicrosound by viewModel.pinnedMicrosound.collectAsState()
    val soundPreview = rememberSoundPreview(playerViewModel)

    // Предупреждения о конфликте с «Часами тишины» (этап 4) считаются ЧИСТОЙ
    // функцией из ЖИВЫХ потоков настроек, поэтому появляются и исчезают сразу
    // после смены часов тишины (вкл/выкл, начало, конец), окна или переключателя,
    // без повторного открытия экрана. У выключенного микрозвука предупреждения нет.
    val pinnedQuietConflict = if (pinnedMicrosound.enabled) {
        MicrosoundQuietHoursConflictCalculator.conflict(quietHours, pinnedMicrosound.window)
    } else {
        MicrosoundQuietHoursConflict.NONE
    }
    val fakeQuietConflict = if (pinnedMicrosound.enabled && pinnedMicrosound.fakeEnabled) {
        MicrosoundQuietHoursConflictCalculator.conflict(quietHours, pinnedMicrosound.fakeWindow)
    } else {
        MicrosoundQuietHoursConflict.NONE
    }

    var editingMicrosoundId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingMicrosound = microsounds.firstOrNull { it.id == editingMicrosoundId }
    var isAddingNew by rememberSaveable { mutableStateOf(false) }
    var editingQuietStart by rememberSaveable { mutableStateOf(false) }
    var editingQuietEnd by rememberSaveable { mutableStateOf(false) }
    var quietHoursError by rememberSaveable { mutableStateOf(false) }
    // Настройки встроенных микрозвуков живут в ТОМ ЖЕ модальном контейнере, что
    // у пользовательских: тап по карточке открывает окно, а не раскрывает её.
    var isPinnedSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isFakeSettingsOpen by rememberSaveable { mutableStateOf(false) }

    // Тап по пустому месту экрана снимает фокус с полей интервала и прячет
    // клавиатуру; фокус также снимается, когда клавиатуру закрыли системой.
    Box(modifier = modifier.fillMaxSize().dismissKeyboardOnTapOutside()) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + dimens.spacingMd,
                bottom = contentPadding.calculateBottomPadding() + dimens.fabListBottomPadding,
                start = dimens.spacingMd,
                end = dimens.spacingMd
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingMd),
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // Глобальные «Часы тишины» — всегда вверху экрана
            item {
                QuietHoursCard(
                    settings = quietHours,
                    showError = quietHoursError,
                    onEnabledChange = { enabled ->
                        quietHoursError = false
                        viewModel.setQuietHoursEnabled(enabled)
                    },
                    onStartClick = { editingQuietStart = true },
                    onEndClick = { editingQuietEnd = true }
                )
            }

            // Закреплённый микрозвук — первый в списке микрозвуков
            item {
                PinnedMicrosoundCard(
                    settings = pinnedMicrosound,
                    quietConflict = pinnedQuietConflict,
                    onEnabledChange = viewModel::setPinnedMicrosoundEnabled,
                    onClick = { isPinnedSettingsOpen = true }
                )
            }

            // Фейк-включение — ОТДЕЛЬНАЯ карточка того же компактного вида. Она
            // имеет смысл только при включённом «Включении колонки» (звук и
            // усиление берутся оттуда), поэтому при выключенном звуке её нет:
            // включить фейк — не больше двух нажатий.
            if (pinnedMicrosound.enabled) {
                item {
                    FakePinnedMicrosoundCard(
                        settings = pinnedMicrosound,
                        quietConflict = fakeQuietConflict,
                        onEnabledChange = viewModel::setFakePinnedMicrosoundEnabled,
                        onClick = { isFakeSettingsOpen = true }
                    )
                }
            }

            if (microsounds.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Default.GraphicEq,
                        title = stringResource(R.string.microsounds_empty_title),
                        description = stringResource(R.string.microsounds_empty_desc),
                        actionButton = {
                            AppPrimaryButton(
                                text = stringResource(R.string.microsounds_add_btn),
                                onClick = { isAddingNew = true }
                            )
                        }
                    )
                }
            } else {
                items(microsounds, key = { it.id }) { sound ->
                    val quietConflict = if (sound.isEnabled) {
                        MicrosoundQuietHoursConflictCalculator.conflict(quietHours, sound.window)
                    } else {
                        MicrosoundQuietHoursConflict.NONE
                    }
                    MicrosoundCard(
                        title = microsoundSourceLabel(sound.uri, sound.title),
                        summary = microsoundSummary(sound),
                        checked = sound.isEnabled,
                        onCheckedChange = { viewModel.toggleMicrosound(sound) },
                        quietConflict = quietConflict,
                        onClick = { editingMicrosoundId = sound.id },
                        onDelete = { viewModel.deleteMicrosound(sound) }
                    )
                }
            }
        }

        // Кнопка добавления (в пустом состоянии кнопка уже есть в EmptyStateView)
        if (microsounds.isNotEmpty()) {
            FloatingActionButton(
                onClick = { isAddingNew = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(contentPadding)
                    .padding(dimens.spacingLg)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.microsounds_add_btn)
                )
            }
        }

        if (editingQuietStart) {
            TimePickerSheet(
                title = stringResource(R.string.time_picker_title),
                initialHour = quietHours.startHour,
                initialMinute = quietHours.startMinute,
                onDismiss = { editingQuietStart = false },
                onConfirm = { h, m ->
                    val newStart = h * 60 + m
                    if (newStart == quietHours.endMinuteOfDay) {
                        quietHoursError = true
                    } else {
                        quietHoursError = false
                        viewModel.setQuietHours(newStart, quietHours.endMinuteOfDay)
                    }
                    editingQuietStart = false
                }
            )
        }

        if (editingQuietEnd) {
            TimePickerSheet(
                title = stringResource(R.string.time_picker_title),
                initialHour = quietHours.endHour,
                initialMinute = quietHours.endMinute,
                onDismiss = { editingQuietEnd = false },
                onConfirm = { h, m ->
                    val newEnd = h * 60 + m
                    if (newEnd == quietHours.startMinuteOfDay) {
                        quietHoursError = true
                    } else {
                        quietHoursError = false
                        viewModel.setQuietHours(quietHours.startMinuteOfDay, newEnd)
                    }
                    editingQuietEnd = false
                }
            )
        }

        // Диалог добавления/редактирования
        if (isAddingNew || editingMicrosound != null) {
            val target = editingMicrosound
            MicrosoundEditDialog(
                initialMicrosound = target,
                preview = soundPreview,
                onDismiss = {
                    isAddingNew = false
                    editingMicrosoundId = null
                },
                onSave = { id, title, uri, gain, minInt, maxInt, window, isEnabled ->
                    viewModel.saveMicrosound(
                        id = id,
                        title = title,
                        uri = uri,
                        gain = gain,
                        minIntervalMinutes = minInt,
                        maxIntervalMinutes = maxInt,
                        isEnabled = isEnabled,
                        window = window
                    )
                    isAddingNew = false
                    editingMicrosoundId = null
                }
            )
        }

        // Настройки встроенных микрозвуков — в ТОМ ЖЕ модальном контейнере, что
        // у пользовательского микрозвука: тап по любой карточке открывает окно.
        // Изменения сохраняются сразу (DataStore), поэтому у окна одна кнопка.
        if (isPinnedSettingsOpen) {
            MicrosoundDetailsDialog(
                title = stringResource(R.string.pinned_microsound_title),
                onDismiss = { isPinnedSettingsOpen = false }
            ) {
                PinnedMicrosoundSettings(
                    settings = pinnedMicrosound,
                    preview = soundPreview,
                    onFileSelected = viewModel::setPinnedMicrosoundFile,
                    onSoundSelected = viewModel::setPinnedMicrosoundBuiltinSound,
                    onGainChange = viewModel::setPinnedMicrosoundGain,
                    onWindowChange = viewModel::setPinnedMicrosoundWindow
                )
            }
        }

        if (pinnedMicrosound.enabled && isFakeSettingsOpen) {
            MicrosoundDetailsDialog(
                title = stringResource(R.string.pinned_fake_title),
                onDismiss = { isFakeSettingsOpen = false }
            ) {
                FakePinnedMicrosoundSettings(
                    settings = pinnedMicrosound,
                    onIntervalChange = viewModel::setFakePinnedMicrosoundIntervals,
                    onWindowChange = viewModel::setFakePinnedMicrosoundWindow
                )
            }
        }
    }
}

@Composable
private fun QuietHoursCard(
    settings: QuietHoursSettings,
    showError: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.quiet_hours_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = stringResource(R.string.quiet_hours_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = settings.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm)
        ) {
            AppTimeValue(
                hour = settings.startHour,
                minute = settings.startMinute,
                prefix = stringResource(R.string.quiet_hours_from),
                onClick = onStartClick,
                modifier = Modifier.weight(1f)
            )
            AppTimeValue(
                hour = settings.endHour,
                minute = settings.endMinute,
                prefix = stringResource(R.string.quiet_hours_to),
                onClick = onEndClick,
                modifier = Modifier.weight(1f)
            )
        }

        if (showError) {
            Spacer(modifier = Modifier.height(dimens.spacingSm))
            Text(
                text = stringResource(R.string.quiet_hours_validation_same_time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PinnedMicrosoundCard(
    settings: PinnedMicrosoundSettings,
    quietConflict: MicrosoundQuietHoursConflict,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultFileName = stringResource(R.string.microsounds_default_filename)
    val soundLabel = pinnedSoundLabel(
        uri = settings.uri,
        fileName = settings.fileName,
        defaultFileName = defaultFileName
    )

    MicrosoundCard(
        title = stringResource(R.string.pinned_microsound_title),
        summary = pinnedSummary(settings, soundLabel),
        checked = settings.enabled,
        onCheckedChange = onEnabledChange,
        quietConflict = quietConflict,
        helpTopic = HelpTopic.SPEAKER_POWER,
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * ОБЩИЙ модальный контейнер настроек встроенных микрозвуков («Включение
 * колонки» и фейк-включение): те же поля, что раньше жили прямо в карточке,
 * но в окне — как у пользовательского микрозвука. Изменения
 * сохраняются сразу (DataStore), поэтому у окна одна кнопка «Готово»; «Отмена»
 * здесь не нужна и не вводит в заблуждение.
 *
 * Insets/IME: окно диалога подстраивает система (`decorFitsSystemWindows`),
 * поэтому `imePadding` сюда НЕ добавляется; содержимое прокручивается, а тап
 * по пустому месту снимает фокус с полей интервала (правила проекта §2).
 * Все цвета — из темы, размеры — из `AppDimens`.
 */
@Composable
private fun MicrosoundDetailsDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissKeyboardOnTapOutside(),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        },
        confirmButton = {
            AppPrimaryButton(
                text = stringResource(R.string.dialog_done),
                onClick = onDismiss
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * Настройки «Включения колонки» в ОБЩЕМ модальном окне микрозвука: звук
 * (ОБЩЕЕ окно выбора), усиление и собственное окно работы. Показываются только
 * в окне, поэтому компактный вид у всех микрозвуков одинаков.
 */
@Composable
private fun PinnedMicrosoundSettings(
    settings: PinnedMicrosoundSettings,
    preview: SoundPreviewState,
    onFileSelected: (uri: String, fileName: String) -> Unit,
    onSoundSelected: (storedUri: String) -> Unit,
    onGainChange: (Float) -> Unit,
    onWindowChange: (MicrosoundWindow?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dimens = AppTheme.dimens
    val defaultFileName = stringResource(R.string.microsounds_default_filename)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SafTrackImporter.takePersistablePermission(context, uri)
            val displayName = SafTrackImporter.queryDisplayName(context, uri)
                ?: uri.lastPathSegment
                ?: defaultFileName
            onFileSelected(uri.toString(), displayName)
        }
    }

    var localGain by remember(settings.gain) { mutableFloatStateOf(settings.gain) }

    // Собственное окно работы «Включения колонки»: выключено = «весь день»
    // (ключей в DataStore нет). Вне окна предзвук не планируется, будильник
    // срабатывает в T как обычно.
    var windowEnabled by rememberSaveable(settings.window) {
        mutableStateOf(settings.window != null)
    }
    var windowStart by rememberSaveable(settings.window) {
        mutableIntStateOf(
            settings.window?.startMinuteOfDay ?: MicrosoundWindow.DEFAULT_START_MINUTE_OF_DAY
        )
    }
    var windowEnd by rememberSaveable(settings.window) {
        mutableIntStateOf(
            settings.window?.endMinuteOfDay ?: MicrosoundWindow.DEFAULT_END_MINUTE_OF_DAY
        )
    }

    // ОБЩЕЕ окно выбора звука (то же, что в редакторе микрозвука и в выборе
    // мелодии/плейлиста сеанса). Выбор применяется только по «Сохранить».
    var showSoundPicker by rememberSaveable { mutableStateOf(false) }

    // Выбран свой файл (content://) или встроенный звук каталога (`builtin:<id>`).
    val isCustomFileSelected = settings.uri.isNotBlank() &&
        !BuiltinMicrosoundCatalog.hasBuiltinPrefix(settings.uri)
    val isUnknownPinnedSound = BuiltinMicrosoundCatalog.hasBuiltinPrefix(settings.uri) &&
        BuiltinMicrosoundCatalog.findByStored(settings.uri) == null

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pinned_microsound_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        // Справка по «Включению колонки» — иконка «i» в строке карточки
        // (HelpTopic.SPEAKER_POWER), поэтому внутри окна её нет.
        Text(
            text = stringResource(R.string.pinned_microsound_sound_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(dimens.spacingXs))

        Text(
            text = stringResource(R.string.pinned_microsound_sound_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        Box {
            SoundPickerTrigger(
                label = pinnedSoundLabel(
                    uri = settings.uri,
                    fileName = settings.fileName,
                    defaultFileName = defaultFileName
                ),
                onClick = { showSoundPicker = true }
            )

            if (showSoundPicker) {
                SoundPickerDialog(
                    title = stringResource(R.string.microsound_picker_title),
                    items = microsoundPickerItems(
                        packageName = context.packageName,
                        customUri = settings.uri.takeIf { isCustomFileSelected },
                        customTitle = settings.fileName.ifBlank { defaultFileName }
                    ),
                    selectedKey = settings.uri.ifBlank { null },
                    emptyText = stringResource(R.string.microsound_picker_empty),
                    onDismiss = { showSoundPicker = false },
                    onConfirm = { item ->
                        showSoundPicker = false
                        // Свой файл уже сохранён ПРЕЖНИМ SAF-потоком — повторно
                        // не пишем; встроенный звук сохраняется как раньше.
                        if (BuiltinMicrosoundCatalog.hasBuiltinPrefix(item.key)) {
                            onSoundSelected(item.key)
                        }
                    },
                    onImport = { filePickerLauncher.launch(arrayOf("audio/*")) },
                    preview = preview
                )
            }
        }

        if (isUnknownPinnedSound) {
            Spacer(modifier = Modifier.height(dimens.spacingXs))
            Text(
                text = stringResource(R.string.microsounds_builtin_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        val gainPercent = (localGain * 100).toInt()
        Text(
            text = stringResource(R.string.pinned_microsound_gain, gainPercent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = localGain,
            onValueChange = { localGain = it },
            onValueChangeFinished = { onGainChange(localGain) },
            valueRange = PinnedMicrosoundSettings.MIN_GAIN..PinnedMicrosoundSettings.MAX_GAIN,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // Окно работы самого «Включения колонки» (Stage 3): тот же компонент,
        // что в редакторе микрозвука и у фейка.
        MicrosoundWindowField(
            enabled = windowEnabled,
            startMinuteOfDay = windowStart,
            endMinuteOfDay = windowEnd,
            onEnabledChange = { enabled ->
                windowEnabled = enabled
                when {
                    !enabled -> onWindowChange(null)
                    windowStart != windowEnd -> onWindowChange(
                        MicrosoundWindow(windowStart, windowEnd)
                    )
                }
            },
            onTimeChange = { start, end ->
                windowStart = start
                windowEnd = end
                if (start != end) {
                    onWindowChange(MicrosoundWindow(start, end))
                }
            }
        )
    }
}

/**
 * Компактный триггер ОБЩЕГО окна выбора звука: показывает текущий звук и
 * открывает то же окно, что у редактора микрозвука и выбора мелодии сеанса.
 * Список НИКОГДА не показывается прямо в карточке — иначе она занимает
 * слишком много высоты.
 */
@Composable
private fun SoundPickerTrigger(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Surface(
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = dimens.minTouchTarget)
                .padding(horizontal = dimens.spacingMd, vertical = dimens.spacingXs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(dimens.spacingSm))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimens.iconSizeMd)
            )
        }
    }
}

/**
 * Название выбранного звука «Включения колонки» для триггера: встроенный —
 * из каталога, свой файл — сохранённое имя, неизвестный builtin — нейтральная
 * подпись, пустой выбор — «не выбран».
 */
@Composable
private fun pinnedSoundLabel(
    uri: String,
    fileName: String,
    defaultFileName: String
): String {
    val builtin = BuiltinMicrosoundCatalog.findByStored(uri)
    return when {
        builtin != null -> stringResource(builtin.nameRes)
        BuiltinMicrosoundCatalog.hasBuiltinPrefix(uri) ->
            stringResource(R.string.microsounds_builtin_unknown)
        uri.isBlank() -> stringResource(R.string.pinned_microsound_sound_none)
        else -> stringResource(
            R.string.microsounds_file_selected,
            fileName.ifBlank { defaultFileName }
        )
    }
}

/**
 * Элементы ОБЩЕГО окна выбора микрозвука: встроенный каталог (`builtin:<id>`)
 * плюс, если сохранён свой файл, его строка. Категории встроенных звуков на
 * карточке НЕ показываются — только название и кнопка прослушивания.
 */
@Composable
private fun microsoundPickerItems(
    packageName: String,
    customUri: String?,
    customTitle: String
): List<SoundPickerItem> = buildList {
    BuiltinMicrosoundCatalog.entries.forEach { sound ->
        val stored = BuiltinMicrosoundCatalog.encode(sound.id)
        add(
            SoundPickerItem(
                key = stored,
                title = stringResource(sound.nameRes),
                previewUri = BuiltinMicrosoundCatalog.resourceUriString(packageName, stored)
            )
        )
    }
    if (customUri != null) {
        add(
            SoundPickerItem(
                key = customUri,
                title = customTitle,
                previewUri = customUri
            )
        )
    }
}

/**
 * Отдельная карточка «Фейк-включение колонки» с тем же компактным видом, что у
 * остальных микрозвуков: название, одна строка интервала/окна, переключатель.
 * Своего звука у фейка нет — он играет звук и усиление «Включения колонки».
 * Показывается при включённом «Включении колонки» (не больше двух нажатий до
 * включения фейка).
 */
@Composable
private fun FakePinnedMicrosoundCard(
    settings: PinnedMicrosoundSettings,
    quietConflict: MicrosoundQuietHoursConflict,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MicrosoundCard(
        title = stringResource(R.string.pinned_fake_title),
        summary = fakeSummary(settings),
        checked = settings.fakeEnabled,
        onCheckedChange = onEnabledChange,
        quietConflict = quietConflict,
        helpTopic = HelpTopic.FAKE_SPEAKER,
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * Раскрытые настройки фейк-включения: случайный интервал min..max, собственное
 * окно работы и предупреждение о «Часах тишины». Те же компоненты, что у
 * обычного микрозвука.
 */
@Composable
private fun FakePinnedMicrosoundSettings(
    settings: PinnedMicrosoundSettings,
    onIntervalChange: (minIntervalMinutes: Int, maxIntervalMinutes: Int) -> Unit,
    onWindowChange: (MicrosoundWindow?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    var minIntervalText by rememberSaveable(settings.fakeMinIntervalMinutes) {
        mutableStateOf(settings.fakeMinIntervalMinutes.toString())
    }
    var maxIntervalText by rememberSaveable(settings.fakeMaxIntervalMinutes) {
        mutableStateOf(settings.fakeMaxIntervalMinutes.toString())
    }

    // Собственное окно фейка: выключено = «весь день» (ключей в DataStore нет).
    var windowEnabled by rememberSaveable(settings.fakeWindow) {
        mutableStateOf(settings.fakeWindow != null)
    }
    var windowStart by rememberSaveable(settings.fakeWindow) {
        mutableIntStateOf(
            settings.fakeWindow?.startMinuteOfDay ?: MicrosoundWindow.DEFAULT_START_MINUTE_OF_DAY
        )
    }
    var windowEnd by rememberSaveable(settings.fakeWindow) {
        mutableIntStateOf(
            settings.fakeWindow?.endMinuteOfDay ?: MicrosoundWindow.DEFAULT_END_MINUTE_OF_DAY
        )
    }

    val minInterval = minIntervalText.toIntOrNull() ?: -1
    val maxInterval = maxIntervalText.toIntOrNull() ?: -1
    val isIntervalValid = MicrosoundIntervalRule.isValid(minInterval, maxInterval)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pinned_fake_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        MicrosoundIntervalFields(
            minIntervalText = minIntervalText,
            maxIntervalText = maxIntervalText,
            onMinIntervalChange = { value ->
                minIntervalText = value
                val parsedMin = value.toIntOrNull() ?: -1
                if (MicrosoundIntervalRule.isValid(parsedMin, maxInterval)) {
                    onIntervalChange(parsedMin, maxInterval)
                }
            },
            onMaxIntervalChange = { value ->
                maxIntervalText = value
                val parsedMax = value.toIntOrNull() ?: -1
                if (MicrosoundIntervalRule.isValid(minInterval, parsedMax)) {
                    onIntervalChange(minInterval, parsedMax)
                }
            },
            showValidationError = !isIntervalValid
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // Окно работы фейка: тот же компонент, что в редакторе микрозвука.
        // Некорректное окно (начало == конец) не сохраняется — показывается
        // ошибка, сохранённое значение остаётся прежним.
        MicrosoundWindowField(
            enabled = windowEnabled,
            startMinuteOfDay = windowStart,
            endMinuteOfDay = windowEnd,
            onEnabledChange = { enabled ->
                windowEnabled = enabled
                when {
                    !enabled -> onWindowChange(null)
                    windowStart != windowEnd -> onWindowChange(
                        MicrosoundWindow(windowStart, windowEnd)
                    )
                }
            },
            onTimeChange = { start, end ->
                windowStart = start
                windowEnd = end
                if (start != end) {
                    onWindowChange(MicrosoundWindow(start, end))
                }
            }
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        // Отдельного выбора звука у фейка НЕТ: он всегда играет звук
        // «Включения колонки» с его громкостью (см. PinnedMicrosoundSettings).
        Text(
            text = stringResource(R.string.pinned_fake_sound_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Единые поля случайного интервала min..max для обычного микрозвука и фейка:
 * одинаковые ограничения, фильтрация ввода и сообщение об ошибке.
 */
@Composable
private fun MicrosoundIntervalFields(
    minIntervalText: String,
    maxIntervalText: String,
    onMinIntervalChange: (String) -> Unit,
    onMaxIntervalChange: (String) -> Unit,
    showValidationError: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    // «Готово» на числовой клавиатуре снимает фокус и прячет клавиатуру.
    val doneActions = rememberDoneKeyboardActions()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.microsounds_interval_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(dimens.spacingXs))

        Row(
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = minIntervalText,
                onValueChange = { value ->
                    onMinIntervalChange(value.filter { char -> char.isDigit() }.take(4))
                },
                label = { Text(stringResource(R.string.microsounds_interval_min_label)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = doneActions,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = maxIntervalText,
                onValueChange = { value ->
                    onMaxIntervalChange(value.filter { char -> char.isDigit() }.take(4))
                },
                label = { Text(stringResource(R.string.microsounds_interval_max_label)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = doneActions,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.weight(1f)
            )
        }

        if (showValidationError) {
            Spacer(modifier = Modifier.height(dimens.spacingXs))
            Text(
                text = stringResource(R.string.microsounds_validation_interval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Единые цвета ВСЕХ переключателей на экране «Микрозвуки»: карточек
 * микрозвуков, «Включения колонки» и фейка. Вынесены в один помощник, чтобы
 * выключенный переключатель нигде не выглядел пустым серым блоком.
 *
 * Причина бага: в тёмной теме `outline` и `surfaceVariant` — один и тот же
 * цвет `#30302E`, а Material 3 по умолчанию красит невыбранные thumb и track
 * именно ими. Поэтому у выключенного микрозвука бегунок сливался с дорожкой.
 * Здесь thumb контрастен дорожке, а дорожка заметна на любой из поверхностей
 * карточек (`surfaceContainer`, `surfaceContainerHigh`).
 */
@Composable
internal fun microsoundSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline
)

/**
 * ЕДИНАЯ компактная карточка микрозвука для ВСЕХ типов: «Включение колонки»,
 * фейк и пользовательские микрозвуки. Вид одинаковый — название (до двух
 * строк), ОДНА строка ключевых параметров, необязательное предупреждение о
 * «Часах тишины», необязательная иконка справки и главный переключатель.
 *
 * Тап по шапке ОБЯЗАТЕЛЬНО открывает модальное окно ([onClick]): у своего
 * микрозвука — существующий редактор, у встроенного — окно его настроек.
 * Раскрытия прямо в карточке нет, поэтому высота списка предсказуема.
 *
 * Меню «Удалить» есть только при переданном [onDelete] (долгое нажатие, как у
 * карточки расписания). [helpTopic] добавляет в строку карточки иконку «i»
 * ([HelpIcon]) — у «Включения колонки» и фейк-включения она стоит перед
 * переключателем и НИКОГДА не переключает карточку. [quietConflict] рисует
 * производное от живых Flow предупреждение о конфликте с «Часами тишины»
 * ([MicrosoundQuietHoursWarning]) прямо в основном (единственном) виде карточки.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicrosoundCard(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quietConflict: MicrosoundQuietHoursConflict = MicrosoundQuietHoursConflict.NONE,
    onDelete: (() -> Unit)? = null,
    helpTopic: HelpTopic? = null
) {
    val dimens = AppTheme.dimens
    val hapticFeedback = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val longClickLabel = stringResource(R.string.microsounds_card_long_press_label)
    val openSettingsLabel = stringResource(R.string.microsound_card_open_settings)

    Box {
        AppCard(modifier = modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onClickLabel = openSettingsLabel,
                        onLongClickLabel = if (onDelete != null) longClickLabel else null,
                        onLongClick = if (onDelete != null) {
                            {
                                hapticFeedback.performHapticFeedback(
                                    HapticFeedbackType.LongPress
                                )
                                menuExpanded = true
                            }
                        } else {
                            null
                        }
                    )
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (checked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingXs))

                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Конфликт с «Часами тишины» виден СРАЗУ, без раскрытия и
                    // без окна: состояние производно от тех же живых Flow, что
                    // и планировщик, поэтому обновляется само.
                    if (quietConflict != MicrosoundQuietHoursConflict.NONE) {
                        Spacer(modifier = Modifier.height(dimens.spacingXs))
                        MicrosoundQuietHoursWarning(conflict = quietConflict)
                    }
                }

                Spacer(modifier = Modifier.width(dimens.spacingSm))

                // Иконка «i» ЕДИНОГО вида: в строке карточки, перед
                // переключателем, у «Включения колонки» (SPEAKER_POWER) и
                // фейк-включения (FAKE_SPEAKER). Тап забирает сама иконка и
                // НИКОГДА не переключает карточку.
                if (helpTopic != null) {
                    HelpIcon(topic = helpTopic)
                }

                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = microsoundSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = title }
                )
            }
        }

        // Единственный пункт меню — «Удалить». Цвета и типографика из темы.
        if (onDelete != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.microsounds_delete),
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
}

/** Одна строка ключевых параметров обычного микрозвука. */
@Composable
private fun microsoundSummary(microsound: MicrosoundEntity): String {
    val gainPercent = (microsound.gain * 100).toInt()
    val window = microsound.window
    return if (window == null) {
        stringResource(
            R.string.microsounds_card_summary,
            microsound.minIntervalMinutes,
            microsound.maxIntervalMinutes,
            gainPercent
        )
    } else {
        stringResource(
            R.string.microsounds_card_summary_window,
            microsound.minIntervalMinutes,
            microsound.maxIntervalMinutes,
            gainPercent,
            formatTimeOfDay(window.startMinuteOfDay),
            formatTimeOfDay(window.endMinuteOfDay)
        )
    }
}

/** Одна строка ключевых параметров «Включения колонки». */
@Composable
private fun pinnedSummary(settings: PinnedMicrosoundSettings, soundLabel: String): String {
    val gainPercent = (settings.gain * 100).toInt()
    val window = settings.window
    return if (window == null) {
        stringResource(R.string.pinned_microsound_summary, gainPercent, soundLabel)
    } else {
        stringResource(
            R.string.pinned_microsound_summary_window,
            gainPercent,
            formatTimeOfDay(window.startMinuteOfDay),
            formatTimeOfDay(window.endMinuteOfDay),
            soundLabel
        )
    }
}

/** Одна строка ключевых параметров фейк-включения. */
@Composable
private fun fakeSummary(settings: PinnedMicrosoundSettings): String {
    val window = settings.fakeWindow
    return if (window == null) {
        stringResource(
            R.string.pinned_fake_summary,
            settings.fakeMinIntervalMinutes,
            settings.fakeMaxIntervalMinutes
        )
    } else {
        stringResource(
            R.string.pinned_fake_summary_window,
            settings.fakeMinIntervalMinutes,
            settings.fakeMaxIntervalMinutes,
            formatTimeOfDay(window.startMinuteOfDay),
            formatTimeOfDay(window.endMinuteOfDay)
        )
    }
}

@Composable
fun MicrosoundEditDialog(
    initialMicrosound: MicrosoundEntity?,
    preview: SoundPreviewState,
    onDismiss: () -> Unit,
    onSave: (
        id: Long,
        title: String,
        uri: String,
        gain: Float,
        minIntervalMinutes: Int,
        maxIntervalMinutes: Int,
        window: MicrosoundWindow?,
        isEnabled: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    val dimens = AppTheme.dimens
    val defaultFileName = stringResource(R.string.microsounds_default_filename)

    var title by rememberSaveable { mutableStateOf(initialMicrosound?.title ?: "") }
    var selectedUri by rememberSaveable { mutableStateOf(initialMicrosound?.uri ?: "") }
    var gain by rememberSaveable { mutableFloatStateOf(initialMicrosound?.gain ?: 0.8f) }
    var minIntervalText by rememberSaveable {
        mutableStateOf((initialMicrosound?.minIntervalMinutes ?: 60).toString())
    }
    var maxIntervalText by rememberSaveable {
        mutableStateOf((initialMicrosound?.maxIntervalMinutes ?: 120).toString())
    }
    // Собственное окно работы: выключено у всех существующих микрозвуков (NULL).
    var windowEnabled by rememberSaveable { mutableStateOf(initialMicrosound?.window != null) }
    var windowStart by rememberSaveable {
        mutableIntStateOf(
            initialMicrosound?.window?.startMinuteOfDay
                ?: MicrosoundWindow.DEFAULT_START_MINUTE_OF_DAY
        )
    }
    var windowEnd by rememberSaveable {
        mutableIntStateOf(
            initialMicrosound?.window?.endMinuteOfDay
                ?: MicrosoundWindow.DEFAULT_END_MINUTE_OF_DAY
        )
    }
    // ОБЩЕЕ окно выбора звука: встроенный каталог и свой файл в одном месте.
    var showSoundPicker by rememberSaveable { mutableStateOf(false) }

    // Лаунчер SAF для выбора аудиофайла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            SafTrackImporter.takePersistablePermission(context, uri)
            selectedUri = uri.toString()
            var displayName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            displayName = cursor.getString(nameIndex) ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
            if (displayName.isBlank()) {
                displayName = uri.lastPathSegment ?: defaultFileName
            }
            title = displayName.substringBeforeLast('.')
        }
    }

    // Валидация
    val minInt = minIntervalText.toIntOrNull() ?: -1
    val maxInt = maxIntervalText.toIntOrNull() ?: -1

    // Источник звука: встроенный каталог (`builtin:<id>`) или свой файл (content://).
    val selectedBuiltin = BuiltinMicrosoundCatalog.findByStored(selectedUri)
    val isBuiltinPrefix = BuiltinMicrosoundCatalog.hasBuiltinPrefix(selectedUri)
    val isCustomFileSelected = selectedUri.isNotBlank() && !isBuiltinPrefix
    val isUnknownBuiltin = isBuiltinPrefix && selectedBuiltin == null
    val isSourceValid = selectedBuiltin != null || isCustomFileSelected

    val isIntervalValid = MicrosoundIntervalRule.isValid(minInt, maxInt)
    // Начало и конец окна не могут совпадать: такое окно не сохраняется.
    val isWindowValid = !windowEnabled || windowStart != windowEnd
    val isFormValid = isIntervalValid && isSourceValid && isWindowValid

    AlertDialog(
        onDismissRequest = onDismiss,
        // Тап по пустому месту диалога снимает фокус с полей интервала.
        // IME-inset здесь НЕ добавляется: окно диалога подстраивает система
        // (DialogProperties.decorFitsSystemWindows = true), иначе поверхность
        // диалога вырастает в пустой сплошной прямоугольник над клавиатурой.
        modifier = Modifier.dismissKeyboardOnTapOutside(),
        title = {
            Text(
                text = if (initialMicrosound == null) {
                    stringResource(R.string.microsounds_add_title)
                } else {
                    stringResource(R.string.microsounds_edit_title)
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            // Прокрутка вместо IME-padding: поле остаётся доступным, а
            // поверхность диалога не растягивается пустым прямоугольником.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Источник звука: ОДНО общее окно выбора (встроенный каталог
                // и свой файл), то же, что у мелодии сеанса и «Включения колонки».
                Text(
                    text = stringResource(R.string.microsounds_sound_section),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                SoundPickerTrigger(
                    label = microsoundSourceLabel(selectedUri, title),
                    onClick = { showSoundPicker = true }
                )

                if (isUnknownBuiltin) {
                    Spacer(modifier = Modifier.height(dimens.spacingXs))
                    Text(
                        text = stringResource(R.string.microsounds_builtin_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!isSourceValid) {
                    Spacer(modifier = Modifier.height(dimens.spacingXs))
                    Text(
                        text = stringResource(R.string.microsounds_validation_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // 2. Усиление звука (Gain Slider)
                val gainPercent = (gain * 100).toInt()
                Text(
                    text = stringResource(R.string.microsounds_gain_label, gainPercent),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Slider(
                    value = gain,
                    onValueChange = { gain = it },
                    valueRange = 0.05f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // 3. Интервалы min-max (общий компонент с фейк-включением колонки)
                MicrosoundIntervalFields(
                    minIntervalText = minIntervalText,
                    maxIntervalText = maxIntervalText,
                    onMinIntervalChange = { minIntervalText = it },
                    onMaxIntervalChange = { maxIntervalText = it },
                    showValidationError = !isIntervalValid
                )

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // 4. Собственное окно работы (Stage 3): «Свое время работы».
                // Тот же компонент и тот же лист выбора времени, что в «Часах тишины».
                MicrosoundWindowField(
                    enabled = windowEnabled,
                    startMinuteOfDay = windowStart,
                    endMinuteOfDay = windowEnd,
                    onEnabledChange = { windowEnabled = it },
                    onTimeChange = { start, end ->
                        windowStart = start
                        windowEnd = end
                    }
                )

            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = stringResource(R.string.microsounds_save),
                enabled = isFormValid,
                onClick = {
                    onSave(
                        initialMicrosound?.id ?: 0L,
                        title,
                        selectedUri,
                        gain,
                        minInt,
                        maxInt,
                        if (windowEnabled) MicrosoundWindow(windowStart, windowEnd) else null,
                        initialMicrosound?.isEnabled ?: true
                    )
                }
            )
        },
        dismissButton = {
            AppOutlinedButton(
                text = stringResource(R.string.microsounds_cancel),
                onClick = onDismiss
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )

    // ОБЩЕЕ окно выбора звука (то же, что у мелодии/плейлиста сеанса и
    // «Включения колонки»): сетка карточек с названием и предпрослушиванием.
    if (showSoundPicker) {
        SoundPickerDialog(
            title = stringResource(R.string.microsound_picker_title),
            items = microsoundPickerItems(
                packageName = context.packageName,
                customUri = selectedUri.takeIf { isCustomFileSelected },
                customTitle = title.ifBlank { defaultFileName }
            ),
            selectedKey = selectedUri.ifBlank { null },
            emptyText = stringResource(R.string.microsound_picker_empty),
            onDismiss = { showSoundPicker = false },
            onConfirm = { item ->
                showSoundPicker = false
                selectedUri = item.key
                // Название встроенного звука берётся из каталога, поэтому имя
                // прежнего файла в БД не сохраняем; у своего файла — имя файла.
                title = if (BuiltinMicrosoundCatalog.hasBuiltinPrefix(item.key)) {
                    ""
                } else {
                    item.title
                }
            },
            onImport = { filePickerLauncher.launch(arrayOf("audio/*")) },
            preview = preview
        )
    }
}

/**
 * Локализованное название выбранного микрозвука: встроенный — из каталога,
 * свой файл — сохранённое имя файла, неизвестный builtin — нейтральная подпись.
 */
@Composable
private fun microsoundSourceLabel(uri: String, storedTitle: String): String {
    val builtin = BuiltinMicrosoundCatalog.findByStored(uri)
    return when {
        builtin != null -> stringResource(builtin.nameRes)
        BuiltinMicrosoundCatalog.hasBuiltinPrefix(uri) ->
            stringResource(R.string.microsounds_builtin_unknown)
        else -> storedTitle.ifBlank { stringResource(R.string.track_unknown_title) }
    }
}
