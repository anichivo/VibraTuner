package com.personal.vibratuner.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

private const val LINK_LOG_TAG = "AppWebLink"

/**
 * Кликабельная внешняя ссылка в стиле темы: акцент `primary` + подчёркивание,
 * область нажатия не меньше `minTouchTarget`. Адрес приходит ИЗ
 * `res/values/help_texts.xml` (единственное место, где живут ссылки).
 *
 * Открывается системным обработником (браузер или приложение Telegram).
 * Если подходящего приложения нет, ссылка НИКОГДА не роняет экран и не диалог:
 * случай пишется в лог, под ссылкой появляется короткое сообщение.
 */
@Composable
fun AppWebLink(
    text: String,
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dimens = AppTheme.dimens
    var openFailed by remember(url) { mutableStateOf(false) }
    val failedText = stringResource(R.string.link_open_failed)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.minTouchTarget)
                .clickable(role = Role.Button) {
                    openFailed = !openExternalLink(context, url)
                }
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )
        }

        if (openFailed) {
            Text(
                text = failedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Открывает внешний адрес системным обработчиком. Возвращает `false`, если
 * открыть не удалось (нет приложения, ошибка разбора адреса). Исключения
 * наружу НИКОГДА не выходят — вызывающий только показывает сообщение.
 */
internal fun openExternalLink(context: Context, url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return try {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(LINK_LOG_TAG, "Нет приложения для открытия ссылки: $url", e)
        false
    } catch (e: RuntimeException) {
        Log.w(LINK_LOG_TAG, "Не удалось открыть ссылку: $url", e)
        false
    }
}
