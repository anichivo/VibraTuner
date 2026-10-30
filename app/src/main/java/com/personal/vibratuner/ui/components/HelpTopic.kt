package com.personal.vibratuner.ui.components

import androidx.annotation.StringRes
import com.personal.vibratuner.R

/**
 * Реестр тем встроенной справки: каждая тема ссылается на пару строк
 * help_<тема>_title / help_<тема>_body из res/values/help_texts.xml.
 *
 * Новая тема = 2 строки в help_texts.xml + одна запись здесь + HelpIcon(...) на экране.
 */
enum class HelpTopic(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
) {
    SPEAKER_POWER(
        titleRes = R.string.help_speaker_power_title,
        bodyRes = R.string.help_speaker_power_body
    ),
    FAKE_SPEAKER(
        titleRes = R.string.help_fake_speaker_title,
        bodyRes = R.string.help_fake_speaker_body
    ),
    INTERMITTENT_MUSIC(
        titleRes = R.string.help_intermittent_music_title,
        bodyRes = R.string.help_intermittent_music_body
    )
}
