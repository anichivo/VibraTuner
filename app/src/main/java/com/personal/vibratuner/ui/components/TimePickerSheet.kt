package com.personal.vibratuner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme

/** Двузначная подпись колеса: «07», «30». */
internal val twoDigitLabel: (Int) -> String = { value ->
    value.toString().padStart(2, '0')
}

/**
 * ЕДИНЫЙ нижний лист выбора времени суток: два колеса («часы» 00..23 и
 * «минуты» 00..59) с разделителем «:». Используется и в редакторе будильника,
 * и в «Часах тишины»; формат по-прежнему 24-часовой.
 *
 * Значение применяется ТОЛЬКО по кнопке «Готово». Свайп вниз, тап по затемнению
 * и системный «Назад» закрывают лист без изменений ([onDismiss]).
 */
@Composable
fun TimePickerSheet(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    var hour by rememberSaveable { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by rememberSaveable { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }
    val hours = remember { (0..23).toList() }
    val minutes = remember { (0..59).toList() }

    WheelPickerSheet(
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(hour, minute) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacingXs)
        ) {
            WheelPicker(
                values = hours,
                selectedValue = hour,
                onSelectedChange = { hour = it },
                modifier = Modifier.weight(1f),
                label = twoDigitLabel,
                contentDescription = stringResource(R.string.wheel_hours_label)
            )
            Text(
                text = stringResource(R.string.time_separator),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            WheelPicker(
                values = minutes,
                selectedValue = minute,
                onSelectedChange = { minute = it },
                modifier = Modifier.weight(1f),
                label = twoDigitLabel,
                contentDescription = stringResource(R.string.wheel_minutes_label)
            )
        }
    }
}

/**
 * Общая оболочка нижних листов выбора: тёмный контейнер темы, заголовок,
 * содержимое (колёса) и кнопки «Отмена» / «Готово».
 *
 * Навигационная панель учитывается ЯВНО через `contentWindowInsets =
 * WindowInsets.navigationBars`: Material3 применяет эти insets к контенту листа,
 * поэтому кнопки и колёса не оказываются под системной панелью. Поля ввода
 * в листах нет, поэтому IME-inset не нужен; статус-бар листу не мешает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    val dimens = AppTheme.dimens
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cancelLabel = stringResource(R.string.dialog_cancel)
    val doneLabel = stringResource(R.string.dialog_done)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = { WindowInsets.navigationBars }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spacingMd)
                .padding(bottom = dimens.spacingLg)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(dimens.spacingMd))

            content()

            Spacer(modifier = Modifier.height(dimens.spacingLg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppOutlinedButton(text = cancelLabel, onClick = onDismiss)
                Spacer(modifier = Modifier.width(dimens.spacingSm))
                AppPrimaryButton(text = doneLabel, onClick = onConfirm)
            }
        }
    }
}
