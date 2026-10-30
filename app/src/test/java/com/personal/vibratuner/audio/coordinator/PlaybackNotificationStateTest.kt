package com.personal.vibratuner.audio.coordinator

import com.personal.vibratuner.data.local.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единственный маппер «состояние координатора -> содержимое уведомления».
 *
 * Проверяется чистая логика без Android: какие кнопки видит пользователь, как
 * отличается играющая музыка от паузы и какие состояния уведомления не дают.
 */
class PlaybackNotificationStateTest {

    private val track = TrackEntity(id = 1, uri = "content://music/1", title = "Песня")

    @Test
    fun idlePreviewAndSpeakerOnSilence_haveNoNotification() {
        assertNull(PlaybackNotificationContent.from(CoordinatorState.Idle))
        assertNull(
            PlaybackNotificationContent.from(
                CoordinatorState.Previewing(previewTrack = track)
            )
        )
        assertNull(
            PlaybackNotificationContent.from(
                CoordinatorState.MicroSoundActive(
                    soundUri = "content://sounds/speaker-on.wav",
                    savedTrack = track,
                    resumeMusicAfter = true,
                    isPinnedAlarmPrelude = true,
                    isSpeakerOnSilence = true,
                    startedAtMs = 1_000L,
                    maxDurationMs = 35_000L
                )
            )
        )
    }

