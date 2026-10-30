package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Предпрослушивание трека в окне выбора мелодии (Stage 2).
 *
 * Главное правило: предпрослушивание — САМЫЙ низкий приоритет. Оно принимается
 * только в покое, не прерывает микрозвук/окно тишины/музыку/восстановленную
 * сессию, никогда не поднимает системную громкость (`appIsAudible` = false),
 * не показывается как «звук приложения» (`currentSound` = null) и не может
 * оставить музыку на паузе навсегда.
 */
class PreviewCoordinatorTest {

    private lateinit var coordinator: PlaybackCoordinator
    private val sampleTrack1 = TrackEntity(id = 1, uri = "content://audio/1", title = "Трек 1")
    private val sampleTrack2 = TrackEntity(id = 2, uri = "content://audio/2", title = "Трек 2")

    @Before
    fun setup() {
        coordinator = PlaybackCoordinator()
    }

    @Test
    fun previewInIdle_startsPreview_andPublishesUri() {
        val actions = coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        assertTrue(coordinator.state is CoordinatorState.Previewing)
        assertEquals(
            sampleTrack1,
            (coordinator.state as CoordinatorState.Previewing).previewTrack
        )
        assertEquals(listOf<CoordinatorAction>(CoordinatorAction.StartTrackPreview(sampleTrack1)), actions)
        assertEquals(sampleTrack1.uri, coordinator.previewTrackUri.value)
        assertTrue("В покое предпрослушивание разрешено", coordinator.previewAvailable.value)
    }

