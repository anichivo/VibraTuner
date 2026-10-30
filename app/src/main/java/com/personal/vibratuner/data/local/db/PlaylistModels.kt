package com.personal.vibratuner.data.local.db

/**
 * Краткая карточка плейлиста для списка: количество треков считается запросом.
 * Для системного плейлиста [name] пустое — UI берёт локализованную подпись по [isSystem].
 * [catalogId] — стабильный id каталога «От автора» у добавленной копии; `null` —
 * пользовательский плейлист или «Любимое». По нему карточка находит иконку
 * каталога в манифесте (Stage 3); схема БД при этом НЕ меняется.
 */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val isSystem: Boolean,
    val trackCount: Int,
    val catalogId: String? = null
)

/**
 * Входные данные для добавления трека в плейлист. URI нормализуется так же,
 * как `tracks.uri`; [artist] может отсутствовать.
 */
data class PlaylistTrackInput(
    val trackUri: String,
    val title: String,
    val artist: String? = null
)

/**
 * Плейлист каталога «От автора» для добавления в библиотеку.
 * [catalogId] — стабильный id из манифеста (`AuthorManifestPlaylist.id`),
 * он же сохраняется в `playlists.catalogId`; [tracks] — снимок метаданных.
 */
data class CatalogPlaylistInput(
    val catalogId: String,
    val name: String,
    val tracks: List<PlaylistTrackInput> = emptyList()
)

/**
 * Сколько будильников используют плейлист как источник звука
 * (`alarms.sourceType = 'PLAYLIST'`). Нужно предупреждению при удалении.
 */
data class PlaylistAlarmUsage(
    val playlistId: Long,
    val alarmCount: Int
)

/**
 * Локальный плейлист с `catalogId = NULL` — кандидат на самовосстановление
 * признака «добавлен из каталога» (копии, созданные до версии БД 10).
 * Ремонт только записывает стабильный `catalogId`; данные не удаляются.
 */
data class PlaylistCopyCandidate(
    val playlistId: Long,
    val name: String,
    val trackUris: List<String>
)
