package com.personal.vibratuner.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.personal.vibratuner.R
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.theme.PlaylistDescriptionStyle

/** Сколько строк описания видно в свёрнутом виде (≈5 строк по условию этапа). */
internal const val COLLAPSED_MAX_LINES = 5

/** Один видимый абзац описания и предел его строк ([Int.MAX_VALUE] — без предела). */
internal data class DescriptionParagraph(val text: String, val maxLines: Int)

/**
 * Описание плейлиста «От автора»: БЕЗ карточки и рамки, прямо на фоне экрана,
 * с отступом сверху и снизу. Акцентная типографика — [PlaylistDescriptionStyle]
 * (`Type.kt`): чуть крупнее обычного текста, SemiBold, БЕЗ курсива.
 *
 * - пустое/пробельное описание не рисует ни текста, ни отступов;
 * - текст делится на абзацы по пустой строке и укладывается в `Column`:
 *   расстояние между абзацами задаёт `playlistDescriptionParagraphSpacing`
 *   (меньше прежней пустой строки, правка по фидбеку), одиночный перевод
 *   строки остаётся внутри абзаца;
 * - если текст не помещается в [COLLAPSED_MAX_LINES] строк, показывается
 *   переключатель «Читать полностью» / «Свернуть», высота меняется
 *   анимированно; если помещается — переключателя нет;
 * - снизу — `playlistDescriptionBottomPadding` плюс `playlistDescriptionListGap`,
 *   чтобы конец описания был заметнее отделён от начала списка треков.
 *
 * Число строк считается `TextMeasurer` по той же ширине и стилю, поэтому
 * свёрнутый вид обрезает текст ровно по пятой строке (ellipsis у последнего
 * видимого абзаца). Размеры, цвета и типографика берутся только из темы.
 */
@Composable
fun PlaylistDescription(
    text: String,
    modifier: Modifier = Modifier
) {
    if (text.isBlank()) return

    val dimens = AppTheme.dimens
    val paragraphs = remember(text) { splitDescriptionParagraphs(text) }
    if (paragraphs.isEmpty()) return
    var isExpanded by rememberSaveable(text) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = dimens.playlistDescriptionTopPadding,
                bottom = dimens.playlistDescriptionBottomPadding +
                    dimens.playlistDescriptionListGap
            )
    ) {
        val measurer = rememberTextMeasurer()
        val availableWidth = constraints.maxWidth
        // Число строк каждого абзаца при той же ширине и стиле, что у Text ниже:
        // нужно только для свёрнутого вида, без второго прохода компоновки.
        val lineCounts = remember(paragraphs, availableWidth, measurer) {
            paragraphs.map { paragraph ->
                measurer.measure(
                    text = paragraph,
                    style = PlaylistDescriptionStyle,
                    maxLines = Int.MAX_VALUE,
                    constraints = Constraints(maxWidth = availableWidth)
                ).lineCount
            }
        }
        val collapsible = isDescriptionCollapsible(lineCounts)
        val visibleParagraphs = visibleDescriptionParagraphs(
            paragraphs = paragraphs,
            lineCounts = lineCounts,
            expanded = isExpanded || !collapsible
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(
                dimens.playlistDescriptionParagraphSpacing
            )
        ) {
            visibleParagraphs.forEach { paragraph ->
                Text(
                    text = paragraph.text,
                    style = PlaylistDescriptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = paragraph.maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (collapsible) {
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .align(Alignment.End)
                        .defaultMinSize(minHeight = dimens.minTouchTarget)
                ) {
                    Text(
                        text = stringResource(
                            if (isExpanded) {
                                R.string.author_description_collapse
                            } else {
                                R.string.author_description_expand
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Делит описание на абзацы по пустой строке (\n\n). Одиночный перевод строки
 * остаётся внутри абзаца; пустые фрагменты отбрасываются. `\r\n`/`\r`
 * приводятся к `\n` (манифест уже нормализует их, но компонент не зависит
 * от этого). Чистая функция — покрыта JVM-тестом.
 */
internal fun splitDescriptionParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** Есть ли у описания больше [COLLAPSED_MAX_LINES] строк (нужен переключатель). */
internal fun isDescriptionCollapsible(lineCounts: List<Int>): Boolean =
    lineCounts.sum() > COLLAPSED_MAX_LINES

/**
 * Абзацы, которые видны в свёрнутом/развёрнутом виде. Свёрнутый вид показывает
 * суммарно не больше [COLLAPSED_MAX_LINES] строк: у последнего видимого абзаца
 * предел равен остатку, поэтому ellipsis рисуется ровно на пятой строке.
 * Развёрнутый — все абзацы без предела. Чистая функция — покрыта JVM-тестом.
 */
internal fun visibleDescriptionParagraphs(
    paragraphs: List<String>,
    lineCounts: List<Int>,
    expanded: Boolean
): List<DescriptionParagraph> {
    if (expanded) {
        return paragraphs.map { DescriptionParagraph(text = it, maxLines = Int.MAX_VALUE) }
    }
    var remaining = COLLAPSED_MAX_LINES
    val visible = mutableListOf<DescriptionParagraph>()
    paragraphs.forEachIndexed { index, paragraph ->
        if (remaining <= 0) return@forEachIndexed
        val lines = lineCounts.getOrElse(index) { 1 }.coerceAtLeast(1)
        val shownLines = minOf(remaining, lines)
        visible += DescriptionParagraph(text = paragraph, maxLines = shownLines)
        remaining -= shownLines
    }
    return visible
}
