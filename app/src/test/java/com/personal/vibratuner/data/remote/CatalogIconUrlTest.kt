package com.personal.vibratuner.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Разрешение поля `icon` относительно адреса каталога (Stage 2). */
class CatalogIconUrlTest {

    private val manifestUrl =
        "https://github.com/anichivo/playlists/raw/main/manifest.json"

    @Test
    fun `relative path is resolved against the manifest directory`() {
        assertEquals(
            "https://github.com/anichivo/playlists/raw/main/icons/icon.png",
            CatalogIconUrl.resolve("icons/icon.png", manifestUrl)
        )
    }

    @Test
    fun `dot and parent segments are resolved`() {
        assertEquals(
            "https://github.com/anichivo/playlists/raw/main/icons/a.png",
            CatalogIconUrl.resolve("./icons/a.png", manifestUrl)
        )
        assertEquals(
            "https://github.com/anichivo/playlists/raw/shared/a.png",
            CatalogIconUrl.resolve("../shared/a.png", manifestUrl)
        )
    }

    @Test
    fun `absolute https url is kept as written`() {
        assertEquals(
            "https://example.com/icons/a.png",
            CatalogIconUrl.resolve("  https://example.com/icons/a.png  ", manifestUrl)
        )
        // Без базы абсолютный адрес всё равно работает.
        assertEquals(
            "https://example.com/a.png",
            CatalogIconUrl.resolve("https://example.com/a.png", null)
        )
    }

    @Test
    fun `non https and foreign schemes are rejected`() {
        assertNull(CatalogIconUrl.resolve("http://example.com/a.png", manifestUrl))
        assertNull(CatalogIconUrl.resolve("content://media/a.png", manifestUrl))
        assertNull(CatalogIconUrl.resolve("file:///sdcard/a.png", manifestUrl))
        assertNull(CatalogIconUrl.resolve("data:image/png;base64,AAAA", manifestUrl))
        assertNull(CatalogIconUrl.resolve("C:\\icons\\a.png", manifestUrl))
    }

    @Test
    fun `relative path without a valid manifest base is rejected`() {
        assertNull(CatalogIconUrl.resolve("icons/a.png", null))
        assertNull(CatalogIconUrl.resolve("icons/a.png", ""))
        assertNull(CatalogIconUrl.resolve("icons/a.png", "http://example.com/manifest.json"))
    }

    @Test
    fun `blank and malformed values are rejected`() {
        assertNull(CatalogIconUrl.resolve(null, manifestUrl))
        assertNull(CatalogIconUrl.resolve("", manifestUrl))
        assertNull(CatalogIconUrl.resolve("   ", manifestUrl))
        assertNull(CatalogIconUrl.resolve("icons/my icon.png", manifestUrl))
        assertNull(CatalogIconUrl.resolve("https://example.com/my icon.png", manifestUrl))
        assertNull(CatalogIconUrl.resolve("https://", manifestUrl))
    }
}