    @Test
    fun playingMusic_showsTrackWithPlayPauseAndStop() {
        val state = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false)
        )

        assertEquals(CurrentSoundKind.MUSIC, state?.kind)
        assertEquals("Песня", state?.titleOrNull)
        assertTrue("Кнопка показывает «Пауза»", state!!.isPlaying)
        assertEquals(
            listOf(NotificationButton.PLAY_PAUSE, NotificationButton.STOP),
            state.buttons
        )
    }

    @Test
    fun pausedMusic_keepsNotificationWithPlayAction() {
        val state = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = true)
        )

        // Пауза НЕ убирает уведомление: иначе продолжить трек из шторки нельзя.
        assertEquals("Песня", state?.titleOrNull)
        assertFalse("Кнопка показывает «Играть»", state!!.isPlaying)
        assertTrue(state.canPlayPause)
        assertTrue(state.canStop)
    }

    @Test
    fun alarmMusic_usesAlarmKind() {
        val playing = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false),
            alarmMusicActive = true
        )
        assertEquals(CurrentSoundKind.ALARM, playing?.kind)

        val paused = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = true),
            alarmMusicActive = true
        )
        assertEquals("Пауза сигнала остаётся «Сеансом»", CurrentSoundKind.ALARM, paused?.kind)
        assertFalse(paused!!.isPlaying)
    }

    @Test
    fun choppySilentWindow_explainsPauseAndCountsDownToResume() {
        val state = PlaybackNotificationContent.from(
            CoordinatorState.ChoppyPauseActive(
                savedTrack = track,
                resumePlaybackAfter = true,
                startedAtMs = 1_000L,
                maxDurationMs = 60_000L
            )
        )

        // Название трека остаётся в модели: по нему «Остановить» узнаёт, что
        // действие относится к актуальному звуку (в шторке показывается причина).
        assertEquals("Песня", state?.titleOrNull)
        assertEquals(NotificationPlaybackStatus.PAUSED, state?.status)
        assertFalse("Кнопка должна показывать «Играть»", state!!.isPlaying)
        assertEquals(NotificationPauseReason.CHOPPY, state.pauseReason)
        assertEquals(
            "Время возврата = момент старта окна + его длительность",
            61_000L,
            state.resumeAtMs
        )
        assertTrue("Play возвращает музыку немедленно", state.canPlayPause)
        assertTrue(state.canStop)
    }

    @Test
    fun choppyResumeTimestamp_isExactStartPlusDuration() {
        val window = CoordinatorState.ChoppyPauseActive(
            savedTrack = track,
            resumePlaybackAfter = true,
            startedAtMs = 1_700_000_000_000L,
            maxDurationMs = 90_000L
        )

        // Ровно +Y миллисекунд: никаких округлений и «остатков» на секунду.
        assertEquals(1_700_000_090_000L, window.resumeAtMs)
    }

    @Test
    fun userPause_hasNoCountdownOrChoppyExplanation() {
        val state = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = true)
        )

        assertFalse(state!!.isPlaying)
        assertEquals(NotificationPauseReason.USER, state.pauseReason)
        assertNull("Ручная пауза не обещает автоматического возврата", state.resumeAtMs)
    }

    @Test
    fun playFromNotificationDuringChoppyPause_resumesImmediately() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 60_000L,
                timestampMs = 1_000L,
                positionMs = 5_000L
            )
        )

        val paused = coordinator.notificationState.value!!
        assertFalse("В окне тишины звука нет — показана пауза", paused.isPlaying)
        assertEquals(NotificationPauseReason.CHOPPY, paused.pauseReason)
        assertEquals(
            "Точный момент возврата доходит до уведомления",
            61_000L,
            paused.resumeAtMs
        )

        // Нажатие «Играть»: координатор закрывает окно и запускает музыку.
        val actions = coordinator.processEvent(CoordinatorEvent.UserResume)

        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertFalse((coordinator.state as CoordinatorState.MusicPlaying).isPaused)
        assertTrue(
            "Усиление музыки восстанавливается действием сервиса",
            actions.any { it is CoordinatorAction.UnmuteMusicPlayback }
        )
        val resumed = coordinator.notificationState.value!!
        assertTrue("Уведомление больше не говорит «пауза»", resumed.isPlaying)
        assertNull(resumed.resumeAtMs)
        assertNull(resumed.pauseReason)
    }

    @Test
    fun microsoundDuringChoppyPause_keepsMicrosoundPriorityInNotification() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(durationMs = 60_000L, timestampMs = 1_000L)
        )
        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                displayName = "Звонок",
                timestampMs = 2_000L
            )
        )

        val state = coordinator.notificationState.value!!
        assertEquals("Микрозвук важнее окна тишины", CurrentSoundKind.MICROSOUND, state.kind)
        assertEquals("Звонок", state.titleOrNull)
        assertTrue(state.isPlaying)
        assertNull("У микрозвука нет обратного отсчёта", state.resumeAtMs)
    }

    @Test
    fun microsound_hasNoPlayPauseButton() {
        val state = PlaybackNotificationContent.from(
            CoordinatorState.MicroSoundActive(
                soundUri = "content://sounds/bell.wav",
                savedTrack = track,
                resumeMusicAfter = true,
                displayName = "Звонок",
                startedAtMs = 2_000L,
                maxDurationMs = 10_000L
            )
        )
        assertEquals(CurrentSoundKind.MICROSOUND, state?.kind)
        assertEquals("Звонок", state?.titleOrNull)
        assertTrue(state!!.isPlaying)
        assertEquals(listOf(NotificationButton.STOP), state.buttons)
    }

    @Test
    fun forceNull_hidesNotificationButNotMicrosound() {
        assertNull(
            PlaybackNotificationContent.from(
                CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false),
                forceNull = true
            )
        )
        assertTrue(
            PlaybackNotificationContent.from(
                CoordinatorState.MicroSoundActive(
                    soundUri = "content://sounds/bell.wav",
                    savedTrack = track,
                    resumeMusicAfter = true,
                    startedAtMs = 2_000L,
                    maxDurationMs = 10_000L
                ),
                forceNull = true
            ) != null
        )
    }

    @Test
    fun playbackMode_isShownOnlyForManualMusic() {
        val shuffle = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false),
            playbackMode = PlaybackMode.SHUFFLE
        )
        assertEquals(PlaybackMode.SHUFFLE, shuffle?.playbackMode)

        val alarm = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false),
            alarmMusicActive = true,
            playbackMode = PlaybackMode.SHUFFLE
        )
        assertNull("У сигнала сеанса режим не показывается", alarm?.playbackMode)

        val off = PlaybackNotificationContent.from(
            CoordinatorState.MusicPlaying(currentTrack = track, isPaused = false)
        )
        assertNull("Обычный режим не занимает подзаголовок", off?.playbackMode)
    }

    @Test
    fun coordinatorExposesNotificationState_forPlayingAndPausedMusic() {
        val coordinator = PlaybackCoordinator()
        assertNull(coordinator.notificationState.value)

        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        assertTrue(coordinator.notificationState.value!!.isPlaying)

        coordinator.processEvent(CoordinatorEvent.UserPause)
        assertNull("currentSound на паузе пуст", coordinator.currentSound.value)
        assertFalse(
            "но уведомление остаётся с кнопкой «Играть»",
            coordinator.notificationState.value!!.isPlaying
        )

        coordinator.processEvent(CoordinatorEvent.UserResume)
        assertTrue(coordinator.notificationState.value!!.isPlaying)
    }

    @Test
    fun manualPause_isNotResumedAfterMicrosound() {
        val coordinator = PlaybackCoordinator()
        coordinator.processEvent(CoordinatorEvent.UserPlay(track))
        coordinator.processEvent(CoordinatorEvent.UserPause)
        assertTrue((coordinator.state as CoordinatorState.MusicPlaying).isPaused)

        coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/bell.wav",
                displayName = "Звонок",
                timestampMs = 2_000L
            )
        )
        val actions = coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(3_000L))

        assertTrue(
            "Ручную паузу микрозвук не отменяет",
            actions.none { it is CoordinatorAction.ResumeMusicPlayback }
        )
        val state = coordinator.state as CoordinatorState.MusicPlaying
        assertTrue("Музыка осталась на паузе", state.isPaused)
        assertFalse(coordinator.notificationState.value!!.isPlaying)

        // И play/pause из уведомления после этого снова продолжает трек.
        coordinator.processEvent(CoordinatorEvent.UserResume)
        assertTrue(coordinator.notificationState.value!!.isPlaying)
    }
}
