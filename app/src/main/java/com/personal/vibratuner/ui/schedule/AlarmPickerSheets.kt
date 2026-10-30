package com.personal.vibratuner.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.WheelPicker
import com.personal.vibratuner.ui.components.WheelPickerSheet
import com.personal.vibratuner.ui.components.formatDurationWords
import com.personal.vibratuner.ui.components.twoDigitLabel

/**
 * Нижний лист точной длительности сигнала: колесо часов 0..24 и колесо минут
 * 0..59. Правила [AlarmDuration]: при 24 часах минуты заблокированы на 0,
 * 0 ч 0 мин зажимается до 1 минуты. Под колёсами — живой предпросмотр той же
 * русской формой, что и везде (`formatDurationWords`).
 *
 * Значение применяется ТОЛЬКО по «Готово»; свайп вниз отменяет изменения.
 */
@Composable
fun AlarmDurationPickerSheet(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int) -> Unit
) {
    val dimens = AppTheme.dimens
    var hours by rememberSaveable {
        mutableIntStateOf(AlarmDuration.wheelHours(initialMinutes))
    }
    var minutes by rememberSaveable {
        mutableIntStateOf(AlarmDuration.wheelMinutes(initialMinutes))
    }
    val hoursValues = remember { (0..AlarmDuration.MAX_HOURS).toList() }
    val minutesValues = remember { (0..59).toList() }
    val minuteWheelDisabled = AlarmDuration.isMinuteWheelDisabled(hours)
    val previewMinutes = AlarmDuration.fromWheels(hours, minutes)
    val previewText = formatDurationWords(previewMinutes)
    val previewDescription = stringResource(
        R.string.schedule_duration_value_content_desc,
        previewText
    )

    // 24 часа всегда означают ровно 0 минут — колесо минут фиксируется на нуле.
    LaunchedEffect(hours) {
        if (AlarmDuration.isMinuteWheelDisabled(hours) && minutes != 0) {
            minutes = 0
        }
    }

    WheelPickerSheet(
        title = stringResource(R.string.schedule_duration_label),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(AlarmDuration.fromWheels(hours, minutes)) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingXs)
        ) {
            WheelPicker(
                values = hoursValues,
                selectedValue = hours,
                onSelectedChange = { hours = it },
                modifier = Modifier.weight(1f),
                label = twoDigitLabel,
                contentDescription = stringResource(R.string.wheel_hours_label)
            )
            WheelPicker(
                values = minutesValues,
                selectedValue = if (minuteWheelDisabled) 0 else minutes,
                onSelectedChange = { minutes = it },
                modifier = Modifier.weight(1f),
                enabled = !minuteWheelDisabled,
                label = twoDigitLabel,
                contentDescription = stringResource(R.string.wheel_minutes_label)
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // Живой предпросмотр результата общей русской формой длительности.
        Text(
            text = previewText,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = previewDescription }
        )
    }
}
