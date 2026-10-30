package com.personal.vibratuner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import com.personal.vibratuner.theme.AppTheme
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Переиспользуемое «колесо» выбора целого значения (Part 2).
 *
 * Чистый Compose: [LazyColumn] с фиксированной высотой строки, content padding
 * в половину высоты колеса и [rememberSnapFlingBehavior] — после остановки
 * прокрутки выбранная строка всегда стоит ровно по центру. Никаких
 * `android.widget.NumberPicker` и сторонних библиотек.
 *
 * В центре видна ОДНА крупная строка акцентным цветом темы, соседние строки
 * меньше и приглушены; за центральной строкой — тонкая подложка из темы.
 * Все размеры/прозрачности — из `AppDimens`, стили — из `Type.kt`.
 *
 * Поведение:
 * - при первом появлении колесо сразу показывает [selectedValue];
 * - [onSelectedChange] вызывается ТОЛЬКО после того, как прокрутка остановилась
 *   и центральной стала другая строка (никаких событий на каждый кадр);
 * - внешнее изменение [selectedValue] (например, блокировка минут при 24 часах
 *   или быстрый пресет) доскролливает колесо к новому значению;
 * - при крупном системном шрифте масштаб внутри колеса ограничен
 *   (`wheelPickerMaxFontScale`), поэтому строки не обрезаются и не «прыгают»;
 * - при `enabled = false` колесо не принимает жесты, приглушено и помечено
 *   disabled для служб доступности.
 *
 * @param values Список допустимых значений (обычно диапазон часов/минут).
 * @param selectedValue Текущее выбранное значение. Должно присутствовать в [values].
 * @param onSelectedChange Вызывается после остановки прокрутки, если центр изменился.
 * @param label Формат подписи строки (например, двузначные «07»).
 */
@Composable
fun WheelPicker(
    values: List<Int>,
    selectedValue: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (Int) -> String = { it.toString() },
    contentDescription: String? = null
) {
    if (values.isEmpty()) return

    val dimens = AppTheme.dimens
    val itemHeight = dimens.wheelItemHeight
    val visibleItems = dimens.wheelPickerVisibleItems.coerceAtLeast(1)
    val halfVisibleItems = visibleItems / 2
    val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Колбэк и значение читаем через rememberUpdatedState: эффект запускается
    // один раз на список и не пересоздаётся на каждый выбор родителя.
    val latestSelectedValue by rememberUpdatedState(selectedValue)
    val latestOnSelectedChange by rememberUpdatedState(onSelectedChange)

    val centeredIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.centeredItemIndex() }
    }

    // Выбор сообщается только после остановки прокрутки. Первое событие
    // snapshotFlow (false в момент появления) не меняет значение родителя,
    // потому что центральная строка уже равна selectedValue.
    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) return@collect
                val index = listState.layoutInfo.centeredItemIndex() ?: return@collect
                val value = values.getOrNull(index) ?: return@collect
                if (value != latestSelectedValue) latestOnSelectedChange(value)
            }
    }

    // Внешнее изменение значения: доводим колесо до него только если оно реально
    // стоит не по центру (при обычном выборе прокрутка уже завершена).
    LaunchedEffect(selectedIndex, values) {
        if (listState.firstVisibleItemIndex != selectedIndex ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .clip(RoundedCornerShape(dimens.radiusMd))
            // Тёмный фон колеса — из темы (surface); подложка выбранной строки
            // рисуется поверх него.
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else dimens.wheelPickerDisabledAlpha)
    ) {
        // Подложка выбранной (центральной) строки: лёгкая акцентная заливка темы.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(dimens.radiusMd))
                .background(
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = dimens.wheelPickerBandAlpha
                    )
                )
        )

        CompositionLocalProvider(
            LocalDensity provides Density(
                density = LocalDensity.current.density,
                fontScale = LocalDensity.current.fontScale.coerceAtMost(
                    dimens.wheelPickerMaxFontScale
                )
            )
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight * halfVisibleItems),
                userScrollEnabled = enabled,
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = values.size,
                    key = { index -> values[index] }
                ) { index ->
                    val isCentered = centeredIndex == index
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label(values[index]),
                            style = if (isCentered) {
                                MaterialTheme.typography.headlineMedium
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            color = if (isCentered) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = dimens.wheelPickerNeighborAlpha
                                )
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * Индекс строки, ближайшей к геометрическому центру видимой области. Координаты
 * строк (`LazyListItemInfo.offset`) отсчитываются от начала макета вместе с
 * content padding, поэтому центр считается как
 * `viewportStartOffset + viewportSize / 2`.
 */
private fun LazyListLayoutInfo.centeredItemIndex(): Int? {
    val viewportCenter = viewportStartOffset + viewportSize.height / 2
    return visibleItemsInfo.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewportCenter)
    }?.index
}
