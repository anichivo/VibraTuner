package com.personal.vibratuner.ui.components

import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.personal.vibratuner.R
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.music.MusicViewModel

/**
 * Один элемент ОБЩЕГО выбора звука. Карточка показывает ТОЛЬКО название
 * (до двух строк), поэтому отличий между треками, встроенными микрозвуками и
 * своими файлами не остаётся — меняется лишь источник данных.
 *
 * @param key стабильный ключ элемента (id трека/плейлиста, `builtin:<id>` или
 *   сохранённый URI своего файла).
 * @param title название звука.
 * @param subtitle необязательная вторая строка (число треков у плейлиста);
 *   у звуков её НЕТ — категории и подписи на карточке не выводятся.
 * @param previewUri URI для предпрослушивания; `null` — кнопка недоступна
 *   (плейлист, «Вся библиотека», неизвестный встроенный звук).
 * @param enabled `false` — выбрать элемент нельзя (пустой плейлист).
 * @param id числовой id трека/плейлиста для сохранения выбора.
 */
@Immutable
data class SoundPickerItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val previewUri: String? = null,
    val enabled: Boolean = true,
    val id: Long? = null
)

/**
 * Предпрослушивание звука из общего окна выбора: состояние приходит из
 * координатора сервиса, UI своего состояния не хранит — иначе иконка
 * play/pause могла бы разойтись с реально звучащим звуком.
 */
@Stable
class SoundPreviewState(
    val playingUri: String?,
    /** Покой или музыка на паузе: прослушивание разрешено. Звук/сеанс играет — нет. */
    val available: Boolean,
    val onToggle: (uri: String, title: String) -> Unit,
    val onStop: () -> Unit
)

/**
 * Единственный контроллер предпрослушивания: связан с существующей привязкой
 * `MusicViewModel` к сервису (второй привязки и второго плеера не появляется).
 * Для встроенных микрозвуков передаётся `android.resource://`-URI, для своих
 * файлов — их content-URI: предпросмотр идёт тем же путём координатора.
 */
@Composable
fun rememberSoundPreview(model: MusicViewModel): SoundPreviewState {
    val playingUri by model.previewTrackUri.collectAsState()
    val available by model.previewAvailable.collectAsState()
    val onToggle = remember(model) { { uri: String, title: String ->
        model.onPreviewToggle(uri, title)
    } }
    val onStop = remember(model) { { model.onPreviewStop() } }
    return remember(playingUri, available, onToggle, onStop) {
        SoundPreviewState(
            playingUri = playingUri,
            available = available,
            onToggle = onToggle,
            onStop = onStop
        )
    }
}

/**
 * ОБЩЕЕ модальное окно выбора звука: сетка компактных карточек, закреплённая
 * нижняя панель («Отмена»/«Сохранить») и один предпросмотр на всё окно.
 * Используется ВСЕМИ выборами звука/трека приложения (мелодия сеанса,
 * плейлист сеанса, встроенный микрозвук, свой файл) — второго окна быть не
 * должно. Отличаются только заголовок и источник данных.
 *
 * Выбор НЕ применяется сразу: нажатие на карточку только отмечает её,
 * «Сохранить» отдаёт выбранный элемент через [onConfirm], «Отмена», свайп/Back
 * и тап вне окна ничего не меняют. Сохранённый звук предвыбран и прокручен в вид.
 *
 * Предпрослушивание останавливается при любом закрытии окна, сохранении,
 * уходе приложения в фон ([LifecycleEventEffect] ON_STOP) и в конце файла
 * (событие приходит из координатора). Кнопка предпросмотра — отдельная
 * область нажатия 48 dp и выбор НЕ меняет.
 */
