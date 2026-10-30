package com.personal.vibratuner.data.repository

import com.personal.vibratuner.data.local.db.PlaylistCopyCandidate
import com.personal.vibratuner.data.local.db.TrackUriNormalizer
import com.personal.vibratuner.data.remote.AuthorManifestPlaylist

/**
 * Самовосстановление стабильного `playlists.catalogId` у копий каталога,
 * созданных до версии БД 10 (у них `catalogId = NULL`).
 *
 * Признак «Добавлено» в каталоге вычисляется ТОЛЬКО по наличию локального
 * плейлиста с этим `catalogId` ([PlaylistRepository.observeAddedCatalogIds]).
 * Чтобы старые копии не «потеряли» признак (и их нельзя было добавить второй
 * раз), при открытии каталога выполняется однократный ремонт: подходящей копии
 * записывается `catalogId`. Ремонт НИКОГДА ничего не удаляет и не меняет треки.
 *
 * Копия каталога опознаётся консервативно:
 * - пустой плейлист каталога — локальный плейлист с тем же именем БЕЗ треков;
 * - непустой — все треки копии входят в треки каталога и имя совпадает, ЛИБО
 *   набор треков копии ТОЧНО равен набору треков каталога (копию переименовали).
 *
 * Один общий трек в чужом плейлисте (меню «⋮» -> «Добавить в плейлист…»)
 * НИКОГДА не считается копией всего плейлиста — именно эта эвристика давала
 * ложное «Добавлено» без плейлиста в библиотеке и делала состояние тупиковым.
 *
 * Функции чистые: покрыты JVM-тестом, данные не читают.
 */
object CatalogCopyRepair {

    /** Какой локальный плейлист получает какой стабильный id каталога. */
    data class Assignment(
        val playlistId: Long,
        val catalogId: String
    )

    fun assignments(
        authorPlaylists: List<AuthorManifestPlaylist>,
        copies: List<PlaylistCopyCandidate>
    ): List<Assignment> {
        if (authorPlaylists.isEmpty() || copies.isEmpty()) return emptyList()

        val usedPlaylistIds = mutableSetOf<Long>()
        val usedCatalogIds = mutableSetOf<String>()
        val result = mutableListOf<Assignment>()

        for (author in authorPlaylists) {
            if (author.id in usedCatalogIds) continue
            val authorUrls = author.tracks.mapTo(linkedSetOf()) {
                TrackUriNormalizer.normalize(it.url)
            }
            val authorName = author.name.trim()
            val copy = copies.firstOrNull { candidate ->
                candidate.playlistId !in usedPlaylistIds &&
                    isCopyOf(
                        authorUrls = authorUrls,
                        authorName = authorName,
                        candidate = candidate
                    )
            } ?: continue
            usedPlaylistIds += copy.playlistId
            usedCatalogIds += author.id
            result += Assignment(playlistId = copy.playlistId, catalogId = author.id)
        }
        return result
    }

    private fun isCopyOf(
        authorUrls: Set<String>,
        authorName: String,
        candidate: PlaylistCopyCandidate
    ): Boolean {
        val candidateName = candidate.name.trim()
        val candidateUrls = candidate.trackUris.mapTo(linkedSetOf()) {
            TrackUriNormalizer.normalize(it)
        }
        if (authorUrls.isEmpty()) {
            // Пустой плейлист каталога: единственный признак — имя, и копия пуста.
            return candidateUrls.isEmpty() && candidateName == authorName
        }
        if (candidateUrls.isEmpty()) return false
        // В копии не может быть треков, которых нет в каталоге.
        if (!authorUrls.containsAll(candidateUrls)) return false
        // Переименованная копия: весь набор треков совпадает.
        if (candidateUrls == authorUrls) return true
        // Обычная копия «Добавить к себе»: то же имя, все её треки — треки каталога.
        return candidateName == authorName
    }
}
