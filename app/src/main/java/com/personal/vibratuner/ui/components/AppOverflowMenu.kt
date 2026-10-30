package com.personal.vibratuner.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/** Пункт меню «⋮»: готовая подпись и действие. [enabled] = false — пункт виден, но недоступен. */
data class AppMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

/**
 * Кнопка «⋮» с выпадающим меню (действия со строкой списка).
 * Все цвета и размеры берутся из темы.
 */
@Composable
fun AppOverflowMenuButton(
    items: List<AppMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.action_more)
) {
    val dimens = AppTheme.dimens
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = modifier.size(dimens.minTouchTarget)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimens.iconSizeSm)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            // Недоступный пункт («Скачивается…», «Скачано») читается
                            // приглушённо, но остаётся видимым: состав меню не прыгает.
                            color = if (item.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    enabled = item.enabled
                )
            }
        }
    }
}
