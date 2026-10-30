package com.personal.vibratuner.data.remote

import com.personal.vibratuner.data.local.db.PlaylistTrackEntity

/**
 * Плейлист каталога «От автора» как источник звука будильника (этап «Каталог»).
 *
 * Будильник хранит только стабильный `alarms.catalogPlaylistId`, поэтому треки
 * нужны ТОЛЬКО в момент срабатывания: они читаются из кеша манифеста
 * (`filesDir/author_manifest.json`) и приводятся к строкам `playlist_tracks` —
 * ровно к тому виду, который ожидает существующий [AlarmPlaylistQueue.build].
 * Второго пути построения очереди нет: дальше работает прежний `PLAYLIST`-путь
 * сервиса и координатора.
 *
 * Схема Room не меняется: каталог в БД не хранится, треки в библиотеку не
 * копируются, порядок — порядок треков в манифесте.
 */
object CatalogAlarmPlaylist {

    /**
     * Строки плейлиста каталога в порядке манифеста. Синтетические id
     * (`-1 - index`) в БД не пишутся; [PlaylistTrackEntity.playlistId] = 0 —
     * у будильника каталога локального плейлиста нет.
     */
    fun tracks(playlist: AuthorManifestPlaylist): List<PlaylistTrackEntity> =
        playlist.tracks.mapIndexed { index, track ->
            PlaylistTrackEntity(
                id = -1L - index,
                playlistId = CATALOG_PLAYLIST_ID,
                trackUri = track.url,
                title = track.title,
                artist = track.artist,
                addedAt = index.toLong()
            )
        }

    /** Строки для будильника по `catalogPlaylistId`; пустой список — плейлиста нет. */
    fun tracks(catalogId: String, playlists: List<AuthorManifestPlaylist>?): List<PlaylistTrackEntity> {
        val playlist = playlists?.firstOrNull { it.id == catalogId } ?: return emptyList()
        return tracks(playlist)
    }

    /** Значение `playlistId` для синтетических строк: локального плейлиста нет. */
    const val CATALOG_PLAYLIST_ID = 0L
}
