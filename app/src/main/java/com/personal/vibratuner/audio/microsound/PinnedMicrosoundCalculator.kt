package com.personal.vibratuner.audio.microsound

/**
 * Чистый расчёт времени старта последовательности «Включение колонки».
 *
 * Последовательность обязана закончиться музыкой будильника в T:
 *   sequenceStart = T − (РЕАЛЬНОЕ время звучания + 10 секунд тишины).
 * Длительность звука приходит уже переведённой из медиавремени в реальное
 * (`media / speed`, см. `PlaybackTiming`), потому что эквалайзер может менять
 * скорость воспроизведения.
 * Если sequenceStart уже в прошлом, но T ещё в будущем, последовательность
 * запускается сразу (сейчас), а музыка будильника — после полной последовательности.
 * Если T уже наступил или длительность звука неизвестна, предзвук не планируется.
 */
object PinnedMicrosoundCalculator {

    /** Фиксированная пауза после окончания звука «Включение колонки». */
    const val SPEAKER_ON_POST_PAUSE_MS = 10_000L

    /**
     * Максимальная тишина до будильника, которую ещё имеет смысл держать после
     * паузы 10 с. Если звук оказался короче метаданных (или время сдвинули часы),
     * до T может остаться намного больше: держать приоритет MICROSOUND часами
     * нельзя — последовательность завершается, а музыку запустит сам будильник в T.
     */
    const val MAX_SILENCE_HOLD_MS = 60_000L

    /**
     * Нужно ли ждать будильник в тишине после 10-секундной паузы.
     * true — ждём T в тишине (тишина короткая, штатный случай);
     * false — последовательность завершается немедленно, музыка — по будильнику в T.
     */
    fun shouldHoldSilenceUntilAlarm(remainingUntilAlarmMs: Long): Boolean {
        return remainingUntilAlarmMs in 1..MAX_SILENCE_HOLD_MS
    }

    /**
     * Момент старта последовательности или null, если её планировать нельзя.
     *
     * @param alarmTriggerMs время будильника T (начало музыки)
     * @param speakerOnDurationMs фактическое РЕАЛЬНОЕ время звучания звука
     *   «Включение колонки» (медиадлительность уже разделена на скорость)
     * @param nowMs текущее время
     */
    fun calculateSequenceStartMillis(
        alarmTriggerMs: Long,
        speakerOnDurationMs: Long,
        nowMs: Long
    ): Long? {
        if (alarmTriggerMs <= nowMs) return null
        if (speakerOnDurationMs <= 0L) return null

        val leadMs = if (speakerOnDurationMs > Long.MAX_VALUE - SPEAKER_ON_POST_PAUSE_MS) {
            Long.MAX_VALUE
        } else {
            speakerOnDurationMs + SPEAKER_ON_POST_PAUSE_MS
        }
        val naturalStart = if (leadMs > alarmTriggerMs) {
            Long.MIN_VALUE
        } else {
            alarmTriggerMs - leadMs
        }
        // Старт в прошлом — не пропускаем предзвук, а запускаем его немедленно.
        return naturalStart.coerceAtLeast(nowMs)
    }
}
