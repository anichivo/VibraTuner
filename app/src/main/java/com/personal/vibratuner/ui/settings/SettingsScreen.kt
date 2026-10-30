package com.personal.vibratuner.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.vibratuner.R
import com.personal.vibratuner.audio.choppy.ChoppyValidator
import com.personal.vibratuner.core.config.AppConfig
import com.personal.vibratuner.core.config.AppVersion
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard
import com.personal.vibratuner.ui.components.AppWebLink
import com.personal.vibratuner.ui.components.HelpIcon
import com.personal.vibratuner.ui.components.HelpTopic
import com.personal.vibratuner.ui.components.SettingSliderRow
import com.personal.vibratuner.ui.components.dismissKeyboardOnTapOutside
import com.personal.vibratuner.ui.components.rememberDoneKeyboardActions
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    var showReliability by rememberSaveable { mutableStateOf(false) }

    if (showReliability) {
        ReliabilityScreen(
            contentPadding = contentPadding,
            onBack = { showReliability = false },
            modifier = modifier
        )
        return
    }

    val dimens = AppTheme.dimens
    val context = LocalContext.current
    // Версия читается в рантайме (PackageManager): BuildConfig выключен, а
    // версия НИКОГДА не хардкодится в интерфейсе.
    val appVersion = remember(context) { AppVersion.currentName(context) }
    val volumeRampSec by viewModel.volumeRampDurationSec.collectAsState()
    val choppyEnabled by viewModel.choppyEnabled.collectAsState()
    val choppyPlayMin by viewModel.choppyPlayMinutes.collectAsState()
    val choppyPauseMin by viewModel.choppyPauseMinutes.collectAsState()
    // Проверка обновлений (часть 2): состояние — только из UpdateRepository
    // через SettingsViewModel, сеть из UI не выполняется.
    val autoUpdateCheckEnabled by viewModel.autoUpdateCheckEnabled.collectAsState()
    val updateAvailable by viewModel.updateAvailable.collectAsState()
    val latestVersionName by viewModel.latestVersionName.collectAsState()
    val latestReleaseUrl by viewModel.latestReleaseUrl.collectAsState()

    var playInput by rememberSaveable(choppyPlayMin) { mutableStateOf(choppyPlayMin.toString()) }
    var pauseInput by rememberSaveable(choppyPauseMin) { mutableStateOf(choppyPauseMin.toString()) }

    val scrollState = rememberScrollState()
    // «Готово» на числовой клавиатуре снимает фокус и прячет клавиатуру.
    val doneActions = rememberDoneKeyboardActions()

    Column(
        modifier = modifier
            .fillMaxSize()
            .dismissKeyboardOnTapOutside()
            .consumeWindowInsets(contentPadding)
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(scrollState)
            .padding(dimens.spacingMd)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 1. Нарастание громкости будильника
        Text(
            text = stringResource(R.string.settings_ramp_section),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        SettingSliderRow(
            title = stringResource(R.string.settings_ramp_duration),
            description = if (volumeRampSec > 0) {
                stringResource(R.string.settings_ramp_desc, volumeRampSec)
            } else {
                stringResource(R.string.settings_ramp_disabled)
            },
            value = volumeRampSec / 60f,
            valueRange = 0f..1f,
            onValueChange = { progress ->
                val seconds = (progress * 60).roundToInt()
                viewModel.onVolumeRampDurationChanged(seconds)
            }
        )

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 2. Прерывистая музыка
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_choppy_section),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false)
            )
            HelpIcon(topic = HelpTopic.INTERMITTENT_MUSIC)
        }

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_choppy_enable),
                    checked = choppyEnabled,
                    onCheckedChange = { viewModel.onChoppyEnabledChanged(it) }
                )

                Spacer(modifier = Modifier.height(dimens.spacingMd))

                // Подписи полей вынесены НАД рамкой: длинные русские тексты
                // («Интервал музыки X (мин)») не помещаются в вырез верхней
                // границы OutlinedTextField и пересекали её. Поля стоят в ОДНОМ
                // ряду с равным весом, поэтому подпись может переноситься на
                // две строки при крупном системном шрифте и никогда не наезжает
                // на рамку и не обрезается: подписи обеих полей занимают не
                // меньше двух строк (minLines), иначе поля разъезжались бы по
                // вертикали, когда одна подпись в одну строку, другая в две.
                // Ограничения «пауза короче интервала» больше нет (часть 2
                // фидбека): сохраняются любые значения, проверяется только
                // техническая разумность — оба числа введены и больше нуля.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacingSm)
                ) {
                    IntervalInputField(
                        label = stringResource(R.string.settings_choppy_play_interval),
                        value = playInput,
                        onValueChange = {
                            playInput = it.take(3)
                            val p = it.toIntOrNull() ?: 0
                            val pa = pauseInput.toIntOrNull() ?: 0
                            if (ChoppyValidator.validate(p, pa)) {
                                viewModel.onChoppyIntervalsChanged(p, pa)
                            }
                        },
                        keyboardActions = doneActions,
                        modifier = Modifier.weight(1f)
                    )

                    IntervalInputField(
                        label = stringResource(R.string.settings_choppy_pause_interval),
                        value = pauseInput,
                        onValueChange = {
                            pauseInput = it.take(3)
                            val p = playInput.toIntOrNull() ?: 0
                            val pa = it.toIntOrNull() ?: 0
                            if (ChoppyValidator.validate(p, pa)) {
                                viewModel.onChoppyIntervalsChanged(p, pa)
                            }
                        },
                        keyboardActions = doneActions,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(dimens.spacingSm))

                // Ошибки ввода нет: подпись всегда описывает ДЕЙСТВУЮЩИЕ значения
                // (пока поле правится, в силе остаются последние сохранённые).
                Text(
                    text = stringResource(R.string.settings_choppy_desc, choppyPlayMin, choppyPauseMin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 3. Экран «Надёжность»
        Text(
            text = stringResource(R.string.settings_reliability_section),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { showReliability = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.padding(start = dimens.spacingSm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_reliability_btn),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_reliability_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 4. Воспроизведение
        Text(
            text = stringResource(R.string.settings_playback_section),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_playback_loop_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 5. Проверка обновлений: единственный переключатель. Включение сразу
        // запускает ОДНУ проверку в UpdateRepository — сеть из UI не идёт.
        Text(
            text = stringResource(R.string.settings_updates_section),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_update_check),
                checked = autoUpdateCheckEnabled,
                onCheckedChange = { viewModel.onAutoUpdateCheckEnabledChanged(it) }
            )

            Spacer(modifier = Modifier.height(dimens.spacingXs))

            Text(
                text = stringResource(R.string.settings_auto_update_check_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 6. «О приложении»: предупреждение о тестировании, контакт в Telegram,
        // текущая версия и ссылка на релизы GitHub. ВСЕ статические тексты и
        // адреса этого блока живут в res/values/help_texts.xml — едином месте
        // правки справки и ссылок; строка доступного обновления и ссылка на
        // него — состояние проверки (strings.xml), в экране нет ни адресов,
        // ни версии.
        Text(
            text = stringResource(R.string.help_about_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(dimens.spacingSm))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spacingSm)) {
                Text(
                    text = stringResource(R.string.help_about_test_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AppWebLink(
                    text = stringResource(R.string.help_link_telegram_label),
                    url = stringResource(R.string.help_link_telegram_url)
                )

                if (!appVersion.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.help_about_version_format, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Обновление: строка и ссылка на релиз показываются ТОЛЬКО при
                // включённом переключателе и релизе новее установленной версии.
                // «Всё актуально», changelog и кнопки проверки здесь нет.
                val newVersion = latestVersionName
                if (updateAvailable && !newVersion.isNullOrBlank()) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_available_format,
                            newVersion
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    AppWebLink(
                        text = stringResource(R.string.settings_update_download_action),
                        // Акцентная ссылка: адрес берётся из UpdateRepository
                        // (html_url релиза, иначе страница релизов AppConfig).
                        url = latestReleaseUrl
                    )
                }

                AppWebLink(
                    text = stringResource(R.string.help_link_github_label),
                    // Адрес строится из ЕДИНСТВЕННОЙ константы owner/repo в AppConfig:
                    // он же нужен проверке обновлений (часть 1), поэтому в ресурсах
                    // адреса нет.
                    url = AppConfig.GITHUB_RELEASES_PAGE_URL
                )
            }
        }
    }
}

/**
 * Строка настройки с переключателем — единый вид тумблеров «Настроек»
 * (прерывистая музыка, автопроверка обновлений). Цвета — только из темы:
 * включённый переключатель акцентный, выключенный не сливается с карточкой.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        )
    }
}

/**
 * Числовое поле с подписью НАД рамкой. В отличие от «плавающей» метки
 * `OutlinedTextField`, подпись здесь не участвует в вырезе верхней границы,
 * поэтому не может её пересечь ни на узком экране, ни при крупном системном
 * шрифте: при нехватке ширины она переносится на следующую строку.
 * Все размеры и цвета — только из темы.
 */
@Composable
private fun IntervalInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    Column(
        modifier = modifier
            .widthIn(max = dimens.settingsFieldMaxWidth)
            .fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Обе подписи занимают минимум две строки: иначе при переносе
            // длинной подписи соседние поля ряда оказываются на разной высоте.
            minLines = 2
        )

        Spacer(modifier = Modifier.height(dimens.spacingXs))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = keyboardActions,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
