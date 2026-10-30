package com.personal.vibratuner.data.local.alarm

import com.personal.vibratuner.data.local.db.PlaylistTrackEntity
import com.personal.vibratuner.data.local.db.TrackEntity
import kotlin.random.Random

/**
 * Построение очереди будильника с источником PLAYLIST (чистая функция).
 *
 * Порядок строк плейлиста — как отдал `PlaylistRepository.getTracksOnce`
 * (`addedAt ASC, id ASC`). При `shuffle = true` порядок перемешивается ОДИН раз
 * при срабатывании; дальше сервис играет очередь по кругу (repeat-all).
 *
 * Строки плейлиста — снимки: трека может уже не быть в библиотеке, поэтому
 * создаётся синтетический [TrackEntity] с отрицательным id (в БД не пишется).
 * URI передаётся как есть: в этапе 4 это будут https-URL, и сервис откроет их
 * тем же путём Media3.
 */
object AlarmPlaylistQueue {

    /**
     * @param tracks треки плейлиста в сохранённом порядке.
     * @param shuffle перемешать порядок (случайность можно зафиксировать в тесте).
     * @return очередь для `SoundPlaybackService`; пустой список, если плейлист пуст.
     */
    fun build(
        tracks: List<PlaylistTrackEntity>,
        shuffle: Boolean,
        random: Random = Random.Default
    ): List<TrackEntity> {
        if (tracks.isEmpty()) return emptyList()
        val ordered = if (shuffle) tracks.shuffled(random) else tracks
        return ordered.mapIndexed { index, track ->
            TrackEntity(
                // Отрицательные id — синтетические строки плейлиста, в БД их нет.
                id = -1L - index,
                uri = track.trackUri,
                title = track.title,
                artist = track.artist.orEmpty(),
                durationMs = 0L,
                dateAdded = track.addedAt
            )
        }
    }
}
