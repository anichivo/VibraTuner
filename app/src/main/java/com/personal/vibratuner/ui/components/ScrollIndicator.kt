package com.personal.vibratuner.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import com.personal.vibratuner.theme.AppTheme

/**
 * Тонкая полоса прокрутки справа от ленивой СЕТКИ: рисуется поверх содержимого
 * тем же `Modifier`, поэтому новых компонентов/библиотек и второго состояния не
 * появляется.
 *
 * Полоса видна ТОЛЬКО пока содержимое больше окна; в покое — приглушённая, во
 * время прокрутки плавно становится заметнее и так же плавно гаснет обратно, но
 * НИКОГДА не исчезает полностью (`scrollIndicatorIdleAlpha` из `AppDimens`).
 * Цвет — `onSurface` темы, толщина/минимальная длина/отступ — из `AppDimens`.
 *
 * Геометрия вынесена в чистую [scrollIndicatorThumb], чтобы её можно было
 * проверить JVM-тестом: бегунок считается по числу видимых элементов и
 * смещению первого из них.
 *
 * Применять к каждой прокручиваемой сетке окна выбора звука (`SoundPickerDialog`).
 */
fun Modifier.scrollIndicator(state: LazyGridState): Modifier = composed {
    val dimens = AppTheme.dimens
    val thumbColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Прокрутка «прямо сейчас»: единственный источник — сам LazyGridState.
    var isScrolling by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { isScrolling = it }
    }
    val alpha by animateFloatAsState(
        targetValue = if (isScrolling) {
            dimens.scrollIndicatorActiveAlpha
        } else {
            dimens.scrollIndicatorIdleAlpha
        },
        animationSpec = tween(durationMillis = dimens.scrollIndicatorAlphaAnimationMs),
        label = "scrollIndicatorAlpha"
    )

    val widthPx = with(density) { dimens.scrollIndicatorWidth.toPx() }
    val endInsetPx = with(density) { dimens.scrollIndicatorEndInset.toPx() }
    val minLengthPx = with(density) { dimens.scrollIndicatorMinLength.toPx() }
    val minOverflowPx = with(density) { dimens.scrollIndicatorMinOverflow.toPx() }

    drawWithContent {
        // Сначала содержимое сетки, затем полоса поверх него.
        drawContent()

        if (!state.canScrollBackward && !state.canScrollForward) return@drawWithContent
        val layoutInfo = state.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val firstVisible = visible.firstOrNull() ?: return@drawWithContent
        val thumb = scrollIndicatorThumb(
            totalItems = layoutInfo.totalItemsCount,
            visibleItems = visible.size,
            firstVisibleIndex = firstVisible.index,
            firstVisibleOffset = firstVisible.offset.y.toFloat(),
            firstVisibleItemHeight = firstVisible.size.height,
            viewportHeight = size.height,
            contentEnd = visible.maxOf { it.offset.y + it.size.height }.toFloat(),
            minLengthFraction = if (size.height > 0f) minLengthPx / size.height else 1f,
            minOverflowPx = minOverflowPx
        ) ?: return@drawWithContent

        // Скругление — половина ширины: тонкий «капсульный» бегунок.
        drawRoundRect(
            color = thumbColor.copy(alpha = alpha),
            topLeft = Offset(
                x = size.width - widthPx - endInsetPx,
                y = thumb.top * size.height
            ),
            size = Size(widthPx, thumb.length * size.height),
            cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
        )
    }
}
