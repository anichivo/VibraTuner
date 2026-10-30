package com.personal.vibratuner.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая логика проверки обновлений (часть 1): сравнение версий, разбор ответа
 * GitHub `/releases/latest` и правило «раз в сутки». Android и сеть не нужны.
 */
class UpdateCheckerTest {

    // ---------- Сравнение версий ----------

    @Test
    fun comparesNumericSegmentsNotLexicographically() {
        // 1.2.0 СТАРШЕ 1.1.9...
        assertTrue(UpdateVersion.isNewer("v1.2.0", "1.1.9"))
        // ...но МЛАДШЕ 1.10.0: 2 < 10, хотя строка "1.2.0" длиннее
        assertFalse(UpdateVersion.isNewer("v1.2.0", "1.10.0"))
        assertTrue(UpdateVersion.isNewer("v1.10.0", "1.2.0"))
        assertTrue(UpdateVersion.isNewer("1.10", "1.9.9"))
    }

    @Test
    fun treatsMissingSegmentAsZero() {
        assertFalse(UpdateVersion.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateVersion.isNewer("1.0.0", "1.0"))
        assertTrue(UpdateVersion.isNewer("1.0.1", "1.0"))
        assertTrue(UpdateVersion.isNewer("1", "0.9.9"))
    }

    @Test
    fun stripsLeadingVAndIgnoresSuffixes() {
        assertFalse(UpdateVersion.isNewer("v1.1.0-beta", "1.1.0"))
        assertTrue(UpdateVersion.isNewer("1.1.0-beta", "1.0.9"))
        assertTrue(UpdateVersion.isNewer("V2.0.0-RC1", "1.9.9"))
        assertFalse(UpdateVersion.isNewer("1.2.0+build.5", "1.2.0"))
        assertTrue(UpdateVersion.isNewer("v1.2.0+build.5", "1.1.0"))
    }

    @Test
    fun garbageVersionsMeanNoUpdate() {
        assertFalse(UpdateVersion.isNewer("abc", "1.0"))
        assertFalse(UpdateVersion.isNewer("v", "1.0"))
        assertFalse(UpdateVersion.isNewer("1.0.", "1.0"))
        assertFalse(UpdateVersion.isNewer("1..0", "1.0"))
        assertFalse(UpdateVersion.isNewer("   ", "1.0"))
        assertFalse(UpdateVersion.isNewer(null, "1.0"))
        assertFalse(UpdateVersion.isNewer("1.0", "garbage"))
        assertFalse(UpdateVersion.isNewer("1.0", null))
        assertFalse(UpdateVersion.isNewer("1.0", ""))
        // Сегмент, не влезающий в Long, тоже считается неразбираемым.
        assertFalse(UpdateVersion.isNewer("99999999999999999999.0", "1.0"))
    }

    @Test
    fun doesNotReportUpdateForSameVersion() {
        assertFalse(UpdateVersion.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateVersion.isNewer("v1.1.0", "1.1.0"))
        assertFalse(UpdateVersion.isNewer("1.1.0", "1.1.0.0"))
        assertFalse(UpdateVersion.isNewer("0.9.9", "1.0"))
    }

    @Test
    fun reportsParsabilitySeparately() {
        assertTrue(UpdateVersion.isParsable("v1.2.0"))
        assertTrue(UpdateVersion.isParsable("1.0"))
        assertFalse(UpdateVersion.isParsable("beta"))
        assertFalse(UpdateVersion.isParsable(null))
        assertEquals(listOf(1L, 2L, 0L), UpdateVersion.segments("v1.2.0-beta"))
    }

    // ---------- Разбор ответа GitHub ----------

    @Test
    fun parsesSampleGitHubResponse() {
        val release = LatestReleaseParser.parse(SAMPLE_RESPONSE)

        assertEquals("v1.1.0", release?.versionName)
        assertEquals(
            "https://github.com/anichivo/VibraTuner/releases/tag/v1.1.0",
            release?.releaseUrl
        )
    }

    @Test
    fun rejectsResponseWithoutRequiredFields() {
        assertNull(LatestReleaseParser.parse("""{"html_url":"https://example.com/a"}"""))
        assertNull(LatestReleaseParser.parse("""{"tag_name":"v1.1.0"}"""))
        assertNull(LatestReleaseParser.parse("""{"tag_name":"","html_url":"https://example.com/a"}"""))
        assertNull(LatestReleaseParser.parse("""{"tag_name":"v1.1.0","html_url":""}"""))
        // Не https — ссылку нельзя считать безопасной.
        assertNull(
            LatestReleaseParser.parse(
                """{"tag_name":"v1.1.0","html_url":"http://github.com/a/b/releases/tag/v1.1.0"}"""
            )
        )
        // Не строка, а число.
        assertNull(LatestReleaseParser.parse("""{"tag_name":110,"html_url":"https://example.com"}"""))
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(LatestReleaseParser.parse(null))
        assertNull(LatestReleaseParser.parse(""))
        assertNull(LatestReleaseParser.parse("   "))
        assertNull(LatestReleaseParser.parse("<html>Not Found</html>"))
        assertNull(LatestReleaseParser.parse("""{"tag_name": }"""))
        assertNull(LatestReleaseParser.parse("""["v1.1.0"]"""))
        assertNull(LatestReleaseParser.parse("""{"tag_name":"v1.1.0","html_url":"https://x"}trailing"""))
    }

    // ---------- Раз в сутки ----------

    @Test
    fun checksWhenNeverCheckedOrAfterDay() {
        val now = 1_700_000_000_000L
        assertTrue(UpdateCheckInterval.isDue(lastCheckTimeMs = 0L, nowMs = now))
        assertFalse(UpdateCheckInterval.isDue(now - 23L * HOUR_MS, now))
        assertTrue(UpdateCheckInterval.isDue(now - 24L * HOUR_MS, now))
        assertTrue(UpdateCheckInterval.isDue(now - 25L * HOUR_MS, now))
        // Часы ушли назад: не повторяем запрос до восстановления времени.
        assertFalse(UpdateCheckInterval.isDue(now + HOUR_MS, now))
    }

    private companion object {
        const val HOUR_MS: Long = 3_600_000L

        /** Реалистичный ответ GitHub (сокращённый, лишние поля игнорируются). */
        const val SAMPLE_RESPONSE: String = """
            {
              "url": "https://api.github.com/repos/anichivo/VibraTuner/releases/123",
              "html_url": "https://github.com/anichivo/VibraTuner/releases/tag/v1.1.0",
              "assets_url": "https://api.github.com/repos/anichivo/VibraTuner/releases/123/assets",
              "id": 123,
              "tag_name": "v1.1.0",
              "name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-10-30T07:00:00Z",
              "body": "Что нового:\r\n- Проверка обновлений\r\n- Исправления",
              "reactions": {"url": "https://api.github.com/x", "total_count": 3},
              "assets": []
            }
        """
    }
}
