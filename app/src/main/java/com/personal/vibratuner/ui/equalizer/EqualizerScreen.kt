package com.personal.vibratuner.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.eq.EqualizerMath
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard

/**
 * Экран «Эквалайзер» (часть 4): только чтение/запись `EqualizerRepository`.
 * Движок воспроизведения реагирует на Flow репозитория сам, второго пути
 * управления звуком здесь нет.
 *
 * Состояние экрана — проекция [EqualizerScreenState] из [EqualizerViewModel.settings];
 * локальный черновик протяжки и отложенная запись живут в [EqualizerEditor].
 * Экран целиком прокручивается, поэтому работает на маленьком телефоне и при
 * крупном системном шрифте.
 */
@Composable
fun EqualizerScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: EqualizerViewModel = viewModel()
) {
    val dimens = AppTheme.dimens
    val settings by viewModel.settings.collectAsState()
    val bandRange by viewModel.bandRange.collectAsState()
    val dbUnit = stringResource(R.string.equalizer_value_db)
    val state = remember(settings, bandRange, dbUnit) {
        equalizerScreenState(settings, bandRange, dbUnit)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(dimens.spacingMd)
    ) {
        Text(
            text = stringResource(R.string.equalizer_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        EqualizerHeaderCard(
            enabled = settings.enabled,
            onEnabledChanged = viewModel::onEnabledChanged
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerPresetRow(
            state = state,
            onPresetSelected = viewModel::onPresetSelected
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerControlCard(
            icon = Icons.Default.Speaker,
            title = stringResource(R.string.equalizer_bass_title),
            hint = stringResource(R.string.equalizer_bass_hint),
            valueLabel = state.bassLabel,
            sliderValue = settings.bassPercent.toFloat(),
            slider = EqualizerSliders.BASS,
            enabled = state.controlsEnabled,
            onValueChange = viewModel::onBassChanged,
            onValueChangeFinished = viewModel::onSliderChangeFinished,
            onReset = { viewModel.onReset(EqualizerControl.BASS) },
            resetContentDescription = stringResource(
                R.string.equalizer_reset_description,
                stringResource(R.string.equalizer_bass_title)
            )
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerControlCard(
            icon = Icons.Default.Hearing,
            title = stringResource(R.string.equalizer_treble_title),
            hint = stringResource(R.string.equalizer_treble_hint),
            valueLabel = state.trebleLabel,
            sliderValue = settings.treblePercent.toFloat(),
            slider = EqualizerSliders.TREBLE,
            enabled = state.controlsEnabled,
            onValueChange = viewModel::onTrebleChanged,
            onValueChangeFinished = viewModel::onSliderChangeFinished,
            onReset = { viewModel.onReset(EqualizerControl.TREBLE) },
            resetContentDescription = stringResource(
                R.string.equalizer_reset_description,
                stringResource(R.string.equalizer_treble_title)
            )
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerControlCard(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.equalizer_overboost_title),
            hint = stringResource(R.string.equalizer_overboost_hint),
            valueLabel = state.overboostLabel,
            sliderValue = settings.overboostPercent.toFloat(),
            slider = EqualizerSliders.OVERBOOST,
            enabled = state.controlsEnabled,
            onValueChange = viewModel::onOverboostChanged,
            onValueChangeFinished = viewModel::onSliderChangeFinished,
            onReset = { viewModel.onReset(EqualizerControl.OVERBOOST) },
            resetContentDescription = stringResource(
                R.string.equalizer_reset_description,
                stringResource(R.string.equalizer_overboost_title)
            )
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerControlCard(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.equalizer_speed_title),
            hint = stringResource(R.string.equalizer_speed_hint),
            valueLabel = state.speedLabel,
            sliderValue = EqualizerMath.sliderFromSpeed(settings.speed),
            slider = EqualizerSliders.SPEED,
            enabled = state.controlsEnabled,
            onValueChange = viewModel::onSpeedSliderChanged,
            onValueChangeFinished = viewModel::onSliderChangeFinished,
            onReset = { viewModel.onReset(EqualizerControl.SPEED) },
            resetContentDescription = stringResource(
                R.string.equalizer_reset_description,
                stringResource(R.string.equalizer_speed_title)
            )
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        EqualizerSystemVolumeCard(
            checked = settings.boostSystemVolume,
            enabled = state.controlsEnabled,
            onCheckedChange = viewModel::onBoostSystemVolumeChanged
        )

        Spacer(modifier = Modifier.height(dimens.spacingLg))
    }
}

/**
 * Карточка-заголовок: состояние обработки и переключатель ON/OFF. Переключатель
 * остаётся доступным всегда — иначе выключенный эквалайзер нельзя было бы
 * включить обратно.
 */
@Composable
private fun EqualizerHeaderCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    val dimens = AppTheme.dimens
    val switchDescription = stringResource(R.string.equalizer_switch_description)
    val switchState = stringResource(
        if (enabled) R.string.equalizer_switch_state_on else R.string.equalizer_switch_state_off
    )

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.equalizer_header_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = stringResource(
                        if (enabled) R.string.equalizer_status_on
                        else R.string.equalizer_status_off
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingMd))

            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier.semantics {
                    contentDescription = switchDescription
                    stateDescription = switchState
                }
            )
        }
    }
}

/** Быстрые пресеты: горизонтально прокручиваемый ряд чипов. */
@Composable
private fun EqualizerPresetRow(
    state: EqualizerScreenState,
    onPresetSelected: (EqualizerPreset) -> Unit
) {
    val dimens = AppTheme.dimens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm)
    ) {
        EqualizerPreset.entries.forEach { preset ->
            val selected = preset == state.selectedPreset
            FilterChip(
                selected = selected,
                onClick = { onPresetSelected(preset) },
                enabled = state.controlsEnabled,
                label = {
                    Text(
                        text = stringResource(preset.nameRes),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                shape = RoundedCornerShape(dimens.radiusPill),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = state.controlsEnabled,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    borderWidth = dimens.cardBorderWidth,
                    selectedBorderWidth = dimens.cardBorderWidth
                )
            )
        }
    }
}

