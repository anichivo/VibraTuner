package com.personal.vibratuner.audio.coordinator

/**
 * Единственная точка принятия решений о конфликте реального «Включения колонки»
 * и его фейк-версии (чистые функции без побочных эффектов).
 *
 * Правила:
 * a) фейк сработал, пока идёт реальная последовательность (звук или пауза 10 с
 *    до музыки будильника), — фейк пропускается;
 * b) реальная последовательность пришла, пока звучит фейк, — реальная побеждает,
 *    фейк останавливается и сразу начинается реальный звук с начала.
 *
 * Решения используют только состояние координатора; сам координатор их исполняет.
 */
object SpeakerOnConflictPolicy {

    /** a) Входящий фейк нужно пропустить: сейчас идёт реальная последовательность. */
    fun fakeMustBeSkipped(currentState: CoordinatorState, incomingIsFake: Boolean): Boolean {
        if (!incomingIsFake) return false
        val active = currentState as? CoordinatorState.MicroSoundActive ?: return false
        return active.isPinnedAlarmPrelude
    }

    /** b) Сейчас играет фейк, пришла реальная последовательность — реальная важнее. */
    fun realPreemptsFake(currentState: CoordinatorState): Boolean {
        val active = currentState as? CoordinatorState.MicroSoundActive ?: return false
        return active.isFakePinnedMicrosound && !active.isPinnedAlarmPrelude
    }
}
