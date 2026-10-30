package com.personal.vibratuner.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistNameValidatorTest {

    @Test
    fun trimsSpacesAroundName() {
        assertEquals("Утренняя музыка", PlaylistNameValidator.validate("  Утренняя музыка  "))
    }

    @Test
    fun keepsInnerSpaces() {
        assertEquals("Мой плейлист", PlaylistNameValidator.validate("Мой плейлист"))
    }

    @Test
    fun rejectsEmptyName() {
        assertNull(PlaylistNameValidator.validate(""))
    }

    @Test
    fun rejectsWhitespaceOnlyName() {
        assertNull(PlaylistNameValidator.validate("   "))
    }

    @Test
    fun acceptsNameOfExactlyFiftyCharacters() {
        val name = "ф".repeat(PlaylistNameValidator.MAX_NAME_LENGTH)
        assertEquals(name, PlaylistNameValidator.validate(name))
    }

    @Test
    fun rejectsNameLongerThanFiftyCharacters() {
        val name = "ф".repeat(PlaylistNameValidator.MAX_NAME_LENGTH + 1)
        assertNull(PlaylistNameValidator.validate(name))
    }

    @Test
    fun trimsBeforeCheckingLengthLimit() {
        val name = "ф".repeat(PlaylistNameValidator.MAX_NAME_LENGTH)
        assertEquals(name, PlaylistNameValidator.validate("   $name   "))
    }
}
