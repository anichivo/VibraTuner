package com.personal.vibratuner.ui.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.alarm.AlarmCalculator
import com.personal.vibratuner.data.local.db.AlarmEntity
import com.personal.vibratuner.data.local.db.AlarmSoundSource
import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistSummary
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard
import com.personal.vibratuner.ui.components.AppOutlinedButton
import com.personal.vibratuner.ui.components.AppPrimaryButton
import com.personal.vibratuner.ui.components.AppTimeValue
import com.personal.vibratuner.ui.components.SoundPickerDialog
import com.personal.vibratuner.ui.components.SoundPickerItem
import com.personal.vibratuner.ui.components.SoundPreviewState
import com.personal.vibratuner.ui.components.TimePickerSheet
import com.personal.vibratuner.ui.components.formatDurationWords
import com.personal.vibratuner.ui.components.formatTrackDuration
import java.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * Результат редактора будильника. Единая точка передачи данных из формы
 * в `ScheduleViewModel.saveAlarm`: источник звука и связанные с ним поля
 * не зависят от позиции аргументов.
 *
 * [catalogPlaylistId] — плейлист каталога «От автора», которого нет в «Моей
 * коллекции»: тогда [playlistId] = null, а треки читаются при срабатывании
 * из кеша манифеста (`alarms.catalogPlaylistId`, v11).
 */
data class AlarmEditResult(
    val hour: Int,
    val minute: Int,
    val durationMinutes: Int,
    val days: Set<DayOfWeek>,
    val source: AlarmSoundSource,
    val trackId: Long?,
    val playlistId: Long?,
    val shuffle: Boolean,
    val gain: Float,
    val catalogPlaylistId: String? = null
)

/**
 * Плейлист каталога «От автора» в редакторе будильника: стабильный id каталога,
 * имя и число треков (имя может быть неизвестно, если кеш манифеста недоступен —
 * ссылка всё равно сохраняется).
 */
data class CatalogPlaylistRef(
    val catalogId: String,
    val name: String? = null,
    val trackCount: Int = 0
)

