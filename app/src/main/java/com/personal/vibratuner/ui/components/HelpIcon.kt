package com.personal.vibratuner.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/**
 * Ненавязчивая, но заметная иконка справки «i»: приглушённый акцент темы,
 * область нажатия не меньше 48 dp, визуально иконка меньше области.
 * По нажатию открывает [HelpDialog] с заголовком и текстом темы из help_texts.xml.
 */
@Composable
fun HelpIcon(
    topic: HelpTopic,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    var showDialog by rememberSaveable { mutableStateOf(false) }

    IconButton(
        onClick = { showDialog = true },
        modifier = modifier.size(dimens.minTouchTarget)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.help_icon_content_description),
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(dimens.helpIconSize)
        )
    }

    if (showDialog) {
        HelpDialog(
            title = stringResource(topic.titleRes),
            body = stringResource(topic.bodyRes),
            onDismiss = { showDialog = false }
        )
    }
}
