package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Встроенный каталог микрозвуков: стабильные id, кодирование `builtin:<id>`
 * в существующем поле `microsounds.uri` и безопасное разрешение неизвестных id.
 */
class BuiltinMicrosoundCatalogTest {

    @Test
    fun catalogIsNotEmpty() {
        assertTrue(BuiltinMicrosoundCatalog.entries.isNotEmpty())
    }

    @Test
    fun idsAreUnique() {
        val ids = BuiltinMicrosoundCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun idsUseStableSnakeCaseAndNeverBlank() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        BuiltinMicrosoundCatalog.entries.forEach { entry ->
            assertTrue("id '${entry.id}' должен быть непустым", entry.id.isNotBlank())
            assertTrue("id '${entry.id}' должен быть стабильным snake_case", pattern.matches(entry.id))
            // id никогда не содержит префикс: он добавляется кодированием.
            assertFalse(entry.id.startsWith(BuiltinMicrosoundCatalog.SCHEME_PREFIX))
        }
    }

    @Test
    fun everyEntryHasNameAndRawResource() {
        BuiltinMicrosoundCatalog.entries.forEach { entry ->
            assertTrue("у '${entry.id}' нет названия", entry.nameRes != 0)
            assertTrue("у '${entry.id}' нет raw-ресурса", entry.rawRes != 0)
        }
        val rawIds = BuiltinMicrosoundCatalog.entries.map { it.rawRes }
        assertEquals("raw-ресурсы не должны дублироваться", rawIds.size, rawIds.distinct().size)
    }

    @Test
    fun encodeAndParseAreReversible() {
        BuiltinMicrosoundCatalog.entries.forEach { entry ->
            val stored = BuiltinMicrosoundCatalog.encode(entry.id)
            assertEquals(BuiltinMicrosoundCatalog.SCHEME_PREFIX + entry.id, stored)
            assertTrue(BuiltinMicrosoundCatalog.hasBuiltinPrefix(stored))
            assertEquals(entry.id, BuiltinMicrosoundCatalog.parseId(stored))
            assertEquals(entry.id, BuiltinMicrosoundCatalog.findByStored(stored)?.id)
        }
    }

    @Test
    fun userFileUriIsNotBuiltin() {
        val userUri = "content://com.android.providers.downloads.documents/document/42"
        assertFalse(BuiltinMicrosoundCatalog.hasBuiltinPrefix(userUri))
        assertNull(BuiltinMicrosoundCatalog.parseId(userUri))
        assertNull(BuiltinMicrosoundCatalog.findByStored(userUri))
        assertNull(BuiltinMicrosoundCatalog.resourceUriString("com.personal.vibratuner", userUri))
    }

    @Test
    fun emptyBuiltinIdIsNotParsed() {
        assertTrue(BuiltinMicrosoundCatalog.hasBuiltinPrefix("builtin:"))
        assertNull(BuiltinMicrosoundCatalog.parseId("builtin:"))
        assertNull(BuiltinMicrosoundCatalog.parseId("builtin:   "))
        assertNull(BuiltinMicrosoundCatalog.parseId(""))
        assertNull(BuiltinMicrosoundCatalog.resourceUriString("com.personal.vibratuner", "builtin:"))
    }

    @Test
    fun unknownBuiltinIdResolvesToNull() {
        val stored = BuiltinMicrosoundCatalog.encode("no_such_sound")
        assertTrue(BuiltinMicrosoundCatalog.hasBuiltinPrefix(stored))
        assertEquals("no_such_sound", BuiltinMicrosoundCatalog.parseId(stored))
        assertNull(BuiltinMicrosoundCatalog.findById("no_such_sound"))
        assertNull(BuiltinMicrosoundCatalog.findByStored(stored))
        assertNull(BuiltinMicrosoundCatalog.resourceUriString("com.personal.vibratuner", stored))
    }

    @Test
    fun knownBuiltinResolvesToAndroidResourceUri() {
        val knownId = BuiltinMicrosoundCatalog.entries.first().id
        val stored = BuiltinMicrosoundCatalog.encode(knownId)
        val expected = "android.resource://com.personal.vibratuner/${
            BuiltinMicrosoundCatalog.findById(knownId)!!.rawRes
        }"
        assertEquals(expected, BuiltinMicrosoundCatalog.resourceUriString("com.personal.vibratuner", stored))
        assertNotNull(BuiltinMicrosoundCatalog.findByStored(stored))
    }

    /**
     * Сгенерированные плейсхолдеры v1 удалены вместе с файлами `res/raw`.
     * Их id НИКОГДА не возвращаются в каталог: сохранённые строки с ними
     * обязаны вести себя как неизвестный встроенный звук.
     */
    @Test
    fun removedGeneratedSoundsAreNeverResolved() {
        listOf("chime", "bell", "droplet", "ping", "low_gong", "pulse").forEach { legacyId ->
            assertNull("id '$legacyId' не должен вернуться в каталог", BuiltinMicrosoundCatalog.findById(legacyId))
            assertNull(BuiltinMicrosoundCatalog.findByStored(BuiltinMicrosoundCatalog.encode(legacyId)))
            assertNull(
                BuiltinMicrosoundCatalog.resourceUriString(
                    "com.personal.vibratuner",
                    BuiltinMicrosoundCatalog.encode(legacyId)
                )
            )
        }
    }

    @Test
    fun defaultStoredUriIsFirstKnownSound() {
        val default = BuiltinMicrosoundCatalog.defaultStoredUri()

        assertTrue(default.isNotBlank())
        assertEquals(BuiltinMicrosoundCatalog.entries.first().id, BuiltinMicrosoundCatalog.parseId(default))
        assertNotNull(BuiltinMicrosoundCatalog.findByStored(default))
    }

    @Test
    fun whitespaceAroundStoredValueIsTolerated() {
        val knownId = BuiltinMicrosoundCatalog.entries.last().id
        val stored = "  " + BuiltinMicrosoundCatalog.encode(knownId) + " "
        assertTrue(BuiltinMicrosoundCatalog.hasBuiltinPrefix(stored))
        assertEquals(knownId, BuiltinMicrosoundCatalog.parseId(stored))
        assertNotNull(BuiltinMicrosoundCatalog.findByStored(stored))
    }
}
