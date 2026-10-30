package com.personal.vibratuner.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.FavoriteToggleButton
import com.personal.vibratuner.ui.components.formatTrackDuration
import com.personal.vibratuner.ui.music.MusicViewModel
import kotlinx.coroutines.delay

/**
 * Глобальный мини-плеер (часть 4): единственная ПЛАВАЮЩАЯ карточка на всё
 * приложение, размещённая в корневом `MainScreenLayout` строго над нижней
 * навигацией. На телефоне она остаётся в `Scaffold.bottomBar`, поэтому Scaffold
 * сам добавляет спискам нижний отступ на её высоту + зазор + NavigationBar.
 *
 * Воспроизведением не управляет напрямую: все команды уходят в
 * [MusicViewModel], который связан с единственным `SoundPlaybackService`
 * и его координатором (приоритет микрозвук > прерывистая пауза > музыка).
 * Состояние — тот же `serviceState`, что и у остального UI; режим порядка
 * (OFF / SHUFFLE / REPEAT_ONE) приходит из координатора.
 *
 * Раскладка карточки сверху вниз:
 * 1) ОДНА строка «Длительность • Название» с бегущей строкой (тап по ней
 *    прокручивает список к треку); иконки-ноты и ♥ здесь нет;
 * 2) тонкая полоса seek — restyled Material 3 Slider: тонкая линия, маленький
 *    бегунок, но зона нажатия не ниже 48 dp;
 * 3) ряд управления из ПЯТИ кнопок: слева режим [shuffle/repeat], РОВНО по
 *    центру тройка [предыдущий] [PLAY/PAUSE] [следующий] (play/pause —
 *    акцентная круглая FilledIconButton), справа зеркально ♥.
 *
 * Виден только когда есть текущий трек (в том числе восстановленный сеансом
 * в состоянии паузы). На чистом первом запуске ничего не рисует.
 */
@Composable
fun MiniPlayer(
    viewModel: MusicViewModel,
    onBodyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val track = uiState.currentTrack ?: return
    val dimens = AppTheme.dimens
    val lifecycleOwner = LocalLifecycleOwner.current

    val belongsToTrack = progress.trackId == track.id
    val durationMs = (if (belongsToTrack) progress.durationMs else 0L)
        .takeIf { it > 0L }
        ?: track.durationMs.takeIf { it > 0L }
        ?: 0L
    val positionMs = if (belongsToTrack) progress.positionMs.coerceAtLeast(0L) else 0L
    val pendingSeekFraction = if (belongsToTrack) progress.pendingSeekFraction else null

    // Пока идёт жест, показываем позицию жеста и НЕ даём секундным обновлениям
    // прогресса дёргать полосу. Seek отправляется один раз в конце жеста.
    var dragFraction by remember(track.id) { mutableStateOf<Float?>(null) }
    val liveFraction = when {
        dragFraction != null -> dragFraction!!
        durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        pendingSeekFraction != null -> pendingSeekFraction
        else -> 0f
    }

    // Тикер живёт в этом листе: обновляется только мини-плеер, а не весь экран.
    LaunchedEffect(uiState.isPlaying, track.id, durationMs, pendingSeekFraction) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshPlaybackPosition()
                if (!uiState.isPlaying && durationMs > 0L && pendingSeekFraction == null) break
                delay(1000L)
            }
        }
    }

    val openTrackLabel = stringResource(R.string.miniplayer_open_track)
    // Одна строка «Длительность • Название»: разделитель и порядок — в
    // strings.xml. Исполнитель НЕ выводится. Если длительность неизвестна —
    // только название, без префикса и без разделителя.
    val durationText = formatTrackDuration(durationMs)
    val nowPlayingText = if (durationText != null) {
        stringResource(R.string.miniplayer_now_playing_format, durationText, track.title)
    } else {
        track.title
    }

    // Плавающая карточка (часть 4): боковые поля и зазор до NavigationBar заданы
    // в Dimens, поэтому экран не знает конкретных dp. Карточка остаётся первым
    // элементом `Scaffold.bottomBar`, а значит нижний contentPadding списков
    // автоматически равен её высоте + зазору + высоте NavigationBar.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimens.floatingPlayerSideMargin,
                end = dimens.floatingPlayerSideMargin,
                bottom = dimens.floatingPlayerNavBarGap
            ),
        shape = RoundedCornerShape(dimens.floatingPlayerCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = dimens.floatingPlayerElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimens.spacingMd,
                    end = dimens.spacingMd,
                    top = dimens.miniPlayerTopPadding,
                    bottom = dimens.miniPlayerBottomPadding
                )
        ) {
            // Строка 1: ОДНА строка «Длительность • Название» — без иконки-ноты
            // (♪ удалена) и без ♥ (♥ переехала в ряд управления справа).
            // Тап по строке прокручивает открытый список к треку и никогда
            // не меняет воспроизведение. Высота строки компактная (Dimens):
            // прежняя минимальная высота 48 dp давала слишком большой зазор
            // вокруг заголовка и удлиняла карточку. heightIn (а не height)
            // оставляет текст целым при очень крупном системном шрифте.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.miniPlayerTitleHeight)
                    .clickable(
                        onClickLabel = openTrackLabel,
                        onClick = onBodyClick
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = nowPlayingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee()
                )
            }

            // Строка 2: тонкая полоса seek без дополнительных вертикальных отступов.
            MiniPlayerSeekBar(
                fraction = liveFraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { viewModel.onSeekFraction(it) }
                    dragFraction = null
                }
            )

            // Строка 3: пять кнопок на одной линии. Главная тройка
            // [предыдущий] [PLAY/PAUSE] [следующий] стоит РОВНО по центру плеера:
            // слева и справа — одинаковые по весу слоты, поэтому её центр
            // совпадает с центром карточки независимо от наполнения слотов.
            // Слева — режим (shuffle/repeat), справа — ♥ зеркально: обе боковые
            // кнопки одного размера и на одинаковом расстоянии от края.
            // Зазор между тройкой и каждой боковой кнопкой — miniPlayerControlsGap.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.miniPlayerHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    PlaybackModeButton(
                        mode = uiState.playbackMode,
                        onClick = { viewModel.onPlaybackModeClick() }
                    )
                }

                // Общий горизонтальный отступ у тройки: он гарантирует зазор
                // до каждой боковой кнопки и не сдвигает тройку от центра.
                Row(
                    modifier = Modifier.padding(
                        horizontal = dimens.miniPlayerControlsGap
                    ),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniPlayerIconButton(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.action_previous),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.onPreviousClick() }
                    )
                    // Нажатое состояние акцентной play/pause — Accent pressed из темы.
                    val playInteraction = remember { MutableInteractionSource() }
                    val playPressed by playInteraction.collectIsPressedAsState()
                    FilledIconButton(
                        onClick = { viewModel.onPlayPauseClick() },
                        modifier = Modifier.size(dimens.playButtonSize),
                        interactionSource = playInteraction,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (playPressed) {
                                AppTheme.accentPressed
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (uiState.isPlaying) {
                                stringResource(R.string.action_pause)
                            } else {
                                stringResource(R.string.action_play)
                            },
                            modifier = Modifier.size(dimens.iconSizeLg)
                        )
                    }
                    MiniPlayerIconButton(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.action_next),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.onNextClick() }
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    FavoriteToggleButton(
                        isFavorite = track.uri in uiState.favoriteUris,
                        onToggle = { viewModel.onFavoriteToggle(track) }
                    )
                }
            }
        }
    }
}