/**
 * Единый редактор будильника. Используется и «Расписанием», и долгим нажатием
 * на трек в «Музыке», и кнопкой «Запланировать» на экране плейлиста —
 * второй копии редактора быть не должно.
 *
 * @param initialAlarm Редактируемый будильник или null для нового.
 * @param tracks Библиотека треков для выбора мелодии.
 * @param playlists Локальные плейлисты (включая «Любимое») для источника PLAYLIST.
 * @param onSave Возвращает время, длительность, дни недели, источник звука
 *        (трек/плейлист), выбранные id, shuffle и громкость будильника.
 * @param initialTrackId Предвыбранный трек нового будильника (долгое нажатие в «Музыке»).
 * @param initialPlaylistId Предвыбранный плейлист (кнопка «Запланировать»).
 * @param initialSource Источник звука нового будильника (по умолчанию TRACK).
 * @param initialShuffle Начальное состояние «Случайного порядка».
 * @param initialCatalogPlaylist Плейлист «От автора» для источника PLAYLIST:
 *        его можно запланировать, НЕ добавляя в «Мою коллекцию» и НЕ скачивая.
 * @param preview Предпрослушивание звука в общем окне выбора: состояние из
 *        координатора сервиса и команды. `null` — окно работает без
 *        предпрослушивания (внешний вид при этом не меняется).
 * @param onImportFiles Импорт треков из файлов устройства ПРЕЖНИМ потоком
 *        (`MusicViewModel.onAddFiles`). `null` — кнопка импорта не показывается.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditDialog(
    initialAlarm: AlarmEntity?,
    tracks: List<TrackEntity>,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onSave: (AlarmEditResult) -> Unit,
    initialTrackId: Long? = initialAlarm?.trackId,
    initialPlaylistId: Long? = initialAlarm?.playlistId,
    initialSource: AlarmSoundSource = AlarmSoundSource.fromValue(initialAlarm?.sourceType),
    initialShuffle: Boolean = initialAlarm?.shuffle ?: false,
    initialCatalogPlaylist: CatalogPlaylistRef? = null,
    preview: SoundPreviewState? = null,
    onImportFiles: ((List<Uri>) -> Unit)? = null
) {
    val dimens = AppTheme.dimens
    var selectedHour by rememberSaveable {
        mutableIntStateOf(initialAlarm?.hour ?: 7)
    }
    var selectedMinute by rememberSaveable {
        mutableIntStateOf(initialAlarm?.minute ?: 0)
    }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDurationPicker by rememberSaveable { mutableStateOf(false) }
    var showTrackPicker by rememberSaveable { mutableStateOf(false) }
    var showPlaylistPicker by rememberSaveable { mutableStateOf(false) }
    // Длительность по-прежнему хранится в минутах. Старые значения вне
    // диапазона (например, больше 12 часов) зажимаются для показа в редакторе;
    // сами данные и логика будильника не меняются.
    var durationMinutes by rememberSaveable {
        mutableIntStateOf(
            AlarmDuration.clampStoredMinutes(
                initialAlarm?.durationMinutes ?: AlarmDuration.DEFAULT_MINUTES
            )
        )
    }
    var selectedDays by rememberSaveable(
        stateSaver = listSaver<Set<DayOfWeek>, Int>(
            save = { days -> days.map { it.value } },
            restore = { values -> values.map { DayOfWeek.of(it) }.toSet() }
        )
    ) {
        mutableStateOf(
            if (initialAlarm != null) {
                AlarmCalculator.parseDaysOfWeek(initialAlarm.daysOfWeek)
            } else {
                emptySet()
            }
        )
    }
    var source by rememberSaveable { mutableStateOf(initialSource) }
    // -1L — маркер «Вся библиотека», чтобы состояние сохранялось в Bundle.
    var selectedTrackId by rememberSaveable { mutableLongStateOf(initialTrackId ?: -1L) }
    // -1L — плейлист не выбран (или удалён).
    var selectedPlaylistId by rememberSaveable { mutableLongStateOf(initialPlaylistId ?: -1L) }
    // Плейлист каталога «От автора»: id, имя и число треков сохраняются в Bundle
    // по отдельности (String/Int), чтобы выбор пережил поворот экрана.
    var selectedCatalogId by rememberSaveable {
        mutableStateOf(initialCatalogPlaylist?.catalogId)
    }
    var selectedCatalogName by rememberSaveable {
        mutableStateOf(initialCatalogPlaylist?.name)
    }
    var selectedCatalogTrackCount by rememberSaveable {
        mutableIntStateOf(initialCatalogPlaylist?.trackCount ?: 0)
    }
    var playlistShuffle by rememberSaveable { mutableStateOf(initialShuffle) }
    var alarmGain by rememberSaveable {
        mutableFloatStateOf(initialAlarm?.gain ?: AlarmEntity.DEFAULT_GAIN)
    }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    val favoritesTitle = stringResource(R.string.playlist_favorites_name)
    val selectedTrack = remember(selectedTrackId, tracks) {
        tracks.firstOrNull { it.id == selectedTrackId }
    }
    val melodyTitle = selectedTrack?.title ?: stringResource(R.string.schedule_all_library)
    // Фидбек: исполнитель не показывается; на его месте — длительность трека.
    val melodyDuration = formatTrackDuration(selectedTrack?.durationMs ?: 0L)

    // Плейлист мог быть удалён: тогда он показывается как «не выбран».
    val selectedPlaylist = remember(selectedPlaylistId, playlists) {
        playlists.firstOrNull { it.id == selectedPlaylistId }
    }
    // Плейлист каталога выбирается БЕЗ добавления в библиотеку: в списке локальных
    // плейлистов его нет, поэтому он хранится отдельными полями и приоритетнее.
    val isCatalogPlaylistSelected = selectedCatalogId != null
    val isPlaylistSelected = isCatalogPlaylistSelected || selectedPlaylist != null
    val playlistDisplayName = when {
        isCatalogPlaylistSelected ->
            // Имя плейлиста каталога может быть ещё не прочитано из кеша манифеста:
            // показываем нейтральную подпись и НЕ теряем ссылку при сохранении.
            selectedCatalogName ?: stringResource(R.string.schedule_catalog_playlist_label)
        selectedPlaylist == null -> stringResource(R.string.schedule_playlist_missing)
        selectedPlaylist.isSystem ||
            selectedPlaylist.id == PlaylistEntity.FAVORITES_PLAYLIST_ID -> favoritesTitle
        else -> selectedPlaylist.name
    }
    val playlistTrackCount = if (isCatalogPlaylistSelected) {
        selectedCatalogTrackCount
    } else {
        selectedPlaylist?.trackCount ?: 0
    }
    // Пустой плейлист выбрать нельзя (сигнал пропускался бы), каталог — можно.
    val playlistReady = isCatalogPlaylistSelected ||
        (selectedPlaylist != null && selectedPlaylist.trackCount > 0)

    // Импорт треков из файлов — ПРЕЖНИЙ поток (SAF + `MusicViewModel.onAddFiles`),
    // открывается компактной кнопкой «Добавить» в шапке общего окна выбора.
    // После импорта новый трек появляется в списке и сразу становится выбранным.
    var importBaseTrackIds by remember { mutableStateOf<Set<Long>?>(null) }
    val importTracksLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val importFiles = onImportFiles
        if (uris.isNotEmpty() && importFiles != null) {
            importBaseTrackIds = tracks.mapTo(HashSet()) { it.id }
            importFiles(uris)
        }
    }
    // Список треков обновляется существующим Flow: выбираем первый добавленный.
    LaunchedEffect(tracks, importBaseTrackIds) {
        val base = importBaseTrackIds ?: return@LaunchedEffect
        val added = tracks.filter { it.id !in base }.maxByOrNull { it.id }
        if (added != null) {
            selectedTrackId = added.id
            importBaseTrackIds = null
        }
    }

    val daysMap = listOf(
        DayOfWeek.MONDAY to stringResource(R.string.day_mon),
        DayOfWeek.TUESDAY to stringResource(R.string.day_tue),
        DayOfWeek.WEDNESDAY to stringResource(R.string.day_wed),
        DayOfWeek.THURSDAY to stringResource(R.string.day_thu),
        DayOfWeek.FRIDAY to stringResource(R.string.day_fri),
        DayOfWeek.SATURDAY to stringResource(R.string.day_sat),
        DayOfWeek.SUNDAY to stringResource(R.string.day_sun)
    )

    val scrollState = rememberScrollState()
    val validationErrorMsg = stringResource(R.string.schedule_validation_error)
    val playlistValidationErrorMsg = stringResource(R.string.schedule_playlist_validation_error)
    val durationLabel = stringResource(R.string.schedule_duration_label)
    val durationValueText = formatDurationWords(
        AlarmDuration.clampStoredMinutes(durationMinutes)
    )
    val durationContentDescription = stringResource(
        R.string.schedule_duration_value_content_desc,
        durationValueText
    )
    // Быстрые пресеты длительности: подписи берутся из ЕДИНСТВЕННОГО форматтера
    // («15 мин», «30 мин», «1 час», …, «24 часа»), отдельного текста для них нет.
    // Максимум 1440 минут (24 часа) уже поддерживается AlarmDuration.
    val durationPresets = remember { listOf(15, 30, 60, 120, 240, 480, 720, 1440) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(
                    if (initialAlarm == null) R.string.schedule_add_alarm else R.string.schedule_edit_alarm
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            // Полей ввода здесь нет (время и длительность выбираются нижними
            // листами), поэтому IME-inset в окно диалога не добавляется:
            // IME-отступ применяется ровно один раз на окно.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // Время: крупное значение, нажатие открывает нижний лист с двумя
                // колёсами «часы : минуты» (единый нижний лист, без системного диалога).
                Text(
                    text = stringResource(R.string.schedule_time_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(dimens.spacingSm))

                AppTimeValue(
                    hour = selectedHour,
                    minute = selectedMinute,
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // Длительность сигнала: то же поле-значение, что и у времени, —
                // тап открывает нижний лист с колёсами часов 0..24 и минут 0..59.
                // Справа видна иконка редактирования; выбранное значение
                // показывается читаемой формой «1 ч 30 мин», а не числом минут.
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(dimens.spacingSm))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = dimens.minTouchTarget)
                        .clickable(
                            role = Role.Button,
                            onClick = { showDurationPicker = true }
                        )
                        .semantics { contentDescription = durationContentDescription },
                    shape = RoundedCornerShape(dimens.radiusMd),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        dimens.cardBorderWidth,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = dimens.spacingMd,
                            vertical = dimens.spacingSm
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = durationValueText,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(dimens.iconSizeMd)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                Text(
                    text = stringResource(R.string.schedule_duration_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(dimens.spacingSm))

                // Быстрые пресеты: FilterChip в переносящемся FlowRow. Восемь
                // чипов (15 мин … 24 часа) не помещаются в один ряд даже на
                // широком экране, а горизонтальная прокрутка прятала бы часть
                // пресетов; перенос показывает их все сразу. Отступы — из Dimens.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingXs)
                ) {
                    durationPresets.forEach { preset ->
                        val presetSelected = durationMinutes == preset
                        FilterChip(
                            selected = presetSelected,
                            onClick = {
                                durationMinutes = preset
                                errorText = null
                            },
                            label = { Text(formatDurationWords(preset)) },
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
                                selected = presetSelected,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderWidth = dimens.cardBorderWidth,
                                selectedBorderWidth = dimens.cardBorderWidth
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // Выбор дней недели
                Text(
                    text = stringResource(R.string.schedule_days_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                // Дни недели: РОВНО семь равных по весу переключателей в одном
                // ряду. Ширину задаёт weight(1f), поэтому «Сб» и «Вс» больше
                // не переносятся на вторую строку даже на узком телефоне.
                // Масштаб шрифта подписи ограничен Dimens, чтобы двухбуквенные
                // сокращения не обрезались при крупном системном шрифте.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = density.fontScale.coerceAtMost(
                            dimens.weekdayLabelMaxFontScale
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            dimens.weekdayToggleSpacing
                        )
                    ) {
                        daysMap.forEach { (day, name) ->
                            WeekdayToggle(
                                label = name,
                                selected = day in selectedDays,
                                onClick = {
                                    selectedDays = if (day in selectedDays) {
                                        selectedDays - day
                                    } else {
                                        selectedDays + day
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                Text(
                    text = if (selectedDays.isEmpty()) {
                        stringResource(R.string.schedule_one_time)
                    } else {
                        formatDaysSummary(selectedDays)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // Источник звука: один трек (как раньше) или локальный плейлист.
                Text(
                    text = stringResource(R.string.schedule_source_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(dimens.spacingSm))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = source == AlarmSoundSource.TRACK,
                        onClick = {
                            source = AlarmSoundSource.TRACK
                            errorText = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(stringResource(R.string.schedule_source_track)) }
                    )
                    SegmentedButton(
                        selected = source == AlarmSoundSource.PLAYLIST,
                        onClick = {
                            source = AlarmSoundSource.PLAYLIST
                            errorText = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(stringResource(R.string.schedule_source_playlist)) }
                    )
                }

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                if (source == AlarmSoundSource.TRACK) {
                    // Мелодия: выбранный трек или вся библиотека
                    Text(
                        text = stringResource(R.string.schedule_melody_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingSm))

                    AppCard(
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = melodyTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (melodyDuration != null) {
                                    Text(
                                        text = melodyDuration,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(dimens.spacingSm))

                            AppOutlinedButton(
                                text = stringResource(R.string.schedule_change_track),
                                onClick = { showTrackPicker = true }
                            )
                        }
                    }
                } else {
                    // Плейлист: только локальные (включая «Любимое»), пустые недоступны.
                    Text(
                        text = stringResource(R.string.schedule_playlist_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingSm))

                    AppCard(
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlistDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isPlaylistSelected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isPlaylistSelected && playlistTrackCount > 0) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.playlist_track_count,
                                            playlistTrackCount,
                                            playlistTrackCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(dimens.spacingSm))

                            AppOutlinedButton(
                                text = stringResource(R.string.schedule_change_track),
                                onClick = { showPlaylistPicker = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(dimens.spacingMd))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.schedule_playlist_shuffle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.schedule_playlist_shuffle_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = playlistShuffle,
                                onCheckedChange = { playlistShuffle = it },
                                enabled = isPlaylistSelected,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // Громкость сеанса: усиление применяется к сигналу (0..1)
                Text(
                    text = stringResource(
                        R.string.schedule_alarm_gain_label,
                        (alarmGain * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                Text(
                    text = stringResource(R.string.schedule_alarm_gain_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Slider(
                    value = alarmGain,
                    onValueChange = { alarmGain = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                errorText?.let { err ->
                    Spacer(modifier = Modifier.height(dimens.spacingSm))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = stringResource(R.string.schedule_save),
                onClick = {
                    when {
                        !AlarmDuration.isValidMinutes(durationMinutes) -> {
                            errorText = validationErrorMsg
                        }
                        source == AlarmSoundSource.PLAYLIST && !playlistReady -> {
                            errorText = playlistValidationErrorMsg
                        }
                        else -> {
                            errorText = null
                            onSave(
                                AlarmEditResult(
                                    hour = selectedHour,
                                    minute = selectedMinute,
                                    durationMinutes = durationMinutes,
                                    days = selectedDays,
                                    source = source,
                                    trackId = selectedTrackId.takeIf { it > 0L },
                                    playlistId = if (
                                        source == AlarmSoundSource.PLAYLIST &&
                                        !isCatalogPlaylistSelected
                                    ) {
                                        selectedPlaylistId.takeIf { it > 0L }
                                    } else {
                                        null
                                    },
                                    shuffle = source == AlarmSoundSource.PLAYLIST && playlistShuffle,
                                    gain = alarmGain.coerceIn(0f, 1f),
                                    // Плейлист каталога сохраняется стабильным id:
                                    // добавлять его в библиотеку не нужно.
                                    catalogPlaylistId = if (source == AlarmSoundSource.PLAYLIST) {
                                        selectedCatalogId
                                    } else {
                                        null
                                    }
                                )
                            )
                        }
                    }
                }
            )
        },
        dismissButton = {
            AppOutlinedButton(
                text = stringResource(R.string.schedule_cancel),
                onClick = onDismiss
            )
        }
    )

    if (showTimePicker) {
        TimePickerSheet(
            title = stringResource(R.string.schedule_time_label),
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                selectedHour = h
                selectedMinute = m
                showTimePicker = false
            }
        )
    }

    // Длительность вводится нижним листом с колёсами часов 0..24 и минут 0..59.
    // Диапазон 1 минута .. 24 часа; 24 часа фиксируют минуты на 0, а 0 ч 0 мин
    // зажимаются до 1 минуты (правила — в AlarmDuration).
    if (showDurationPicker) {
        AlarmDurationPickerSheet(
            initialMinutes = durationMinutes,
            onDismiss = { showDurationPicker = false },
            onConfirm = { minutes ->
                // Значение сохраняется в минутах — модель данных не меняется.
                durationMinutes = AlarmDuration.clampStoredMinutes(minutes)
                errorText = null
                showDurationPicker = false
            }
        )
    }

    if (showTrackPicker) {
        // ОБЩЕЕ окно выбора звука (то же, что у плейлистов и микрозвуков):
        // отличается только заголовок и источник данных. Выбор применяется
        // ТОЛЬКО по «Сохранить», сохранённая мелодия предвыбрана и видна.
        SoundPickerDialog(
            title = stringResource(R.string.schedule_track_picker_title),
            items = trackPickerItems(
                tracks = tracks,
                allLibraryTitle = stringResource(R.string.schedule_all_library)
            ),
            selectedKey = if (selectedTrackId <= 0L) {
                ALL_LIBRARY_KEY
            } else {
                "track:$selectedTrackId"
            },
            emptyText = stringResource(R.string.schedule_track_picker_empty),
            onDismiss = { showTrackPicker = false },
            onConfirm = { item ->
                // null — «Вся библиотека»: прежний маркер -1L.
                selectedTrackId = item.id ?: -1L
                showTrackPicker = false
            },
            onImport = if (onImportFiles != null) {
                {
                    // Те же типы файлов, что у кнопки «+ Добавить» в «Моей коллекции».
                    importTracksLauncher.launch(
                        arrayOf("audio/*", "application/ogg", "application/x-flac")
                    )
                }
            } else {
                null
            },
            preview = preview
        )
    }

    if (showPlaylistPicker) {
        // То же окно, что у треков и микрозвуков. У плейлистов нет
        // предпросмотра, а пустой плейлист выбрать нельзя.
        SoundPickerDialog(
            title = stringResource(R.string.schedule_playlist_picker_title),
            items = playlistPickerItems(
                playlists = playlists,
                favoritesTitle = favoritesTitle
            ),
            selectedKey = "playlist:$selectedPlaylistId",
            emptyText = stringResource(R.string.schedule_playlist_picker_empty),
            onDismiss = { showPlaylistPicker = false },
            onConfirm = { item ->
                selectedPlaylistId = item.id ?: -1L
                // Выбор локального плейлиста снимает плейлист каталога:
                // у будильника ровно один источник звука.
                selectedCatalogId = null
                selectedCatalogName = null
                selectedCatalogTrackCount = 0
                errorText = null
                showPlaylistPicker = false
            }
        )
    }
}

/** Стабильный ключ строки «Вся библиотека» в общем окне выбора. */
private const val ALL_LIBRARY_KEY = "all_library"

