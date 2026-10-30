package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity

/**
 * История реально проигранных треков для кнопки «Предыдущий» (этап 3).
 *
 * Чистая Kotlin-логика без Android: живёт в координаторе, только в памяти.
 * Стек хранит треки, которые звучали ДО текущего (не более [capacity]),
 * поэтому «предыдущий» — это честный `pop`, а не переключение туда-обратно.
 *
 * При возврате назад ([recordStart] с `recordInHistory = false`) текущий трек
 * в историю НЕ попадает: иначе повторное нажатие «предыдущий» ходило бы между
 * двумя треками вместо движения назад по истории.
 */
class TrackHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val previousTracks = ArrayDeque<TrackEntity>()
    private var currentTrack: TrackEntity? = null

    /** Сколько треков ещё можно вернуть назад (0 — история пуста). */
    val size: Int get() = previousTracks.size

    /**
     * Трек реально начал звучать.
     *
     * @param recordInHistory true — обычный (прямой) переход: покидаемый трек
     * кладётся в историю; false — возврат по истории: ничего не кладём.
     */
    fun recordStart(track: TrackEntity, recordInHistory: Boolean = true) {
        val outgoing = currentTrack
        if (recordInHistory &&
            outgoing != null &&
            !sameTrack(outgoing, track)
        ) {
            previousTracks.addLast(outgoing)
            while (previousTracks.size > capacity) {
                previousTracks.removeFirst()
            }
        }
        currentTrack = track
    }

    /** Предыдущий трек из истории; история пуста — null (тогда текущий перезапускают). */
    fun popPrevious(): TrackEntity? = previousTracks.removeLastOrNull()

    /** История больше не нужна (пользовательская остановка/уничтожение сервиса). */
    fun clear() {
        previousTracks.clear()
        currentTrack = null
    }

    /** Сравнение по стабильному идентификатору проекта — URI (id в плейлистах синтетические). */
    private fun sameTrack(first: TrackEntity, second: TrackEntity): Boolean =
        first.uri == second.uri

    companion object {
        /** Верхняя граница истории: последние 50 треков. */
        const val DEFAULT_CAPACITY: Int = 50

        /**
         * Порог «перезапустить текущий трек вместо перехода назад»:
         * позиция больше 3 секунд — пользователь ожидает начала трека заново.
         */
        const val RESTART_THRESHOLD_MS: Long = 3_000L
    }
}
