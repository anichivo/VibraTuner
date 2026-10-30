package com.personal.vibratuner.ui.music

import com.personal.vibratuner.data.local.db.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Любимое» — всегда первая карточка карусели «Моей медиатеки» (часть 2,
 * item 6). Остальные плейлисты сохраняют порядок репозитория.
 */
class PlaylistOrderingTest {

    @Test
    fun favoritesMovesToFrontKeepingOtherOrder() {
        val playlists = listOf(
            summary(id = 5L, name = "Рок"),
            summary(id = 2L, name = "Джаз"),
            summary(id = 1L, name = "", isSystem = true)
        )

        assertEquals(
            listOf("", "Рок", "Джаз"),
            favoritesFirst(playlists).map { it.name }
        )
    }

    @Test
    fun systemFlagIsEnoughEvenWithOtherId() {
        val playlists = listOf(
            summary(id = 7L, name = "Первый"),
            summary(id = 9L, name = "Системный", isSystem = true)
        )

        assertEquals(
            listOf("Системный", "Первый"),
            favoritesFirst(playlists).map { it.name }
        )
    }

    @Test
    fun favoritesIdIsEnoughWithoutSystemFlag() {
        // Наследие: строка id = 1 могла вернуться с isSystem = false.
        val playlists = listOf(
            summary(id = 3L, name = "Первый"),
            summary(id = 1L, name = "Любимое")
        )

        assertEquals(
            listOf("Любимое", "Первый"),
            favoritesFirst(playlists).map { it.name }
        )
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<PlaylistSummary>(), favoritesFirst(emptyList()))
    }

    private fun summary(id: Long, name: String, isSystem: Boolean = false) =
        PlaylistSummary(id = id, name = name, isSystem = isSystem, trackCount = 0)
}
