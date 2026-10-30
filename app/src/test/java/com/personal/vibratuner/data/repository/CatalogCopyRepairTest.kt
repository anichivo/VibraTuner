package com.personal.vibratuner.data.repository

import com.personal.vibratuner.data.local.db.PlaylistCopyCandidate
import com.personal.vibratuner.data.remote.AuthorManifestPlaylist
import com.personal.vibratuner.data.remote.AuthorTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Самовосстановление `catalogId` у копий каталога, созданных до v10 (item 3).
 *
 * Главный регресс-тест — «общий трек в чужом плейлисте»: раньше он давал ложное
 * «Добавлено» всему каталогу и тупик (нельзя ни добавить, ни удалить).
 */
class CatalogCopyRepairTest {

    private fun authorPlaylist(
        id: String,
        name: String,
        vararg urls: String
    ) = AuthorManifestPlaylist(
        id = id,
        name = name,
        tracks = urls.map { AuthorTrack(title = "Трек $it", artist = null, url = it) }
    )

    private fun copy(
        id: Long,
        name: String,
        vararg urls: String
    ) = PlaylistCopyCandidate(playlistId = id, name = name, trackUris = urls.toList())

    private val morning = authorPlaylist(
        "morning",
        "Утро",
        "https://example.com/1.mp3",
        "https://example.com/2.mp3"
    )

    @Test
    fun copyByNameAndTrackSubsetIsRepaired() {
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(morning),
            copies = listOf(copy(7, "Утро", "https://example.com/1.mp3"))
        )

        assertEquals(listOf(CatalogCopyRepair.Assignment(7, "morning")), assignment)
    }

    @Test
    fun renamedCopyWithExactTrackSetIsRepaired() {
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(morning),
            copies = listOf(
                copy(
                    7,
                    "Моё утро",
                    "https://example.com/1.mp3",
                    "https://example.com/2.mp3"
                )
            )
        )

        assertEquals(listOf(CatalogCopyRepair.Assignment(7, "morning")), assignment)
    }

    @Test
    fun singleSharedTrackInUserPlaylistIsNotRepaired() {
        // Пользователь добавил ОДИН авторский трек в свой плейлист через «⋮».
        // Это НЕ копия плейлиста: каталог обязан снова показать «Добавить к себе».
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(morning),
            copies = listOf(copy(5, "Мои любимые", "https://example.com/1.mp3"))
        )

        assertTrue(assignment.isEmpty())
    }

    @Test
    fun copyWithForeignTrackIsNotRepaired() {
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(morning),
            copies = listOf(
                copy(
                    7,
                    "Утро",
                    "https://example.com/1.mp3",
                    "content://music/other"
                )
            )
        )

        assertTrue(assignment.isEmpty())
    }

    @Test
    fun emptyCatalogPlaylistIsRepairedOnlyByNameAndEmptyCopy() {
        val empty = authorPlaylist("empty", "Тишина")

        assertEquals(
            listOf(CatalogCopyRepair.Assignment(9, "empty")),
            CatalogCopyRepair.assignments(
                authorPlaylists = listOf(empty),
                copies = listOf(copy(9, "Тишина"))
            )
        )
        assertTrue(
            CatalogCopyRepair.assignments(
                authorPlaylists = listOf(empty),
                copies = listOf(copy(9, "Тишина", "content://music/1"))
            ).isEmpty()
        )
    }

    @Test
    fun eachCopyAndEachCatalogIdAreUsedAtMostOnce() {
        val evening = authorPlaylist("evening", "Вечер", "https://example.com/3.mp3")
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(morning, evening),
            copies = listOf(
                copy(7, "Утро", "https://example.com/1.mp3"),
                copy(8, "Утро", "https://example.com/2.mp3")
            )
        )

        assertEquals(listOf(CatalogCopyRepair.Assignment(7, "morning")), assignment)
    }

    @Test
    fun urlsAreComparedAfterNormalization() {
        // Манифест принимает и «HTTPS://», а в playlist_tracks схема нормализована
        // в нижний регистр: копия обязана опознаваться в обоих вариантах.
        val assignment = CatalogCopyRepair.assignments(
            authorPlaylists = listOf(
                authorPlaylist("morning", "Утро", "HTTPS://example.com/1.mp3")
            ),
            copies = listOf(
                copy(
                    7,
                    "Утро",
                    "https://example.com/1.mp3",
                    "HTTPS://example.com/1.mp3"
                )
            )
        )

        assertEquals(listOf(CatalogCopyRepair.Assignment(7, "morning")), assignment)
    }

    @Test
    fun noCandidatesMeansNoRepair() {
        assertTrue(
            CatalogCopyRepair.assignments(
                authorPlaylists = listOf(morning),
                copies = emptyList()
            ).isEmpty()
        )
        assertTrue(CatalogCopyRepair.assignments(emptyList(), listOf(copy(7, "Утро"))).isEmpty())
    }
}
