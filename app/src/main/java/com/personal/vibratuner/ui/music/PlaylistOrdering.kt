package com.personal.vibratuner.ui.music

import com.personal.vibratuner.data.local.db.PlaylistEntity
import com.personal.vibratuner.data.local.db.PlaylistSummary

/**
 * Порядок карточек в карусели «Моей медиатеки»: «Любимое» ВСЕГДА первое,
 * независимо от порядка запроса репозитория и любых будущих сортировок.
 * Остальные плейлисты сохраняют исходный порядок — `sortedWith` стабилен.
 */
internal fun favoritesFirst(playlists: List<PlaylistSummary>): List<PlaylistSummary> =
    playlists.sortedWith(
        compareByDescending { playlist ->
            playlist.isSystem || playlist.id == PlaylistEntity.FAVORITES_PLAYLIST_ID
        }
    )