@Composable
fun SoundPickerDialog(
    title: String,
    items: List<SoundPickerItem>,
    selectedKey: String?,
    emptyText: String,
    onConfirm: (SoundPickerItem) -> Unit,
    onDismiss: () -> Unit,
    onImport: (() -> Unit)? = null,
    preview: SoundPreviewState? = null
) {
    val dimens = AppTheme.dimens

    // Уход в фон останавливает предпрослушивание; окно остаётся открытым.
    if (preview != null) {
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.onStop() }
    }
    // Закрытие/сохранение/уход из композиции: второй проигрывающий карточки нет.
    DisposableEffect(Unit) {
        onDispose { preview?.onStop() }
    }

    // Несохранённый выбор. Внешняя смена сохранённого звука (в том числе после
    // импорта файла) переводит выбор на новый сохранённый элемент.
    var pendingKey by remember(selectedKey) { mutableStateOf(selectedKey) }
    val pendingItem = items.firstOrNull { it.key == pendingKey && it.enabled }

    // Высота сетки: ровно столько рядов, сколько нужно (без пустого «хвоста»),
    // но не выше предела — тогда список прокручивается. Ряды считаются по
    // минимальной высоте карточки из Dimens. Part 3: если элементов больше, чем
    // полных рядов, снизу добавляется видимая полоска следующего ряда — вместе с
    // полосой прокрутки она показывает, что список продолжается.
    val rows = ((items.size + dimens.soundPickerColumns - 1) / dimens.soundPickerColumns)
        .coerceAtLeast(1)
    val maxVisibleRows = (
        (dimens.soundPickerGridMaxHeight + dimens.spacingSm) /
            (dimens.soundPickerCardMinHeight + dimens.spacingSm)
        ).toInt().coerceAtLeast(1)
    val visibleRows = rows.coerceAtMost(maxVisibleRows)
    val rowsHeight = dimens.soundPickerCardMinHeight * visibleRows +
        dimens.spacingSm * (visibleRows - 1)
    val gridHeight = if (rows > visibleRows) {
        rowsHeight + dimens.soundPickerNextRowPeek
    } else {
        rowsHeight
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedKey, items.size) {
        val index = items.indexOfFirst { it.key == selectedKey }
        if (index >= 0) gridState.scrollToItem(index)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            // Полноэкранное окно с ПЕРВОГО кадра: промежуточного кадра с пустой
            // тёмной поверхностью во весь экран не бывает.
            usePlatformDefaultWidth = false
        )
    ) {
        PickerWindowChrome()

        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val appearProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(
                durationMillis = dimens.soundPickerAppearDurationMs,
                easing = FastOutSlowInEasing
            ),
            label = "soundPickerAppear"
        )
        val scale = dimens.soundPickerAppearInitialScale +
            (1f - dimens.soundPickerAppearInitialScale) * appearProgress

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Полноэкранное окно диалога не даёт платформе поймать «внешний»
                // тап — закрываем окно тапом по пустому месту сами.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(dimens.spacingLg),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(dimens.radiusLg),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    dimens.cardBorderWidth,
                    MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .widthIn(max = dimens.soundPickerMaxWidth)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appearProgress
                        scaleX = scale
                        scaleY = scale
                    }
                    // Поверхность поглощает тапы: они не закрывают окно.
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(modifier = Modifier.padding(dimens.spacingLg)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Импорт своего звука: одна компактная вторичная кнопка,
                        // стоящая одинаково во всех окнах выбора. Открывает
                        // ПРЕЖНИЙ поток импорта (SAF), логика не переписана.
                        if (onImport != null) {
                            Spacer(modifier = Modifier.width(dimens.spacingSm))
                            TextButton(
                                onClick = onImport,
                                modifier = Modifier.defaultMinSize(
                                    minHeight = dimens.minTouchTarget
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(dimens.iconSizeSm)
                                )
                                Spacer(modifier = Modifier.width(dimens.spacingXs))
                                Text(
                                    text = stringResource(R.string.sound_picker_import),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dimens.spacingMd))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(dimens.soundPickerColumns),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                        verticalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Вес БЕЗ заполнения: сетка получает не больше
                            // свободного места окна, поэтому на маленьком экране,
                            // в горизонтальной ориентации и при крупном системном
                            // шрифте шапка и кнопки остаются видны, а на обычном
                            // телефоне сетка растёт до новой высоты из Dimens.
                            .weight(1f, fill = false)
                            .height(gridHeight)
                            // Тонкая полоса прокрутки справа: появляется только
                            // когда содержимое больше окна.
                            .scrollIndicator(gridState)
                    ) {
                        items(items = items, key = { it.key }) { item ->
                            SoundPickerCard(
                                item = item,
                                selected = item.key == pendingKey,
                                preview = preview,
                                onSelect = {
                                    if (item.enabled) pendingKey = item.key
                                }
                            )
                        }
                        if (items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = emptyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dimens.spacingMd))

                    // Закреплённая нижняя панель: всегда видна, список не прокручивает её.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AppOutlinedButton(
                            text = stringResource(R.string.sound_picker_cancel),
                            onClick = onDismiss
                        )
                        Spacer(modifier = Modifier.width(dimens.spacingSm))
                        AppPrimaryButton(
                            text = stringResource(R.string.sound_picker_save),
                            enabled = pendingItem != null,
                            onClick = { pendingItem?.let(onConfirm) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Компактная карточка сетки: название (до двух строк), необязательная вторая
 * строка и кнопка предпросмотра. Выбранная карточка — рамкой `primary` 2 dp и
 * галочкой, невыбранная — нейтральной рамкой темы; смена состояния — плавная
 * (150 мс), без сдвига разметки: слот галочки зарезервирован всегда.
 */
@Composable
private fun SoundPickerCard(
    item: SoundPickerItem,
    selected: Boolean,
    preview: SoundPreviewState?,
    onSelect: () -> Unit
) {
    val dimens = AppTheme.dimens
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(durationMillis = dimens.soundPickerSelectionAnimationMs),
        label = "soundPickerBorderColor"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(durationMillis = dimens.soundPickerSelectionAnimationMs),
        label = "soundPickerContainerColor"
    )
    val isPreviewing = preview != null &&
        item.previewUri != null &&
        preview.playingUri == item.previewUri
    val titleColor = if (item.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(dimens.radiusMd),
        color = containerColor,
        border = BorderStroke(
            if (selected) dimens.soundPickerSelectedBorderWidth else dimens.cardBorderWidth,
            borderColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                // Выбор — тап по телу карточки; состояние выбора доступно
                // экранному диктору (selectable + selected).
                .selectable(
                    selected = selected,
                    enabled = item.enabled,
                    role = Role.RadioButton,
                    onClick = onSelect
                )
                .fillMaxWidth()
                .heightIn(min = dimens.soundPickerCardMinHeight)
                .padding(dimens.spacingSm)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.subtitle != null) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Слот галочки зарезервирован всегда — название не «прыгает».
                Box(
                    modifier = Modifier.size(dimens.iconSizeSm),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.sound_picker_selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dimens.iconSizeSm)
                        )
                    }
                }
            }

            if (preview != null) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        // Пока играет музыка/микрозвук/сеанс, прослушивание
                        // недоступно: кнопка приглушена вместо «мёртвого» нажатия.
                        enabled = item.previewUri != null &&
                            (preview.available || isPreviewing),
                        onClick = {
                            val uri = item.previewUri ?: return@IconButton
                            if (isPreviewing) preview.onStop() else preview.onToggle(uri, item.title)
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (isPreviewing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (isPreviewing) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = stringResource(
                                if (isPreviewing) {
                                    R.string.sound_picker_preview_pause
                                } else {
                                    R.string.sound_picker_preview_play
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Настройка окна выбора: окно открывается ПОВЕРХ редактора, поэтому ему не нужны
 * ни собственное системное затемнение, ни системная анимация окна (иначе экран
 * на кадр темнеет до появления содержимого). Заодно отключается принудительный
 * контрастный scrim навбара: у окна диалога он свой.
 */
@Composable
private fun PickerWindowChrome() {
    val view = LocalView.current
    SideEffect {
        val window = view.findDialogWindow() ?: return@SideEffect
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        PlatformCompat.disableNavigationBarContrastEnforced(window)
    }
}

/** Окно Compose-диалога: им является `DialogLayout` (реализует `DialogWindowProvider`). */
private fun View.findDialogWindow(): android.view.Window? {
    var current: Any? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = (current as? View)?.parent
    }
    return null
}
