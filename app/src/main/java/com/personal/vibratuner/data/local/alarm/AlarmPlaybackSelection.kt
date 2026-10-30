package com.personal.vibratuner.data.local.alarm

import com.personal.vibratuner.data.local.db.TrackEntity

/**
 * Что играть при срабатывании будильника.
 *
 * @property track Выбранный трек или `null`, если играет вся библиотека.
 * @property loopsOnlySelected true — трек повторяется весь сигнал (shuffle игнорируется).
 * @property selectedTrackMissing true — трек был выбран, но удалён из библиотеки;
 *           воспроизведение откатывается на всю библиотеку.
 */
data class AlarmPlaybackTarget(
    val track: TrackEntity?,
    val loopsOnlySelected: Boolean,
    val selectedTrackMissing: Boolean
)

/**
 * Чистое разрешение выбранного трека будильника по текущей библиотеке.
 * Никогда не бросает исключений: отсутствующий трек — это откат на всю библиотеку.
 */
object AlarmPlaybackSelection {

    fun resolve(trackId: Long?, tracks: List<TrackEntity>): AlarmPlaybackTarget {
        if (trackId == null) {
            return AlarmPlaybackTarget(track = null, loopsOnlySelected = false, selectedTrackMissing = false)
        }
        val track = tracks.firstOrNull { it.id == trackId }
        return if (track != null) {
            AlarmPlaybackTarget(track = track, loopsOnlySelected = true, selectedTrackMissing = false)
        } else {
            AlarmPlaybackTarget(track = null, loopsOnlySelected = false, selectedTrackMissing = true)
        }
    }
}
