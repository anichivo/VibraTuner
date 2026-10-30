package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.coordinator.CoordinatorAction
import com.personal.vibratuner.audio.coordinator.CoordinatorEvent
import com.personal.vibratuner.audio.coordinator.CoordinatorState
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.PlaybackCoordinator
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.audio.coordinator.TrackHistory
import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Этап 3: режим порядка (OFF / SHUFFLE / REPEAT_ONE), история «Предыдущего»
 * и перезапуск трека в REPEAT_ONE. Логика полностью в координаторе, поэтому
 * проверяется без Android-инструментальных тестов.
 */
class PlaybackModeAndHistoryTest {

    private lateinit var coordinator: PlaybackCoordinator

    private fun track(id: Long) = TrackEntity(
        id = id,
        uri = "content://audio/$id",
        title = "Трек $id",
        // Детерминированное значение вместо дефолтного System.currentTimeMillis():
        // assertEquals по data-классу иначе флейкует на границе миллисекунды.
        dateAdded = id
    )

    /** Как сервис: старт трека по событию + фактический старт в плеере. */
    private fun playStarted(track: TrackEntity) {
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.onMusicTrackStarted(track)
    }

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    // ---------------------------------------------------------------- режим

    @Test
    fun playbackMode_startsOff_andCyclesThroughThreeStates() {
        assertEquals(PlaybackMode.OFF, coordinator.playbackMode.value)

        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        assertEquals(PlaybackMode.SHUFFLE, coordinator.playbackMode.value)

        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        assertEquals(PlaybackMode.REPEAT_ONE, coordinator.playbackMode.value)

        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        assertEquals(PlaybackMode.OFF, coordinator.playbackMode.value)
    }

