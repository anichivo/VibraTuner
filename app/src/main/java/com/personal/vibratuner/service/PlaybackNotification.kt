package com.personal.vibratuner.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.personal.vibratuner.MainActivity
import com.personal.vibratuner.R
import com.personal.vibratuner.VibraTunerApplication
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.NotificationButton
import com.personal.vibratuner.audio.coordinator.NotificationPauseReason
import com.personal.vibratuner.audio.coordinator.PlaybackMode
import com.personal.vibratuner.audio.coordinator.PlaybackNotificationState
import com.personal.vibratuner.receiver.PlaybackNotificationReceiver

/**
 * Единственное пользовательское уведомление воспроизведения.
 *
 * Содержимое приходит ГОТОВЫМ из единственного маппера
 * `PlaybackNotificationContent.from(...)` (`audio/coordinator`): здесь только
 * строки из `strings.xml` и Android-разметка. Никакой логики воспроизведения
 * нет: кнопки отправляют широковещательный интент в
 * [PlaybackNotificationReceiver], а тот передаёт действие живому сервису, где
 * команды уходят в координатор.
 *
 * Тап по уведомлению — ОТДЕЛЬНЫЙ PendingIntent.getActivity на [MainActivity]:
 * он поднимает уже существующую задачу приложения и НИКОГДА не останавливает
 * звук и не запускает сервис.
 *
 * Уведомление показывается как startForeground-уведомление сервиса (тот же ID),
 * поэтому второго уведомления воспроизведения не существует.
 */
object PlaybackNotification {

    /** ID единственного уведомления воспроизведения (и foreground-сервиса). */
    const val ID = 1001

    /** Кнопка «Остановить» (и смахивание уведомления). */
    const val ACTION_STOP_SOUND = "com.personal.vibratuner.action.NOTIFICATION_STOP_SOUND"

    /** Кнопка play/pause: идёт через координатор. */
    const val ACTION_TOGGLE_PLAY_PAUSE =
        "com.personal.vibratuner.action.NOTIFICATION_TOGGLE_PLAY_PAUSE"

    /** Смахивание уведомления (deleteIntent). */
    const val ACTION_DISMISSED = "com.personal.vibratuner.action.NOTIFICATION_DISMISSED"

    /** Вид звука, для которого было показано уведомление (защита от устаревших действий). */
    const val EXTRA_SOUND_KIND = "extra_notification_sound_kind"

    /** Название звука, для которого было показано уведомление. */
    const val EXTRA_SOUND_NAME = "extra_notification_sound_name"

    private const val REQUEST_TAP = 1101
    private const val REQUEST_ACTION_STOP = 1102
    private const val REQUEST_DISMISS = 1103
    private const val REQUEST_ACTION_PLAY_PAUSE = 1104

