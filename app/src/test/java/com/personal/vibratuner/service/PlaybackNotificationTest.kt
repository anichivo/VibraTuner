package com.personal.vibratuner.service

import com.personal.vibratuner.R
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.NotificationButton
import com.personal.vibratuner.audio.coordinator.NotificationPauseReason
import com.personal.vibratuner.audio.coordinator.NotificationPlaybackStatus
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.audio.coordinator.PlaybackNotificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Подписи видов звука в единственном уведомлении воспроизведения.
 * Проверяется чистое сопоставление «вид -> строковый ресурс» без Android runtime.
 */
class PlaybackNotificationTest {

    @Test
    fun everyKindHasDistinctLabelResource() {
        val labels = CurrentSoundKind.entries.map { PlaybackNotification.kindLabelRes(it) }

        assertEquals(CurrentSoundKind.entries.size, labels.toSet().size)
    }

    @Test
    fun kindLabelsMatchRequiredRussianLabels() {
        assertEquals(
            R.string.notification_kind_music,
            PlaybackNotification.kindLabelRes(CurrentSoundKind.MUSIC)
        )
        assertEquals(
            R.string.notification_kind_microsound,
            PlaybackNotification.kindLabelRes(CurrentSoundKind.MICROSOUND)
        )
        assertEquals(
            R.string.notification_kind_speaker_on,
            PlaybackNotification.kindLabelRes(CurrentSoundKind.SPEAKER_ON)
        )
        assertEquals(
            R.string.notification_kind_fake_speaker_on,
            PlaybackNotification.kindLabelRes(CurrentSoundKind.FAKE_SPEAKER_ON)
        )
        assertEquals(
            R.string.notification_kind_alarm,
            PlaybackNotification.kindLabelRes(CurrentSoundKind.ALARM)
        )
    }

    @Test
    fun statusLabelsDistinguishPlayingAndPaused() {
        val playing = notificationState(status = NotificationPlaybackStatus.PLAYING)
        val paused = notificationState(status = NotificationPlaybackStatus.PAUSED)

        assertEquals(
            R.string.notification_state_playing,
            PlaybackNotification.statusLabelRes(playing)
        )
        assertEquals(
            R.string.notification_state_paused,
            PlaybackNotification.statusLabelRes(paused)
        )
    }

    @Test
    fun subtitleShowsPlaybackModeInsteadOfGenericMusicLabel() {
        assertEquals(
            R.string.notification_mode_shuffle,
            PlaybackNotification.sourceLabelRes(
                notificationState(mode = PlaybackMode.SHUFFLE)
            )
        )
        assertEquals(
            R.string.notification_mode_repeat_one,
            PlaybackNotification.sourceLabelRes(
                notificationState(mode = PlaybackMode.REPEAT_ONE)
            )
        )
        assertEquals(
            "Без режима — обычная подпись вида звука",
            R.string.notification_kind_music,
            PlaybackNotification.sourceLabelRes(notificationState(mode = null))
        )
    }

    @Test
    fun choppyPause_hasOwnTitleAndText_noCountdownForOtherPauses() {
        val choppy = notificationState(status = NotificationPlaybackStatus.PAUSED).copy(
            pauseReason = NotificationPauseReason.CHOPPY,
            resumeAtMs = 2_000L
        )

        assertEquals(
            R.string.notification_choppy_pause_title,
            PlaybackNotification.pauseReasonTitleRes(choppy)
        )
        assertEquals(
            R.string.notification_choppy_pause_text,
            PlaybackNotification.pauseReasonTextRes(choppy)
        )

        val userPause = notificationState(status = NotificationPlaybackStatus.PAUSED)
        assertEquals(NotificationPauseReason.USER, userPause.pauseReason)
        assertNull(
            "Ручная пауза не показывает причину прерывистой музыки",
            PlaybackNotification.pauseReasonTitleRes(userPause)
        )
        assertNull(PlaybackNotification.pauseReasonTextRes(userPause))

        val playing = notificationState(status = NotificationPlaybackStatus.PLAYING)
        assertNull(PlaybackNotification.pauseReasonTitleRes(playing))
        assertNull(PlaybackNotification.pauseReasonTextRes(playing))
    }

    private fun notificationState(
        status: NotificationPlaybackStatus = NotificationPlaybackStatus.PLAYING,
        mode: PlaybackMode? = null
    ) = PlaybackNotificationState(
        kind = CurrentSoundKind.MUSIC,
        trackTitle = "Песня",
        status = status,
        buttons = listOf(NotificationButton.PLAY_PAUSE, NotificationButton.STOP),
        playbackMode = mode,
        // Совпадает с тем, что даёт маппер для ручной паузы.
        pauseReason = if (status == NotificationPlaybackStatus.PAUSED) {
            NotificationPauseReason.USER
        } else {
            null
        }
    )
}