    @Test
    fun cyclingMode_doesNotDisturbMicrosoundPriorityState() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://micro/1",
                timestampMs = 1_000L,
                maxDurationMs = 5_000L
            )
        )
        val stateBefore = coordinator.state

        val actions = coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)

        assertEquals(stateBefore, coordinator.state)
        assertEquals(PlaybackMode.SHUFFLE, coordinator.playbackMode.value)
        assertTrue(actions.single() is CoordinatorAction.LogMessage)
    }

    @Test
    fun manualNext_keepsMode() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        playStarted(track(1))
        val actions = coordinator.processEvent(CoordinatorEvent.UserNext)

        assertEquals(PlaybackMode.SHUFFLE, coordinator.playbackMode.value)
        assertTrue(actions.single() is CoordinatorAction.PlayNextTrack)
    }

    @Test
    fun reset_restoresOffModeAndClearsHistory() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        playStarted(track(1))
        playStarted(track(2))
        assertEquals(1, coordinator.historySize)

        coordinator.reset()

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertEquals(PlaybackMode.OFF, coordinator.playbackMode.value)
        assertEquals(0, coordinator.historySize)
        assertNull(coordinator.currentSound.value)
    }

    // ----------------------------------------------------------- repeat one

    @Test
    fun repeatOne_trackEnded_restartsCurrentTrackInsteadOfNext() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        assertEquals(PlaybackMode.REPEAT_ONE, coordinator.playbackMode.value)
        playStarted(track(1))

        val actions = coordinator.processEvent(CoordinatorEvent.TrackEnded)

        assertTrue(actions.any { it is CoordinatorAction.SeekTo })
        assertTrue(actions.any { it is CoordinatorAction.ResumeMusicPlayback })
        assertTrue(actions.none { it is CoordinatorAction.PlayNextTrack })
        assertEquals(track(1), (coordinator.state as CoordinatorState.MusicPlaying).currentTrack)
    }

    @Test
    fun repeatOne_trackEnded_keepsCurrentSoundSoNotificationDoesNotDisappear() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        playStarted(track(1))

        coordinator.processEvent(CoordinatorEvent.TrackEnded)

        assertNotNull(coordinator.currentSound.value)
        assertEquals(CurrentSoundKind.MUSIC, coordinator.currentSound.value?.kind)
    }

    @Test
    fun repeatOne_doesNotApplyToAlarmMusic() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        coordinator.processEvent(CoordinatorEvent.UserPlay(track(1), isAlarm = true))
        coordinator.onMusicTrackStarted(track(1))

        val actions = coordinator.processEvent(CoordinatorEvent.TrackEnded)

        // Музыка будильника живёт по своим прежним правилам.
        assertTrue(actions.single() is CoordinatorAction.PlayNextTrack)
    }

    @Test
    fun offMode_trackEnded_playsNextTrack() {
        playStarted(track(1))

        val actions = coordinator.processEvent(CoordinatorEvent.TrackEnded)

        assertTrue(actions.single() is CoordinatorAction.PlayNextTrack)
    }

    @Test
    fun repeatOne_trackError_stillSkipsToNextTrack() {
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        coordinator.processEvent(CoordinatorEvent.UserCyclePlaybackMode)
        playStarted(track(1))

        val actions = coordinator.processEvent(CoordinatorEvent.TrackError(track(1), null))

        assertTrue(actions.any { it is CoordinatorAction.PlayNextTrack })
        assertTrue(actions.none { it is CoordinatorAction.SeekTo })
    }

    // ------------------------------------------------------- предыдущий

    @Test
    fun previous_overThreeSeconds_restartsCurrentTrack() {
        playStarted(track(1))
        playStarted(track(2))

        val actions = coordinator.processEvent(
            CoordinatorEvent.UserPrevious(TrackHistory.RESTART_THRESHOLD_MS + 1)
        )

        assertTrue(actions.any { it is CoordinatorAction.SeekTo })
        assertTrue(actions.none { it is CoordinatorAction.PlayPreviousTrack })
        assertEquals(track(2), (coordinator.state as CoordinatorState.MusicPlaying).currentTrack)
        assertEquals(1, coordinator.historySize)
    }

    @Test
    fun previous_underThreeSeconds_playsLastPlayedTrack() {
        playStarted(track(1))
        playStarted(track(2))

        val actions = coordinator.processEvent(CoordinatorEvent.UserPrevious(500L))

        val previous = actions.filterIsInstance<CoordinatorAction.PlayPreviousTrack>().single()
        assertEquals(track(1), previous.track)
        assertEquals(track(1), (coordinator.state as CoordinatorState.MusicPlaying).currentTrack)
        assertEquals(0, coordinator.historySize)
    }

    @Test
    fun previous_withEmptyHistory_restartsCurrentTrack() {
        playStarted(track(1))

        val actions = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))

        assertTrue(actions.any { it is CoordinatorAction.SeekTo })
        assertTrue(actions.none { it is CoordinatorAction.PlayPreviousTrack })
    }

    @Test
    fun previous_twice_walksBackThroughHistoryInsteadOfToggling() {
        playStarted(track(1))
        playStarted(track(2))
        playStarted(track(3))

        val first = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))
            .filterIsInstance<CoordinatorAction.PlayPreviousTrack>().single()
        assertEquals(track(2), first.track)
        // Сервис стартует трек из истории: покидаемый трек в историю не попадает.
        coordinator.onMusicTrackStarted(first.track, recordInHistory = false)

        val second = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))
            .filterIsInstance<CoordinatorAction.PlayPreviousTrack>().single()
        assertEquals(track(1), second.track)
    }

    @Test
    fun previous_historyIsBoundedTo50Tracks() {
        repeat(60) { index ->
            playStarted(track(index.toLong() + 1))
        }

        var returned = 0
        while (true) {
            val action = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))
                .filterIsInstance<CoordinatorAction.PlayPreviousTrack>()
                .singleOrNull() ?: break
            returned++
            coordinator.onMusicTrackStarted(action.track, recordInHistory = false)
        }

        assertEquals(TrackHistory.DEFAULT_CAPACITY, returned)
    }

    @Test
    fun previous_duringMicrosound_isRejectedWithLogAndDoesNotChangeState() {
        playStarted(track(1))
        playStarted(track(2))
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://micro/1",
                timestampMs = 2_000L,
                maxDurationMs = 5_000L
            )
        )
        val stateBefore = coordinator.state

        val actions = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))

        assertEquals(stateBefore, coordinator.state)
        assertTrue(actions.single() is CoordinatorAction.LogMessage)
        assertEquals(1, coordinator.historySize)
    }

    @Test
    fun previous_inIdle_logsOnly() {
        val actions = coordinator.processEvent(CoordinatorEvent.UserPrevious(0L))

        assertTrue(actions.single() is CoordinatorAction.LogMessage)
        assertEquals(CoordinatorState.Idle, coordinator.state)
    }

    @Test
    fun userStop_clearsPreviousHistory() {
        playStarted(track(1))
        playStarted(track(2))
        assertEquals(1, coordinator.historySize)

        coordinator.processEvent(CoordinatorEvent.UserStop)

        assertEquals(0, coordinator.historySize)
    }

    @Test
    fun restartingSameTrack_doesNotGrowHistory() {
        playStarted(track(1))
        playStarted(track(1))

        assertEquals(0, coordinator.historySize)
    }
}