/** Элементы окна выбора мелодии: «Вся библиотека» + библиотека (с предпрослушиванием). */
private fun trackPickerItems(
    tracks: List<TrackEntity>,
    allLibraryTitle: String
): List<SoundPickerItem> = buildList {
    add(
        SoundPickerItem(
            key = ALL_LIBRARY_KEY,
            title = allLibraryTitle
        )
    )
    tracks.forEach { track ->
        add(
            SoundPickerItem(
                key = "track:${track.id}",
                title = track.title,
                previewUri = track.uri,
                id = track.id
            )
        )
    }
}

/** Элементы окна выбора локального плейлиста (включая «Любимое»). */
@Composable
private fun playlistPickerItems(
    playlists: List<PlaylistSummary>,
    favoritesTitle: String
): List<SoundPickerItem> = playlists.map { playlist ->
    val isFavorites = playlist.isSystem ||
        playlist.id == PlaylistEntity.FAVORITES_PLAYLIST_ID
    SoundPickerItem(
        key = "playlist:${playlist.id}",
        title = if (isFavorites) favoritesTitle else playlist.name,
        subtitle = pluralStringResource(
            R.plurals.playlist_track_count,
            playlist.trackCount,
            playlist.trackCount
        ),
        // Пустой плейлист виден, но выбрать его нельзя: сеанс пропускался бы.
        enabled = playlist.trackCount > 0,
        id = playlist.id
    )
}

