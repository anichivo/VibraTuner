package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Part 2: восстановление последней сессии. Проверяем, что координатор готовит
 * трек в ПАУЗЕ (без действия запуска и без аудиофокуса), не перетирает другие
 * звуки и что последующий Play идёт обычным путём координатора.
 */
class SessionRestoreCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val track = TrackEntity(id = 7, uri = "content://audio/7", title = "Трек 7")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun restoreSession_inIdle_preparesPausedTrackWithoutStartingPlayback() {
        val actions = coordinator.processEvent(
            CoordinatorEvent.RestoreSession(track = track, positionMs = 42_000L)
        )

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(track, state.currentTrack)
        assertTrue("Восстановленная сессия должна быть на паузе", state.isPaused)

        assertEquals(1, actions.size)
        val prepare = actions.single() as CoordinatorAction.PreparePausedMusic
        assertEquals(track, prepare.track)
        assertEquals(42_000L, prepare.positionMs)
        assertTrue(
            "Восстановление не должно запускать воспроизведение",
            actions.none {
                it is CoordinatorAction.StartMusicPlayback ||
                    it is CoordinatorAction.ResumeMusicPlayback
            }
        )
        assertTrue("На паузе ничего не звучит", coordinator.currentSound.value == null)
    }

    @Test
    fun restoreSession_isRejectedWhileMicrosoundIsPlaying() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                maxDurationMs = 5_000L,
                timestampMs = 1_000L
            )
        )

        val actions = coordinator.processEvent(
            CoordinatorEvent.RestoreSession(track = track, positionMs = 1_000L)
        )

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertTrue(actions.any { it is CoordinatorAction.LogMessage })
        assertFalse(actions.any { it is CoordinatorAction.PreparePausedMusic })
    }

    @Test
    fun restoredSession_playResumesThroughCoordinator() {
        coordinator.processEvent(
            CoordinatorEvent.RestoreSession(track = track, positionMs = 5_000L)
        )

        val actions = coordinator.processEvent(CoordinatorEvent.UserResume)

        assertEquals(1, actions.size)
        assertTrue(actions.single() is CoordinatorAction.ResumeMusicPlayback)
        assertFalse((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
    }
}
