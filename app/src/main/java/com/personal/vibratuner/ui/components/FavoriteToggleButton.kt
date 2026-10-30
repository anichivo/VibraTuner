package com.personal.vibratuner.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/**
 * Кнопка ♥ «Любимое» — единый компонент для «Моей медиатеки» и экрана плейлиста.
 *
 * Отдельная кнопка в строке трека: НИКОГДА не запускает воспроизведение и не
 * меняет поведение тапа/долгого нажатия по строке. Цвета и размеры — только из темы.
 */
@Composable
fun FavoriteToggleButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(dimens.minTouchTarget)
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) {
                stringResource(R.string.action_remove_favorite)
            } else {
                stringResource(R.string.action_add_favorite)
            },
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(dimens.iconSizeSm)
        )
    }
}
