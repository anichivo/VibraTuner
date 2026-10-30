package com.personal.vibratuner.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/**
 * Индикатор состояния трека в строке списка (Part 3).
 *
 * Обычная строка показывает «♪». Активный трек (тот же [TrackEntity.uri], что
 * играет сейчас) показывает анимированный эквалайзер во время воспроизведения
 * и иконку паузы, когда музыка на паузе. Единственный источник состояния —
 * `MusicViewModel.uiState` (`serviceState` сервиса/координатора), второго
 * источника нет.
 *
 * Анимация живёт только пока строка в композиции и трек действительно играет:
 * `rememberInfiniteTransition` останавливается при уходе из композиции и
 * никогда не работает в фоне.
 */
@Composable
fun TrackPlaybackIndicator(
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    when {
        isActive && isPlaying -> EqualizerIndicator(modifier = modifier)
        isActive -> Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = stringResource(R.string.track_indicator_paused),
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(dimens.iconSizeMd)
        )

        else -> Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(dimens.iconSizeMd)
        )
    }
}

/**
 * Компактный эквалайзер: три полоски разной высоты с разными периодами.
 * Только цвета и размеры темы — без хардкода.
 */
@Composable
private fun EqualizerIndicator(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    val playingDescription = stringResource(R.string.track_indicator_playing)
    val bars = listOf(
        EqualizerBar(durationMillis = 480, delayMillis = 0),
        EqualizerBar(durationMillis = 620, delayMillis = 120),
        EqualizerBar(durationMillis = 540, delayMillis = 240)
    )
    val transition = rememberInfiniteTransition(label = "track-equalizer")

    Row(
        modifier = modifier
            .size(dimens.iconSizeMd)
            .semantics { contentDescription = playingDescription },
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEachIndexed { index, bar ->
            val fraction by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = bar.durationMillis,
                        delayMillis = bar.delayMillis
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "track-equalizer-bar-$index"
            )
            val barHeight = dimens.equalizerBarMinHeight +
                (dimens.equalizerBarMaxHeight - dimens.equalizerBarMinHeight) * fraction
            Box(
                modifier = Modifier
                    .width(dimens.equalizerBarWidth)
                    .height(barHeight)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(dimens.radiusSm)
                    )
            )
        }
    }
}

private data class EqualizerBar(
    val durationMillis: Int,
    val delayMillis: Int
)
