package com.personal.vibratuner.ui.microsounds

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.microsound.MicrosoundQuietHoursConflict
import com.personal.vibratuner.theme.AppTheme

/**
 * ЕДИНСТВЕННОЕ предупреждение о конфликте окна микрозвука с глобальными
 * «Часами тишины» (этап 4). С части 2 серии v1.2 показывается КОМПАКТНО прямо
 * на карточке микрозвука — в её свёрнутом (единственном) виде, без раскрытия и
 * без диалога: предупреждение легко не заметить внутри настроек.
 *
 * Показывается ТОЛЬКО для включённого микрозвука и только когда его окно
 * пересекается с включёнными часами тишины ([MicrosoundQuietHoursConflict]):
 * FULL — микрозвук не сработает никогда, PARTIAL — не сработает в тишину.
 * Текст короткий (строки `microsounds_quiet_hours_{full,partial}_warning`),
 * не больше двух строк с многоточием; цвета — `errorContainer`/`onErrorContainer`
 * из темы (единственная «внимание»-пара тёмной палитры: других оттенков для
 * статусов правила проекта не разрешают), размеры — из AppDimens. Компонент
 * занимает ширину текстовой колонки карточки и не ломает её разметку на узком
 * экране.
 */
@Composable
internal fun MicrosoundQuietHoursWarning(
    conflict: MicrosoundQuietHoursConflict,
    modifier: Modifier = Modifier
) {
    if (conflict == MicrosoundQuietHoursConflict.NONE) return

    val dimens = AppTheme.dimens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(dimens.radiusSm),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = dimens.spacingSm,
                vertical = dimens.spacingXs
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(dimens.iconSizeSm)
            )
            Spacer(modifier = Modifier.width(dimens.spacingXs))
            Text(
                text = stringResource(
                    if (conflict == MicrosoundQuietHoursConflict.FULL) {
                        R.string.microsounds_quiet_hours_full_warning
                    } else {
                        R.string.microsounds_quiet_hours_partial_warning
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
