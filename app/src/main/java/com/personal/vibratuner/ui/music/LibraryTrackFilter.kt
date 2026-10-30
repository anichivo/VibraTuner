package com.personal.vibratuner.ui.music

import androidx.annotation.StringRes
import com.personal.vibratuner.R
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.local.db.TrackSource

/**
 * Фильтр самостоятельных треков в нижней части «Моей коллекции» (Stage 3).
 *
 * Список «Треки» объединяет ДВА происхождения из `tracks.source` (Stage 2):
 * IMPORTED — добавленные с устройства, DOWNLOADED — скачанные из каталога по
 * одному. Треки, скачанные только в составе плейлиста, в `tracks` не попадают,
 * поэтому в фильтре «Скачанные» их нет по построению.
 *
 * Подписи и тексты пустых состояний — ТОЛЬКО из strings.xml. В чипах фильтра
 * НЕТ иконок (в том числе галочки у выбранного): выбор показывается цветом и
 * контейнером.
 */
enum class LibraryTrackFilter(
    @StringRes val labelRes: Int,
    @StringRes val emptyTextRes: Int
) {
    ALL(R.string.library_filter_all, R.string.library_tracks_empty_all),
    DOWNLOADED(R.string.library_filter_downloaded, R.string.library_tracks_empty_downloaded),
    IMPORTED(R.string.library_filter_imported, R.string.library_tracks_empty_imported);

    /**
     * Подходит ли трек фильтру. Неизвестное/пустое значение `tracks.source`
     * трактуется как IMPORTED — так же, как при чтении из БД (легаси-строки).
     */
    fun matches(track: TrackEntity): Boolean = when (this) {
        ALL -> true
        DOWNLOADED -> TrackSource.fromValue(track.source) == TrackSource.DOWNLOADED
        IMPORTED -> TrackSource.fromValue(track.source) == TrackSource.IMPORTED
    }
}

/**
 * Чистая функция фильтрации списка треков «Моей коллекции»: источник один —
 * список из Room (`MusicUiState.tracks`), порядок сохраняется. Покрыта
 * JVM-тестом. Для [LibraryTrackFilter.ALL] возвращается исходный список.
 */
fun filterLibraryTracks(
    tracks: List<TrackEntity>,
    filter: LibraryTrackFilter
): List<TrackEntity> = when (filter) {
    LibraryTrackFilter.ALL -> tracks
    else -> tracks.filter(filter::matches)
}