    /**
     * Уведомление о текущем звуке. Состояние обязано быть непустым: при
     * отсутствии содержимого уведомление не строится, а удаляется.
     */
    fun build(context: Context, state: PlaybackNotificationState): Notification {
        val builder = baseBuilder(context)
            .setContentTitle(title(context, state))
            .setContentText(text(context, state))
            .setContentIntent(contentIntent(context))
            .setDeleteIntent(
                soundActionPendingIntent(context, state, ACTION_DISMISSED, REQUEST_DISMISS)
            )

        // Прерывистая пауза: системный ОБРАТНЫЙ ОТСЧЁТ до момента возврата
        // музыки. Время считает сама система, поэтому приложение НЕ обновляет
        // уведомление ни разу за паузу (требование «не чаще раза в минуту»).
        val resumeAtMs = state.resumeAtMs
        if (state.pauseReason == NotificationPauseReason.CHOPPY && resumeAtMs != null) {
            builder
                .setShowWhen(true)
                .setWhen(resumeAtMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        // Порядок кнопок задаёт маппер; новых кнопок, кроме play/pause и
        // существующей «Остановить», здесь нет.
        state.buttons.forEach { button ->
            when (button) {
                NotificationButton.PLAY_PAUSE -> builder.addAction(
                    0,
                    context.getString(
                        if (state.isPlaying) {
                            R.string.notification_action_pause
                        } else {
                            R.string.notification_action_play
                        }
                    ),
                    soundActionPendingIntent(
                        context,
                        state,
                        ACTION_TOGGLE_PLAY_PAUSE,
                        REQUEST_ACTION_PLAY_PAUSE
                    )
                )
                NotificationButton.STOP -> builder.addAction(
                    0,
                    context.getString(R.string.notification_action_stop),
                    soundActionPendingIntent(
                        context,
                        state,
                        ACTION_STOP_SOUND,
                        REQUEST_ACTION_STOP
                    )
                )
            }
        }
        return builder.build()
    }

    /**
     * Короткоживущая заглушка для момента между `startForegroundService` и
     * фактическим звуком: Android требует startForeground сразу, а состояние
     * уведомления ещё null. Заменяется настоящим уведомлением или удаляется в том
     * же вызове `onStartCommand`, если звук не начался.
     */
    fun buildPlaceholder(context: Context): Notification {
        return baseBuilder(context)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
    }

    /** Подпись вида звука из strings.xml. */
    @StringRes
    fun kindLabelRes(kind: CurrentSoundKind): Int = when (kind) {
        CurrentSoundKind.MUSIC -> R.string.notification_kind_music
        CurrentSoundKind.MICROSOUND -> R.string.notification_kind_microsound
        CurrentSoundKind.SPEAKER_ON -> R.string.notification_kind_speaker_on
        CurrentSoundKind.FAKE_SPEAKER_ON -> R.string.notification_kind_fake_speaker_on
        CurrentSoundKind.ALARM -> R.string.notification_kind_alarm
    }

    /** Подпись состояния «играет»/«пауза». */
    @StringRes
    fun statusLabelRes(state: PlaybackNotificationState): Int =
        if (state.isPlaying) {
            R.string.notification_state_playing
        } else {
            R.string.notification_state_paused
        }

    /**
     * Подпись источника для подзаголовка: режим музыки, если он не обычный
     * («Случайный порядок» / «Повтор трека»), иначе вид звука. Имя исполнителя
     * НИКОГДА не выводится (правило части 1 фидбека).
     */
    @StringRes
    fun sourceLabelRes(state: PlaybackNotificationState): Int = when (state.playbackMode) {
        PlaybackMode.SHUFFLE -> R.string.notification_mode_shuffle
        PlaybackMode.REPEAT_ONE -> R.string.notification_mode_repeat_one
        else -> kindLabelRes(state.kind)
    }

    /**
     * Заголовок причины паузы: у прерывистой паузы он объясняет, ПОЧЕМУ тихо.
     * `null` — причина не требует отдельного заголовка (обычный трек/пауза).
     */
    @StringRes
    fun pauseReasonTitleRes(state: PlaybackNotificationState): Int? =
        when (state.pauseReason) {
            NotificationPauseReason.CHOPPY -> R.string.notification_choppy_pause_title
            else -> null
        }

    /**
     * Текст причины паузы вместо обычного подзаголовка «источник • состояние»:
     * сообщает, что музыка вернётся сама (остаток времени показывает системный
     * обратный отсчёт уведомления).
     */
    @StringRes
    fun pauseReasonTextRes(state: PlaybackNotificationState): Int? =
        when (state.pauseReason) {
            NotificationPauseReason.CHOPPY -> R.string.notification_choppy_pause_text
            else -> null
        }

    /**
     * Заголовок: у прерывистой паузы — причина, иначе название трека/файла, а
     * если его нет — подпись источника («Музыка», «Микрозвук», «Сеанс»).
     * Исполнитель НИКОГДА не выводится.
     */
    fun title(context: Context, state: PlaybackNotificationState): String {
        pauseReasonTitleRes(state)?.let { return context.getString(it) }
        return state.titleOrNull ?: context.getString(kindLabelRes(state.kind))
    }

    /**
     * Подзаголовок: у прерывистой паузы — объяснение причины, иначе
     * источник/режим и ЯВНОЕ состояние — «Музыка • Играет»,
     * «Случайный порядок • Пауза». Без названия повторять источник в двух
     * строках незачем, поэтому остаётся только состояние.
     */
    fun text(context: Context, state: PlaybackNotificationState): String {
        pauseReasonTextRes(state)?.let { return context.getString(it) }
        val status = context.getString(statusLabelRes(state))
        if (state.titleOrNull == null) return status
        return context.getString(
            R.string.notification_text_format,
            context.getString(sourceLabelRes(state)),
            status
        )
    }

    /**
     * Тап по уведомлению открывает приложение: PendingIntent.getActivity на
     * главную Activity с FLAG_IMMUTABLE и флагами, которые поднимают уже
     * существующую задачу вместо создания нового экземпляра.
     */
    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_TAP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Общие свойства единственного уведомления: тихое, не ongoing, без бейджа,
     * с акцентом из ресурсов. Цвет берётся только из Android color resource:
     * системному Notification нужен int, а не Compose Color.
     *
     * `ic_notification` — монохромный знак приложения (силуэт фигуры с иконки):
     * системная строка статуса рисует small icon только по альфа-каналу, поэтому
     * полноцветная иконка лаунчера здесь не используется.
     */
    private fun baseBuilder(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, VibraTunerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // Время по умолчанию скрыто; у прерывистой паузы build() включает
            // системный обратный отсчёт (setShowWhen(true) + chronometer).
            .setShowWhen(false)
            .setAutoCancel(false)
            .setOngoing(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    /**
     * PendingIntent на неэкспортированный приёмник. Явный компонент и
     * FLAG_IMMUTABLE: кнопки уведомления не открывают приложение и не запускают
     * сервис.
     *
     * `data` содержит вид звука, а extras — вид и название: это нужно, чтобы
     * кнопка «Остановить», отправленная для УЖЕ ЗАВЕРШИВШЕГОСЯ звука, была
     * распознана как устаревшая. Разные виды звука дают разные PendingIntent,
     * поэтому смена «микрозвук -> музыка» не переписывает extras старого
     * уведомления. Play/pause от extras не зависит: сервис решает по живому
     * состоянию координатора.
     */
    private fun soundActionPendingIntent(
        context: Context,
        state: PlaybackNotificationState,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, PlaybackNotificationReceiver::class.java).apply {
            this.action = action
            data = soundData(state)
            putExtra(EXTRA_SOUND_KIND, state.kind.name)
            putExtra(EXTRA_SOUND_NAME, state.titleOrNull)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Идентичность PendingIntent по виду звука (без названия — оно в extras). */
    private fun soundData(state: PlaybackNotificationState): Uri {
        return Uri.Builder()
            .scheme("vibratuner")
            .authority("playback")
            .appendPath(state.kind.name.lowercase())
            .build()
    }
}
