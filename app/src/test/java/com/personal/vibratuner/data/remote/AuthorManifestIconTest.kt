package com.personal.vibratuner.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Необязательное поле `icon` манифеста (Stage 2): обратная совместимость,
 * разрешение относительного пути, игнорирование неизвестных ключей.
 */
class AuthorManifestIconTest {

    private val manifestUrl =
        "https://github.com/anichivo/playlists/raw/main/manifest.json"

    private fun parse(raw: String, base: String? = manifestUrl): AuthorManifestParseResult =
        AuthorManifestParser.parse(raw, base)

    private fun success(raw: String, base: String? = manifestUrl): AuthorManifestParseResult.Success {
        val result = parse(raw, base)
        assertTrue(
            "ожидался успешный разбор, получено $result",
            result is AuthorManifestParseResult.Success
        )
        return result as AuthorManifestParseResult.Success
    }

    @Test
    fun `old catalog without icon parses as before`() {
        val raw = """
            {
              "version": 1,
              "playlists": [
                { "id": "p", "name": "Имя", "description": "Текст", "tracks": [
                    { "title": "A", "artist": "B", "url": "https://example.com/a.mp3" }
                ] }
              ]
            }
        """.trimIndent()

        val playlist = success(raw).playlists.single()

        assertNull(playlist.icon)
        assertEquals("Имя", playlist.name)
        assertEquals("Текст", playlist.description)
        assertEquals(1, playlist.tracks.size)
    }

    @Test
    fun `relative icon is resolved against the manifest location`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "icon": "icons/morning.png", "tracks": [] }
            ] }
        """.trimIndent()

        val playlist = success(raw).playlists.single()

        assertEquals(
            "https://github.com/anichivo/playlists/raw/main/icons/morning.png",
            playlist.icon
        )
    }

    @Test
    fun `absolute https icon is kept as written`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "icon": " https://cdn.example.com/p.png ", "tracks": [] }
            ] }
        """.trimIndent()

        assertEquals("https://cdn.example.com/p.png", success(raw).playlists.single().icon)
    }

    @Test
    fun `missing null blank and non-string icon become null without warnings`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "a", "name": "Нет поля", "tracks": [] },
              { "id": "b", "name": "null", "icon": null, "tracks": [] },
              { "id": "c", "name": "Пробелы", "icon": "   ", "tracks": [] },
              { "id": "d", "name": "Число", "icon": 42, "tracks": [] },
              { "id": "e", "name": "Массив", "icon": ["a.png"], "tracks": [] },
              { "id": "f", "name": "Объект", "icon": {"path": "a.png"}, "tracks": [] }
            ] }
        """.trimIndent()

        val result = success(raw)

        assertEquals(6, result.playlists.size)
        result.playlists.forEach { playlist -> assertNull(playlist.icon) }
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `non-https icon is ignored with a warning`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "icon": "http://example.com/a.png", "tracks": [] }
            ] }
        """.trimIndent()

        val result = success(raw)

        assertNull(result.playlists.single().icon)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("icon"))
    }

    @Test
    fun `relative icon without manifest base is ignored with a warning`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "icon": "icons/a.png", "tracks": [] }
            ] }
        """.trimIndent()

        val result = success(raw, base = null)

        assertNull(result.playlists.single().icon)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `unknown keys near icon are ignored`() {
        val raw = """
            {
              "version": 1,
              "author": "Кто-то",
              "playlists": [
                {
                  "id": "p",
                  "name": "Имя",
                  "cover": "https://example.com/cover.png",
                  "icon": "icons/p.png",
                  "color": { "hex": "#D97757" },
                  "tags": ["спокойное"],
                  "tracks": [
                    { "title": "A", "url": "https://example.com/a.mp3", "bitrate": 320 }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = success(raw)

        assertEquals(
            "https://github.com/anichivo/playlists/raw/main/icons/p.png",
            result.playlists.single().icon
        )
        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.playlists.single().tracks.size)
    }

    @Test
    fun `icon does not change track parsing or duration`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "icon": "icons/p.png", "tracks": [
                { "title": "A", "url": "https://example.com/a.mp3", "duration": 90 }
              ] }
            ] }
        """.trimIndent()

        val playlist = success(raw).playlists.single()

        assertEquals(90_000L, playlist.tracks.single().durationMs)
        assertEquals("https://example.com/a.mp3", playlist.tracks.single().url)
    }
}
