package com.personal.vibratuner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R

/**
 * Диалоги ПЕРВОГО запуска. Оба собраны существующим [HelpDialog] (то же
 * оформление, прокрутка текста), поэтому второго механизма диалогов в проекте
 * нет.
 *
 * Порядок: сначала приветствие [WelcomeDialog] (закрывается любым способом —
 * кнопка, Back, тап вне), сразу после него — правила [TermsDialog], которые
 * ОБЯЗАТЕЛЬНО нужно принять: окно не закрывается ни Back, ни тапом вне, у него
 * единственная кнопка «Принять».
 *
 * Тексты — ТОЛЬКО из `res/values/help_texts.xml`, подпись кнопки принятия —
 * из `strings.xml`. Факты показа/принятия хранятся флагами DataStore
 * (`welcome_shown`, `terms_accepted`; см. MusicViewModel).
 */
@Composable
fun WelcomeDialog(onDismiss: () -> Unit) {
    HelpDialog(
        title = stringResource(R.string.help_welcome_title),
        body = stringResource(R.string.help_welcome_body),
        onDismiss = onDismiss,
        extraContent = {
            AppWebLink(
                text = stringResource(R.string.help_link_telegram_label),
                url = stringResource(R.string.help_link_telegram_url)
            )
        }
    )
}

/**
 * Окно правил первого запуска: показывается ПОСЛЕ приветствия и не закрывается
 * без принятия (Back и тап вне выключены). [onAccept] — нажатие «Принять».
 */
@Composable
fun TermsDialog(onAccept: () -> Unit) {
    HelpDialog(
        title = stringResource(R.string.help_terms_title),
        body = stringResource(R.string.help_terms_body),
        onDismiss = onAccept,
        okTextRes = R.string.help_terms_accept,
        dismissible = false
    )
}