/**
 * Компактный переключатель дня недели для ряда из семи равных ячеек.
 * Состояние selected/unselected различается заливкой (акцент/контейнер)
 * и рамкой; содержимое центрировано в одной строке и не переносится.
 */
@Composable
private fun WeekdayToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = modifier
            .height(dimens.weekdayToggleHeight)
            .selectable(
                selected = selected,
                role = Role.Checkbox,
                onClick = onClick
            ),
        shape = RoundedCornerShape(dimens.radiusMd),
        color = containerColor,
        border = BorderStroke(dimens.cardBorderWidth, borderColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimens.weekdayToggleHorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun formatDaysSummary(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return stringResource(R.string.schedule_one_time)
    if (days.size == 7) return stringResource(R.string.schedule_everyday)

    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    if (days == weekdays) return stringResource(R.string.schedule_weekdays)

    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    if (days == weekend) return stringResource(R.string.schedule_weekends)

    val map = mapOf(
        DayOfWeek.MONDAY to stringResource(R.string.day_mon),
        DayOfWeek.TUESDAY to stringResource(R.string.day_tue),
        DayOfWeek.WEDNESDAY to stringResource(R.string.day_wed),
        DayOfWeek.THURSDAY to stringResource(R.string.day_thu),
        DayOfWeek.FRIDAY to stringResource(R.string.day_fri),
        DayOfWeek.SATURDAY to stringResource(R.string.day_sat),
        DayOfWeek.SUNDAY to stringResource(R.string.day_sun)
    )

    return days.sorted().mapNotNull { map[it] }.joinToString(", ")
}
