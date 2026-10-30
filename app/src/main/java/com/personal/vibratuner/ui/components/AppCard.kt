package com.personal.vibratuner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.personal.vibratuner.theme.AppTheme

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) {
    val dimens = AppTheme.dimens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(dimens.radiusMd),
        color = backgroundColor,
        border = BorderStroke(dimens.cardBorderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(dimens.spacingMd),
            content = content
        )
    }
}
