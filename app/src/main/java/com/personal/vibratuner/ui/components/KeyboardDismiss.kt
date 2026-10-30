package com.personal.vibratuner.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * ЕДИНЫЙ помощник клавиатуры и фокуса для всех экранов, диалогов и листов,
 * где есть текстовый ввод (Part 2, items 2.3–2.5).
 *
 * (1) Тап по пустому месту (по самому контейнеру, а не по ребёнку) снимает
 *     фокус и прячет клавиатуру. Нажатия, долгие нажатия и прокрутка детей
 *     НЕ ломаются: жест приходит в Main-проходе после детей, поэтому
 *     обрабатывается только невыкупленный тап.
 * (2) Когда клавиатура закрывается (системный Back/жест, «Готово» или скрытие
 *     системой), фокус снимается автоматически — курсор в поле ввода больше
 *     не остаётся висеть.
 *
 * Помощник НИКОГДА не добавляет и не тратит inset-ы: IME-отступы остаются
 * ровно в одном месте на окно (см. project-rules §2).
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.dismissKeyboardOnTapOutside(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    // Реагируем только на переход «клавиатура была видна -> скрылась»:
    // на первом кадре и при отсутствии клавиатуры фокус не трогаем.
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            focusManager.clearFocus()
        }
        wasImeVisible = imeVisible
    }
    this.pointerInput(focusManager, keyboardController) {
        detectTapGestures(
            onTap = {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        )
    }
}

/**
 * IME-действие «Готово» для однострочных и числовых полей: снимает фокус и
 * прячет клавиатуру, чтобы курсор не оставался в поле после закрытия ввода.
 * Использовать вместе с `KeyboardOptions(imeAction = ImeAction.Done)`.
 */
@Composable
fun rememberDoneKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        )
    }
}
