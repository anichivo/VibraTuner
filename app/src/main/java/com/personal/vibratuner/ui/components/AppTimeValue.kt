package com.personal.vibratuner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme
import java.util.Locale

/**
 * Форматирует время суток в виде ЧЧ:ММ (24-часовой формат).
 */
fun formatAppTime(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

/**
 * Единый tappable-элемент отображения времени: крупное значение ЧЧ:ММ,
 * нажатие открывает нижний лист [TimePickerSheet].
 */
@Composable
fun AppTimeValue(
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    enabled: Boolean = true
) {
    val dimens = AppTheme.dimens
    val formatted = formatAppTime(hour, minute)
    val description = if (prefix.isNullOrBlank()) {
        stringResource(R.string.time_value_content_desc, formatted)
    } else {
        stringResource(R.string.time_value_with_prefix_content_desc, prefix, formatted)
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = dimens.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = dimens.spacingMd,
                vertical = dimens.spacingSm
            )
        ) {
            if (!prefix.isNullOrBlank()) {
                Text(
                    text = prefix,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatted,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
