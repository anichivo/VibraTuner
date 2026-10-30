package com.personal.vibratuner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбиение текста справки на абзацы: абзацы разделяются пустой строкой,
 * одиночный перевод строки остаётся внутри абзаца.
 */
class HelpParagraphsTest {

    @Test
    fun splitsParagraphsByBlankLine() {
        val result = splitHelpParagraphs("Первый абзац.\n\nВторой абзац.\n\nТретий.")

        assertEquals(
            listOf("Первый абзац.", "Второй абзац.", "Третий."),
            result
        )
    }

    @Test
    fun keepsSingleNewLineInsideParagraphAndTrimsEdges() {
        val result = splitHelpParagraphs("  Первая строка\nвторая строка  \n\n\nВторой абзац.  ")

        assertEquals(
            listOf("Первая строка\nвторая строка", "Второй абзац."),
            result
        )
    }

    @Test
    fun handlesWindowsLineEndingsAndEmptyText() {
        assertEquals(
            listOf("Абзац один.", "Абзац два."),
            splitHelpParagraphs("Абзац один.\r\n\r\nАбзац два.")
        )
        assertEquals(emptyList<String>(), splitHelpParagraphs("   \n\n  "))
    }
}
