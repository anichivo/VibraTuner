package com.personal.vibratuner.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorManifestParserTest {

    private fun parse(raw: String): AuthorManifestParseResult = AuthorManifestParser.parse(raw)

    @Test
    fun `valid manifest is parsed with optional artist and unknown fields`() {
        val raw = """
            {
              "version": 1,
              "author": "Кто-то",
              "playlists": [
                {
                  "id": "morning",
                  "name": "Утро",
                  "cover": "https://example.com/cover.png",
                  "tracks": [
                    { "title": "Первый", "artist": "Автор", "url": "https://example.com/1.mp3" },
                    { "title": "Второй", "url": "https://example.com/2.mp3", "duration": 42 }
                  ]
                },
                {
                  "id": "evening",
                  "name": "Вечер",
                  "tracks": []
                }
              ]
            }
        """.trimIndent()

        val result = parse(raw)

        assertTrue(result is AuthorManifestParseResult.Success)
        val success = result as AuthorManifestParseResult.Success
        assertTrue(success.warnings.isEmpty())
        assertEquals(2, success.playlists.size)
        assertEquals("morning", success.playlists[0].id)
        assertEquals("Утро", success.playlists[0].name)
        assertEquals(2, success.playlists[0].tracks.size)
        assertEquals("Автор", success.playlists[0].tracks[0].artist)
        assertNull(success.playlists[0].tracks[1].artist)
        assertEquals("https://example.com/2.mp3", success.playlists[0].tracks[1].url)
        // Необязательная duration — секунды; отсутствие поля даёт 0.
        assertEquals(0L, success.playlists[0].tracks[0].durationMs)
        assertEquals(42_000L, success.playlists[0].tracks[1].durationMs)
        assertTrue(success.playlists[1].tracks.isEmpty())
    }

    @Test
    fun `optional duration ignores negative too long and non numeric values`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "tracks": [
                { "title": "A", "url": "https://example.com/a.mp3", "duration": -5 },
                { "title": "B", "url": "https://example.com/b.mp3", "duration": "42" },
                { "title": "C", "url": "https://example.com/c.mp3", "duration": 90000 }
              ] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success
        assertEquals(listOf(0L, 0L, 0L), success.playlists[0].tracks.map { it.durationMs })
    }

    @Test
    fun `optional description is trimmed and keeps line and paragraph breaks`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "description": "  Первый абзац.\nВторая строка.\n\nВторой абзац.\r\nКонец.  ", "tracks": [] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(
            "Первый абзац.\nВторая строка.\n\nВторой абзац.\nКонец.",
            success.playlists[0].description
        )
        assertTrue(success.warnings.isEmpty())
    }

    @Test
    fun `missing blank or non-string description is ignored without warnings`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "a", "name": "Нет поля", "tracks": [] },
              { "id": "b", "name": "Пробелы", "description": "   \n  ", "tracks": [] },
              { "id": "c", "name": "Число", "description": 42, "tracks": [] },
              { "id": "d", "name": "Массив", "description": ["текст"], "tracks": [] },
              { "id": "e", "name": "null", "description": null, "tracks": [] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(5, success.playlists.size)
        success.playlists.forEach { playlist -> assertNull(playlist.description) }
        assertTrue(success.warnings.isEmpty())
    }

    @Test
    fun `description longer than the limit is truncated with a warning`() {
        val longText = "я".repeat(AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH + 50)
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Длинное", "description": "$longText  ", "tracks": [] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(
            AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH,
            success.playlists[0].description?.length
        )
        assertEquals(1, success.warnings.size)
        assertTrue(success.warnings[0].contains(AUTHOR_PLAYLIST_DESCRIPTION_MAX_LENGTH.toString()))
    }

    @Test
    fun `playlist without id or name is skipped and logged`() {
        val raw = """
            {
              "version": 1,
              "playlists": [
                { "name": "Без id", "tracks": [] },
                { "id": "no-name", "tracks": [] },
                { "id": "ok", "name": "Нормальный", "tracks": [] }
              ]
            }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(1, success.playlists.size)
        assertEquals("ok", success.playlists[0].id)
        assertEquals(2, success.warnings.size)
    }

    @Test
    fun `track without title or url and non-https url are skipped`() {
        val raw = """
            {
              "version": 1,
              "playlists": [
                {
                  "id": "p1",
                  "name": "Смешанный",
                  "tracks": [
                    { "title": "Без url", "artist": "A" },
                    { "url": "https://example.com/no-title.mp3" },
                    { "title": "http", "url": "http://example.com/clear.mp3" },
                    { "title": "content", "url": "content://media/external/audio/1" },
                    { "title": "Хороший", "url": "https://example.com/good.mp3" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(1, success.playlists.size)
        assertEquals(1, success.playlists[0].tracks.size)
        assertEquals("Хороший", success.playlists[0].tracks[0].title)
        assertEquals(4, success.warnings.size)
    }

    @Test
    fun `github releases track url is kept as written including encoded characters`() {
        val releaseUrl =
            "https://github.com/anichivo/playlists/releases/download/" +
                "pl-nizkie-chastoty-basy/bassboosted-by-jony-sm-505cf2.mp3"
        val encodedUrl =
            "https://github.com/anichivo/playlists/releases/download/" +
                "pl-test/Say%20Shava%20Shava.mp3"
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "Имя", "tracks": [
                { "title": "A", "url": "$releaseUrl" },
                { "title": "B", "url": "$encodedUrl" }
              ] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(
            listOf(releaseUrl, encodedUrl),
            success.playlists[0].tracks.map { it.url }
        )
        assertTrue(success.warnings.isEmpty())
    }

    @Test
    fun `duplicate playlist id and duplicate track url are skipped`() {
        val raw = """
            {
              "version": 1,
              "playlists": [
                {
                  "id": "same",
                  "name": "Первый",
                  "tracks": [
                    { "title": "A", "url": "https://example.com/a.mp3" },
                    { "title": "A снова", "url": "https://example.com/a.mp3" }
                  ]
                },
                { "id": "same", "name": "Второй", "tracks": [] }
              ]
            }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals(1, success.playlists.size)
        assertEquals(1, success.playlists[0].tracks.size)
        assertEquals(2, success.warnings.size)
    }

    @Test
    fun `version newer than supported is a failure`() {
        val result = parse("""{ "version": 2, "playlists": [] }""")

        assertTrue(result is AuthorManifestParseResult.Failure)
        assertTrue((result as AuthorManifestParseResult.Failure).reason.contains("2"))
    }

    @Test
    fun `missing or non-numeric version is a failure`() {
        assertTrue(parse("""{ "playlists": [] }""") is AuthorManifestParseResult.Failure)
        assertTrue(parse("""{ "version": "1", "playlists": [] }""") is AuthorManifestParseResult.Failure)
        assertTrue(parse("""{ "version": 1.5, "playlists": [] }""") is AuthorManifestParseResult.Failure)
    }

    @Test
    fun `missing playlists array or root not object is a failure`() {
        assertTrue(parse("""{ "version": 1 }""") is AuthorManifestParseResult.Failure)
        assertTrue(parse("""{ "version": 1, "playlists": {} }""") is AuthorManifestParseResult.Failure)
        assertTrue(parse("""["version"]""") is AuthorManifestParseResult.Failure)
    }

    @Test
    fun `broken json is a failure`() {
        assertTrue(parse("""{ "version": 1, "playlists": [ } """) is AuthorManifestParseResult.Failure)
        assertTrue(parse("""{ "version": 1, "playlists": [] }trailing""") is AuthorManifestParseResult.Failure)
        assertTrue(parse("   ") is AuthorManifestParseResult.Failure)
    }

    @Test
    fun `empty playlists array is a successful empty manifest`() {
        val success = parse("""{ "version": 1, "playlists": [] }""") as AuthorManifestParseResult.Success

        assertTrue(success.playlists.isEmpty())
        assertTrue(success.warnings.isEmpty())
    }

    @Test
    fun `whitespace around id name and url is trimmed`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "  p  ", "name": "  Имя  ", "tracks": [
                { "title": "  Т  ", "artist": " ", "url": "  https://example.com/t.mp3  " }
              ] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals("p", success.playlists[0].id)
        assertEquals("Имя", success.playlists[0].name)
        assertEquals("Т", success.playlists[0].tracks[0].title)
        assertNull(success.playlists[0].tracks[0].artist)
        assertEquals("https://example.com/t.mp3", success.playlists[0].tracks[0].url)
    }

    @Test
    fun `unicode escapes and escaped quotes are decoded`() {
        val raw = """
            { "version": 1, "playlists": [
              { "id": "p", "name": "\u0423\u0442\u0440\u043e \"кавычки\"", "tracks": [] }
            ] }
        """.trimIndent()

        val success = parse(raw) as AuthorManifestParseResult.Success

        assertEquals("Утро \"кавычки\"", success.playlists[0].name)
    }
}
