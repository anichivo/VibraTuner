package com.personal.vibratuner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.personal.vibratuner.theme.AppTheme

@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val dimens = AppTheme.dimens
    // Нажатое состояние акцентной кнопки — Accent pressed (#C6613F) из темы.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = dimens.minTouchTarget),
        shape = RoundedCornerShape(dimens.radiusPill),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPressed) {
                AppTheme.accentPressed
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(dimens.iconSizeSm)
            )
            Spacer(modifier = Modifier.width(dimens.spacingSm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val dimens = AppTheme.dimens
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = dimens.minTouchTarget),
        shape = RoundedCornerShape(dimens.radiusPill),
        enabled = enabled,
        border = BorderStroke(dimens.cardBorderWidth, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(dimens.iconSizeSm)
            )
            Spacer(modifier = Modifier.width(dimens.spacingSm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
