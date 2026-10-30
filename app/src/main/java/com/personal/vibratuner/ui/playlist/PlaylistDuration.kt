package com.personal.vibratuner.ui.playlist

/**
 * Итоговая длительность плейлиста. Показываем её только когда длительность
 * известна у ВСЕХ треков: иначе сумма была бы неполной и вводила бы в заблуждение.
 * Пустой плейлист тоже не имеет итога.
 *
 * У локальных плейлистов длительность берётся из библиотеки (`tracks.durationMs`),
 * у read-only «От автора» — из необязательного поля `duration` манифеста.
 */
fun playlistTotalDurationMs(tracks: List<PlaylistTrackUi>): Long? {
    if (tracks.isEmpty()) return null
    if (tracks.any { it.durationMs <= 0L }) return null
    return tracks.sumOf { it.durationMs }
}