    @Test
    fun previewIsNotAppSound_andNeverAudible() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        assertNull("Предпрослушивание не показывается как звук приложения", coordinator.currentSound.value)
        assertFalse(
            "Предпрослушивание никогда не поднимает системную громкость",
            coordinator.appIsAudible.value
        )
    }

    @Test
    fun previewWhileMusicPlaying_isSkippedWithLog() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack2))

        // Состояние музыки не изменилось, плеер не переключался.
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertEquals(sampleTrack1, (coordinator.state as CoordinatorState.MusicPlaying).currentTrack)
        assertNull(coordinator.previewTrackUri.value)
        assertFalse(
            "Пока играет музыка, предпрослушивание недоступно (кнопка приглушена)",
            coordinator.previewAvailable.value
        )
        assertEquals(1, actions.size)
        val log = actions.single()
        assertTrue(log is CoordinatorAction.LogMessage)
        assertTrue((log as CoordinatorAction.LogMessage).isWarning)
        assertTrue(log.message.contains("Предпрослушивание пропущено"))
    }

    @Test
    fun previewWhileMusicPaused_isAllowed_andSessionIsRemembered() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(CoordinatorEvent.UserPause)

        val actions = coordinator.processEvent(
            CoordinatorEvent.PreviewPlay(sampleTrack2, pausedPositionMs = 42_000L)
        )

        val state = coordinator.state as CoordinatorState.Previewing
        assertEquals(sampleTrack2, state.previewTrack)
        assertEquals("Музыка на паузе запоминается", sampleTrack1, state.pausedTrack)
        assertEquals(42_000L, state.pausedPositionMs)
        assertEquals(listOf<CoordinatorAction>(CoordinatorAction.StartTrackPreview(sampleTrack2)), actions)
        assertEquals(sampleTrack2.uri, coordinator.previewTrackUri.value)
        assertTrue(
            "При музыке на паузе прослушивание разрешено",
            coordinator.previewAvailable.value
        )
    }

    @Test
    fun stopPreview_restoresPausedMusicAtSamePosition() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(CoordinatorEvent.UserPause)
        coordinator.processEvent(
            CoordinatorEvent.PreviewPlay(sampleTrack2, pausedPositionMs = 42_000L)
        )

        val actions = coordinator.processEvent(CoordinatorEvent.StopPreview)

        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(sampleTrack1, state.currentTrack)
        assertTrue("Музыка возвращается на паузу, а не остаётся навсегда замолчавшей", state.isPaused)
        assertEquals(
            listOf<CoordinatorAction>(
                CoordinatorAction.StopTrackPreview,
                CoordinatorAction.PreparePausedMusic(sampleTrack1, 42_000L)
            ),
            actions
        )
        assertNull(coordinator.previewTrackUri.value)
        assertTrue(coordinator.previewAvailable.value)
    }

    @Test
    fun previewWhileMicrosound_isSkipped() {
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 1_000L
            )
        )

        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun previewWhileChoppySilentWindow_isSkipped() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(durationMs = 60_000L, timestampMs = 1_000L)
        )

        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack2))

        assertTrue(coordinator.state is CoordinatorState.ChoppyPauseActive)
        assertNull(coordinator.previewTrackUri.value)
        // Окно тишины не закрыто: усиление музыки не сброшено.
        assertFalse(coordinator.appIsAudible.value)
    }

    @Test
    fun anotherPreviewTrack_replacesCurrentPreview() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack2))

        assertEquals(
            sampleTrack2,
            (coordinator.state as CoordinatorState.Previewing).previewTrack
        )
        assertEquals(listOf<CoordinatorAction>(CoordinatorAction.StartTrackPreview(sampleTrack2)), actions)
        assertEquals(sampleTrack2.uri, coordinator.previewTrackUri.value)
    }

    @Test
    fun samePreviewTrackTwice_doesNotRestartPlayback() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        assertTrue(coordinator.state is CoordinatorState.Previewing)
        assertTrue("Повторный запуск того же трека игнорируется", actions.isEmpty())
    }

    @Test
    fun stopPreview_stopsPlayer_andClearsUri() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.StopPreview)

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertEquals(listOf<CoordinatorAction>(CoordinatorAction.StopTrackPreview), actions)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun previewEnded_stopsPlayer_withoutNextTrack() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.PreviewEnded)

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertEquals(listOf<CoordinatorAction>(CoordinatorAction.StopTrackPreview), actions)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun previewError_returnsToIdle_withLog() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(
            CoordinatorEvent.PreviewError(IllegalStateException("нет файла"))
        )

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertNull(coordinator.previewTrackUri.value)
        assertTrue(actions.contains(CoordinatorAction.StopTrackPreview))
        assertTrue(actions.any { it is CoordinatorAction.LogMessage })
    }

    @Test
    fun watchdogTick_doesNotInterruptPreview() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(
            CoordinatorEvent.WatchdogTimeoutTick(currentTimestampMs = 99_999L)
        )

        assertTrue(coordinator.state is CoordinatorState.Previewing)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun microsoundPreemptsPreview() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )

        // Предпрослушивание остановлено ДО запуска микрозвука, микрозвук играет.
        assertEquals(CoordinatorAction.StopTrackPreview, actions.first())
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
        assertTrue(coordinator.state is CoordinatorState.MicroSoundActive)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun userPlayPreemptsPreview_andMusicKeepsPlaying() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        val actions = coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))

        assertEquals(CoordinatorAction.StopTrackPreview, actions.first())
        assertTrue(actions.any { it is CoordinatorAction.StartMusicPlayback })
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun microsoundPreemptsPreview_ofPausedSession_andSessionStays() {
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack1))
        coordinator.processEvent(CoordinatorEvent.UserPause)
        coordinator.processEvent(
            CoordinatorEvent.PreviewPlay(sampleTrack2, pausedPositionMs = 42_000L)
        )

        val actions = coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                timestampMs = 2_000L
            )
        )

        assertEquals(CoordinatorAction.StopTrackPreview, actions.first())
        assertTrue(
            actions.contains(CoordinatorAction.PreparePausedMusic(sampleTrack1, 42_000L))
        )
        assertTrue(actions.any { it is CoordinatorAction.PlayMicroSoundTrack })
        val state = coordinator.state as CoordinatorState.MicroSoundActive
        assertEquals("Сессия не потеряна", sampleTrack1, state.savedTrack)
        assertFalse("Музыка была на паузе — сама не заиграет", state.resumeMusicAfter)
        assertNull(coordinator.previewTrackUri.value)

        // Микрозвук закончился: музыка снова на паузе, ничего не «залипло».
        coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))
        val restored = coordinator.state as CoordinatorState.MusicPlaying
        assertEquals(sampleTrack1, restored.currentTrack)
        assertTrue(restored.isPaused)
    }

    @Test
    fun resetClearsPreview() {
        coordinator.processEvent(CoordinatorEvent.PreviewPlay(sampleTrack1))

        coordinator.reset()

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertNull(coordinator.previewTrackUri.value)
    }

    @Test
    fun stopPreviewInIdle_isSilentNoOp() {
        val actions = coordinator.processEvent(CoordinatorEvent.StopPreview)

        assertEquals(CoordinatorState.Idle, coordinator.state)
        assertTrue("Остановка не начатого предпрослушивания не пишет в лог", actions.isEmpty())
    }
}
