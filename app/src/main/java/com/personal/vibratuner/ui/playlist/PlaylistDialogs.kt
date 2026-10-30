package com.personal.vibratuner.ui.playlist

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.db.PlaylistNameValidator
import com.personal.vibratuner.ui.components.dismissKeyboardOnTapOutside

private const val TAG = "PlaylistDialogs"

/**
 * Единый диалог имени плейлиста: создание (пустое поле) и переименование
 * (поле с текущим именем). Переиспользуется каруселью «Моей медиатеки»,
 * блоком «Мои плейлисты» и частью 3 (добавление в новый плейлист).
 * Проверка — правила репозитория [PlaylistNameValidator]: trim, непустое,
 * не длиннее 50 символов. Пустое имя НЕ даёт нажать «Подтвердить» (кнопка
 * выключена); ввод ограничен 50 символами. Диалог прокручивается и учитывает
 * клавиатуру.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var showError by rememberSaveable { mutableStateOf(false) }

    val validatedName = PlaylistNameValidator.validate(name)
    val errorEmptyText = stringResource(R.string.playlist_name_error_empty)

    LaunchedEffect(Unit) {
        // Окно диалога может быть ещё не готово — тогда фокус ставится тапом.
        try {
            focusRequester.requestFocus()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Не удалось автоматически сфокусировать поле имени: ${e.message}")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Тап по пустому месту диалога снимает фокус и прячет клавиатуру.
        // IME-inset не добавляется: окно диалога подстраивает система
        // (decorFitsSystemWindows = true), иначе поверхность диалога
        // растягивается пустым прямоугольником над клавиатурой.
        modifier = Modifier.dismissKeyboardOnTapOutside(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value ->
                        // Ограничение ввода: длиннее 50 символов ввести нельзя,
                        // поэтому имя не отклоняется «молча» уже в репозитории.
                        name = value.take(PlaylistNameValidator.MAX_NAME_LENGTH)
                        showError = false
                    },
                    label = { Text(stringResource(R.string.playlist_name_label)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(text = errorEmptyText) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // «Готово» снимает фокус и прячет клавиатуру;
                            // курсор не остаётся в поле после закрытия ввода.
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            val valid = PlaylistNameValidator.validate(name)
                            if (valid == null) showError = true else onConfirm(valid)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            // Пустое имя не подтверждается: кнопка выключена до ввода.
            // Цвета кнопки берутся из темы (Material3 TextButton).
            TextButton(
                enabled = validatedName != null,
                onClick = {
                    val valid = validatedName ?: return@TextButton
                    onConfirm(valid)
                }
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.dialog_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