/**
 * Кнопка единого режима: OFF -> SHUFFLE -> REPEAT_ONE -> OFF.
 * У каждого состояния своя иконка; неактивное — приглушённое, активное — акцент.
 */
@Composable
private fun PlaybackModeButton(
    mode: PlaybackMode,
    onClick: () -> Unit
) {
    val icon: ImageVector
    val descriptionRes: Int
    when (mode) {
        PlaybackMode.OFF -> {
            icon = Icons.Default.Repeat
            descriptionRes = R.string.playback_mode_off
        }
        PlaybackMode.SHUFFLE -> {
            icon = Icons.Default.Shuffle
            descriptionRes = R.string.playback_mode_shuffle
        }
        PlaybackMode.REPEAT_ONE -> {
            icon = Icons.Default.RepeatOne
            descriptionRes = R.string.playback_mode_repeat_one
        }
    }
    MiniPlayerIconButton(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = if (mode == PlaybackMode.OFF) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        onClick = onClick
    )
}

/** Кнопка мини-плеера: область нажатия не меньше [AppTheme.dimens.minTouchTarget]. */
@Composable
private fun MiniPlayerIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    IconButton(
        onClick = onClick,
        modifier = modifier.size(dimens.minTouchTarget)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(dimens.iconSizeMd)
        )
    }
}

/**
 * Тонкая интерактивная полоса seek (часть 4): Material 3 [Slider] с кастомной
 * дорожкой и бегунком.
 *
 * Визуальная линия тонкая ([AppTheme.dimens.miniPlayerProgressHeight]), бегунок
 * маленький ([AppTheme.dimens.miniPlayerThumbSize]) и растёт при перетаскивании;
 * высота зоны нажатия — [AppTheme.dimens.miniPlayerSeekTouchHeight] (не ниже
 * 48 dp), поэтому по полосе легко попасть, и никаких дополнительных отступов
 * вокруг неё нет. Жест обрабатывает сам Slider; seek вызывается ОДИН раз в конце
 * жеста (`onValueChangeFinished`), поэтому секундные обновления прогресса
 * не перебивают drag, а seek не спамит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniPlayerSeekBar(
    fraction: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    val progressDescription = stringResource(R.string.miniplayer_progress_description)
    val trackColor = AppTheme.controlTrack
    val activeColor = MaterialTheme.colorScheme.primary

    Slider(
        value = fraction,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.miniPlayerSeekTouchHeight)
            .semantics { contentDescription = progressDescription },
        thumb = { sliderState ->
            // Слот бегунка держим постоянного размера: Slider считает по нему
            // ширину дорожки и позицию пальца, поэтому менять layout во время
            // drag нельзя. Растёт только нарисованный круг при нажатии.
            Box(
                modifier = Modifier.size(dimens.miniPlayerThumbPressedSize),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            if (sliderState.isDragging) {
                                dimens.miniPlayerThumbPressedSize
                            } else {
                                dimens.miniPlayerThumbSize
                            }
                        )
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        },
        track = { sliderState ->
            // Material 3 Slider измеряет дорожку между центрами бегунка
            // (ширина слайдера минус ширина бегунка), поэтому активная часть
            // ровно заканчивается в центре бегунка без ручных поправок.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.miniPlayerProgressHeight)
                    .clip(RoundedCornerShape(dimens.radiusPill))
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(sliderState.coercedValueAsFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(dimens.radiusPill))
                        .background(activeColor)
                )
            }
        }
    )
}
