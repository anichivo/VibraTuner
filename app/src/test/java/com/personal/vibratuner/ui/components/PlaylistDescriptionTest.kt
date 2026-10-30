package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбиение описания «От автора» на абзацы и свёрнутый вид по 5 строк.
 * Одиночный перевод строки — внутри абзаца, пустая строка — граница абзаца.
 */
class PlaylistDescriptionTest {

    @Test
    fun splitsParagraphsByBlankLineAndKeepsSingleNewLine() {
        val result = splitDescriptionParagraphs(
            "  Первый абзац.\nВторая строка.  \n\nВторой абзац.\n\n\nТретий.  "
        )

        assertEquals(
            listOf("Первый абзац.\nВторая строка.", "Второй абзац.", "Третий."),
            result
        )
    }

    @Test
    fun handlesWindowsLineEndingsAndEmptyText() {
        assertEquals(
            listOf("Абзац один.", "Абзац два."),
            splitDescriptionParagraphs("Абзац один.\r\n\r\nАбзац два.")
        )
        assertEquals(emptyList<String>(), splitDescriptionParagraphs("   \n\n  "))
    }

    @Test
    fun collapsibleOnlyWhenMoreThanFiveLines() {
        assertFalse(isDescriptionCollapsible(listOf(2, 3)))
        assertFalse(isDescriptionCollapsible(listOf(5)))
        assertTrue(isDescriptionCollapsible(listOf(2, 4)))
        assertTrue(isDescriptionCollapsible(listOf(6)))
    }

    @Test
    fun collapsedViewLimitsTotalLinesAndMarksLastVisibleParagraph() {
        val paragraphs = listOf("A", "B", "C")
        val visible = visibleDescriptionParagraphs(
            paragraphs = paragraphs,
            lineCounts = listOf(2, 3, 4),
            expanded = false
        )

        // 2 + 3 = 5 строк: третий абзац не виден, у второго предел строк полный.
        assertEquals(
            listOf(
                DescriptionParagraph(text = "A", maxLines = 2),
                DescriptionParagraph(text = "B", maxLines = 3)
            ),
            visible
        )

        val cut = visibleDescriptionParagraphs(
            paragraphs = paragraphs,
            lineCounts = listOf(2, 5, 4),
            expanded = false
        )
        // На второй абзац остаётся 3 строки — там и будет ellipsis.
        assertEquals(
            listOf(
                DescriptionParagraph(text = "A", maxLines = 2),
                DescriptionParagraph(text = "B", maxLines = 3)
            ),
            cut
        )
    }

    @Test
    fun expandedViewShowsAllParagraphsWithoutLimit() {
        val visible = visibleDescriptionParagraphs(
            paragraphs = listOf("A", "B"),
            lineCounts = listOf(4, 4),
            expanded = true
        )

        assertEquals(
            listOf(
                DescriptionParagraph(text = "A", maxLines = Int.MAX_VALUE),
                DescriptionParagraph(text = "B", maxLines = Int.MAX_VALUE)
            ),
            visible
        )
    }
}
