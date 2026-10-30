package com.personal.vibratuner.ui.microsounds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppTimeValue
import com.personal.vibratuner.ui.components.MINUTES_PER_DAY
import com.personal.vibratuner.ui.components.TimePickerSheet

/**
 * Собственное окно работы микрозвука («Свое время работы») — Stage 3.
 *
 * Выключено: микрозвук работает весь день, под переключателем короткая подсказка
 * «Весь день». Включено: два нажимаемых времени «С» и «До», каждое открывает
 * ЕДИНЫЙ нижний лист [TimePickerSheet] (тот же, что в «Часах тишины» и в
 * редакторе будильника; второй лист/колесо НИКОГДА не создаётся).
 *
 * Начало и конец не могут совпадать: показывается короткая ошибка из
 * `strings.xml`, а сохранение такого окна родитель не выполняет.
 *
 * Компонент без собственного состояния окна: значения приходят от родителя,
 * поэтому одна и та же разметка используется и редактором микрозвука, и
 * фейк-включением колонки.
 */
@Composable
fun MicrosoundWindowField(
    enabled: Boolean,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (startMinuteOfDay: Int, endMinuteOfDay: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    val start = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val end = endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    var editingStart by rememberSaveable { mutableStateOf(false) }
    var editingEnd by rememberSaveable { mutableStateOf(false) }
    val isInvalid = enabled && start == end

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.microsounds_window_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(dimens.spacingXs))

                Text(
                    text = stringResource(
                        if (enabled) {
                            R.string.microsounds_window_desc
                        } else {
                            R.string.microsounds_window_off_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = microsoundSwitchColors()
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(dimens.spacingSm))

            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppTimeValue(
                    hour = start / 60,
                    minute = start % 60,
                    prefix = stringResource(R.string.quiet_hours_from),
                    onClick = { editingStart = true },
                    modifier = Modifier.weight(1f)
                )
                AppTimeValue(
                    hour = end / 60,
                    minute = end % 60,
                    prefix = stringResource(R.string.quiet_hours_to),
                    onClick = { editingEnd = true },
                    modifier = Modifier.weight(1f)
                )
            }

            if (isInvalid) {
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = stringResource(R.string.microsounds_window_same_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (editingStart) {
        TimePickerSheet(
            title = stringResource(R.string.time_picker_title),
            initialHour = start / 60,
            initialMinute = start % 60,
            onDismiss = { editingStart = false },
            onConfirm = { hour, minute ->
                editingStart = false
                onTimeChange(hour * 60 + minute, end)
            }
        )
    }

    if (editingEnd) {
        TimePickerSheet(
            title = stringResource(R.string.time_picker_title),
            initialHour = end / 60,
            initialMinute = end % 60,
            onDismiss = { editingEnd = false },
            onConfirm = { hour, minute ->
                editingEnd = false
                onTimeChange(start, hour * 60 + minute)
            }
        )
    }
}
