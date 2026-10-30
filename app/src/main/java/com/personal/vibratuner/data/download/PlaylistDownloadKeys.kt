package com.personal.vibratuner.data.download

/**
 * Ключи заданий скачивания плейлистов (этап «ручное скачивание»).
 *
 * - Плейлист «Моей коллекции» — его собственный `playlists.id` (положительный).
 * - Плейлист каталога «От автора», НЕ добавленный в библиотеку, — стабильный
 *   отрицательный ключ от `catalogId`: в БД его нет, но скачать его можно.
 *
 * Ключ — ТОЛЬКО адрес задания в [TrackDownloadManager]; сам признак «трек
 * скачан» по-прежнему определяется наличием файла по https-URL, поэтому файлы
 * одного и того же трека не дублируются из разных плейлистов. Один плейлист —
 * одно задание: одинаковый ключ не запускает вторую загрузку.
 */
object PlaylistDownloadKeys {

    /** Ключ плейлиста библиотеки (id положительный, конфликтов с каталогом нет). */
    fun libraryPlaylist(playlistId: Long): Long = playlistId

    /**
     * Стабильный отрицательный ключ плейлиста каталога: FNV-1a 64-bit от
     * [catalogId], знак снят, результат сдвинут в диапазон `..-1`. Ноль и
     * положительные значения заняты id плейлистов библиотеки, поэтому задания
     * двух разных плейлистов не могут пересечься на практике.
     */
    fun catalogPlaylist(catalogId: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in catalogId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        val magnitude = hash and Long.MAX_VALUE
        return -magnitude - 1L
    }

    /** Скачивается ли этот трек вообще (только https-адреса). */
    fun isDownloadable(trackUri: String): Boolean =
        TrackDownloadStore.fileNameFor(trackUri) != null

    /** Есть ли в списке треки, которые вообще скачиваются (только https). */
    fun hasDownloadableTracks(trackUris: Collection<String>): Boolean =
        trackUris.any { isDownloadable(it) }

    /**
     * Part 3, задача 2: ВСЕ скачиваемые треки списка уже лежат на устройстве.
     * Треки с устройства (`content://`/`file://`) не считаются «нескачанными»:
     * их файлы принадлежат пользователю и удаление приложения их не касается.
     * Пустой список скачиваемых треков даёт `false` — кнопка «Удалить из памяти»
     * не появляется, если скачивать нечего.
     */
    fun allDownloadableDownloaded(
        trackUris: Collection<String>,
        downloadedUris: Set<String>
    ): Boolean {
        val downloadable = trackUris
            .map { it.trim() }
            .filter { isDownloadable(it) }
            .distinct()
        return downloadable.isNotEmpty() && downloadable.all { it in downloadedUris }
    }

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
}
