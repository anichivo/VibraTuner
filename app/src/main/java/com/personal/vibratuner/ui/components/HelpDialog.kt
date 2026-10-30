package com.personal.vibratuner.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/**
 * Тематический диалог справки: заголовок закреплён сверху, текст прокручивается
 * в области не выше ~60% высоты экрана, внизу — кнопка «Понятно».
 * Закрывается кнопкой, тапом вне диалога и системной кнопкой «Назад»
 * (кроме обязательного окна, у которого `dismissible = false`).
 *
 * Абзацы во входном тексте разделяются пустой строкой (см. help_texts.xml).
 * [extraContent] — необязательный слот ПОСЛЕ абзацев внутри той же прокручиваемой
 * области (например, кликабельная ссылка в приветственном диалоге). Поведение
 * диалога он не меняет: кнопка, тап вне и Back по-прежнему закрывают его.
 *
 * [okTextRes] — подпись единственной кнопки внизу (по умолчанию «Понятно»).
 * [dismissible] = false делает окно ОБЯЗАТЕЛЬНЫМ: Back и тап вне диалога его
 * не закрывают, действие выполняется только кнопкой. Так работает окно правил
 * первого запуска (`TermsDialog`) — без единой копии разметки диалога.
 */
@Composable
fun HelpDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {},
    @StringRes okTextRes: Int = R.string.help_dialog_ok,
    dismissible: Boolean = true
) {
    val dimens = AppTheme.dimens
    val paragraphs = remember(body) { splitHelpParagraphs(body) }
    val bodyMaxHeight =
        LocalConfiguration.current.screenHeightDp.dp * dimens.helpDialogBodyMaxHeightFraction

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Тап вне диалога закрывает его. Полноэкранное окно диалога
                // не даёт платформе поймать «внешний» тап, поэтому ловим сами.
                // Для обязательного окна (dismissible = false) этот жест выключен.
                .pointerInput(dismissible) {
                    if (!dismissible) return@pointerInput
                    detectTapGestures { onDismiss() }
                }
                .padding(dimens.spacingLg),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(dimens.radiusLg),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    dimens.cardBorderWidth,
                    MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .widthIn(max = dimens.helpDialogMaxWidth)
                    .fillMaxWidth()
                    // Поверхность поглощает тапы, чтобы они не закрывали диалог.
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(modifier = Modifier.padding(dimens.spacingLg)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(dimens.spacingMd))

                    // Текст ограничен по высоте и прокручивается: и короткая,
                    // и очень длинная справка выглядят одинаково аккуратно.
                    Column(
                        modifier = Modifier
                            .heightIn(max = bodyMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(dimens.spacingMd)
                    ) {
                        paragraphs.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Необязательный слот (ссылка в приветственном диалоге):
                        // прокручивается вместе с текстом, а не выносится вниз.
                        extraContent()
                    }

                    Spacer(modifier = Modifier.height(dimens.spacingMd))

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = stringResource(okTextRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Делит текст справки на абзацы по пустой строке (\n\n).
 * Пустые фрагменты отбрасываются, чтобы лишние переводы строк не рисовали пустоты.
 */
internal fun splitHelpParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