/**
 * Слайдер регулятора с рисками и привязкой к круглым значениям.
 *
 * Материал 3 `Slider` с кастомной дорожкой (экспериментальный слот `track`,
 * эталон — полоса seek мини-плеера). Каждое движение пальца проходит через
 * чистую [EqualizerSliderSpec.snap]: рядом с риской значение «прилипает»,
 * между рисками остаётся непрерывным. Значение и запись настроек не
 * дублируются — в UI приходит уже сохранённое значение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerSlider(
    value: Float,
    slider: EqualizerSliderSpec,
    enabled: Boolean,
    title: String,
    stateText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value.coerceIn(slider.range.start, slider.range.endInclusive),
        onValueChange = { rawValue -> onValueChange(slider.snap(rawValue)) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = slider.range,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = AppTheme.controlTrack
        ),
        track = { sliderState ->
            EqualizerSliderTrack(
                valueFraction = sliderState.coercedValueAsFraction,
                tickFractions = slider.tickFractions,
                enabled = enabled
            )
        },
        modifier = modifier.semantics {
            contentDescription = title
            stateDescription = stateText
        }
    )
}

/**
 * Дорожка слайдера с рисками: фон — [AppTheme.controlTrack], заполненная часть
 * и бегунок — акцент темы, риски — приглушённый текст `onSurfaceVariant`.
 * Material 3 измеряет дорожку между центрами бегунка, поэтому позиции рисок
 * в долях [tickFractions] совпадают с реальным ходом полосы без поправок.
 */
@Composable
private fun EqualizerSliderTrack(
    valueFraction: Float,
    tickFractions: List<Float>,
    enabled: Boolean
) {
    val dimens = AppTheme.dimens
    val activeColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = dimens.sliderTickAlpha
    )
    val trackShape = RoundedCornerShape(dimens.radiusPill)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.sliderTickAreaHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.sliderTrackHeight)
                .clip(trackShape)
                .background(AppTheme.controlTrack)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(valueFraction.coerceIn(0f, 1f))
                .height(dimens.sliderTrackHeight)
                .clip(trackShape)
                .background(activeColor)
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickWidthPx = dimens.sliderTickWidth.toPx()
            val tickHalfHeightPx = dimens.sliderTickHeight.toPx() / 2f
            val centerY = size.height / 2f
            val halfTick = tickWidthPx / 2f
            tickFractions.forEach { fraction ->
                val x = (size.width * fraction).coerceIn(halfTick, size.width - halfTick)
                drawLine(
                    color = tickColor,
                    start = Offset(x, centerY - tickHalfHeightPx),
                    end = Offset(x, centerY + tickHalfHeightPx),
                    strokeWidth = tickWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Карточка одного регулятора: иконка, название, подсказка, текущее значение,
 * слайдер и компактный сброс. При выключенном эквалайзере все действия
 * недоступны, а значения остаются видимыми.
 */
@Composable
private fun EqualizerControlCard(
    icon: ImageVector,
    title: String,
    hint: String,
    valueLabel: String,
    sliderValue: Float,
    slider: EqualizerSliderSpec,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit,
    resetContentDescription: String
) {
    val dimens = AppTheme.dimens
    val density = LocalDensity.current
    val accent = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(dimens.iconSizeMd)
            )

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            // Слот значения — ФИКСИРОВАННОЙ ширины: подпись меняет длину
            // («0%» / «100%» / «+500%» / «x0,25»), но ширина колонки названия
            // и подсказки не меняется, поэтому карточка не «прыгает» по
            // высоте во время протяжки. Масштаб шрифта сверху ограничен,
            // чтобы длинное значение не обрезалось при крупном системном шрифте.
            Box(
                modifier = Modifier.width(dimens.equalizerValueSlotWidth),
                contentAlignment = Alignment.CenterEnd
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = density.fontScale.coerceAtMost(
                            dimens.equalizerValueMaxFontScale
                        )
                    )
                ) {
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EqualizerSlider(
                value = sliderValue,
                slider = slider,
                enabled = enabled,
                title = title,
                stateText = valueLabel,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(dimens.spacingXs))

            // Сброс — иконка уже подключённого набора Material (та же, что у
            // обновления каталога), без текста; область нажатия >= 48 dp.
            IconButton(
                onClick = onReset,
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .size(dimens.minTouchTarget)
                    .semantics { contentDescription = resetContentDescription }
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(dimens.iconSizeMd)
                )
            }
        }
    }
}

/**
 * Строка «максимальная системная громкость»: пока звук играет, контроллер
 * системной громкости поднимает уровень до максимума и после возвращает
 * прежний. Настройка доступна только при включённом эквалайзере.
 */
@Composable
private fun EqualizerSystemVolumeCard(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val dimens = AppTheme.dimens
    val switchDescription = stringResource(R.string.equalizer_system_volume_description)
    val switchState = stringResource(
        if (checked) R.string.equalizer_system_volume_state_on
        else R.string.equalizer_system_volume_state_off
    )

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(dimens.iconSizeMd)
            )

            Spacer(modifier = Modifier.width(dimens.spacingSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.equalizer_system_volume_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(dimens.spacingXs))
                Text(
                    text = stringResource(R.string.equalizer_system_volume_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(dimens.spacingMd))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier.semantics {
                    contentDescription = switchDescription
                    stateDescription = switchState
                }
            )
        }
    }
}
