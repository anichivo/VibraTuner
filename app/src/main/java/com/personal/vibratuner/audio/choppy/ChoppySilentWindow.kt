package com.personal.vibratuner.audio.choppy

/**
 * Состояние ОКНА ТИШИНЫ прерывистой музыки (этап 4).
 *
 * Почему не пауза плеера: на Android 12+ приложение без активного звука теряет
 * foreground-состояние и попадает в замороженный кэш — таймер возобновления в
 * сервисе перестаёт тикать, и музыка не возвращается, пока процесс не разбудят
 * (открытие «Недавних»/экрана). Поэтому плеер НЕ ставится на паузу: он
 * продолжает играть, а программное усиление музыки на время окна умножается на
 * 0 (ducking-фактор 0 в существующей композиции громкости). Позиция на момент
 * старта запоминается, чтобы в конце окна вернуться к ней и не потерять
 * прозвучавшие в тишине секунды.
 *
 * Класс чистый: время передаётся снаружи ([SystemClock.elapsedRealtime] в
 * сервисе), поэтому логика окна проверяется JVM-тестами без Android runtime.
 * Системная громкость здесь НЕ меняется — только усиление плеера.
 */
class ChoppySilentWindow(
    private val safetyGraceMs: Long = SAFETY_GRACE_MS
) {
    /** true — окно тишины активно, усиление музыки обязано быть 0. */
    var isActive: Boolean = false
        private set

    /** Позиция (медиавремя, мс), с которой трек продолжится после окна. */
    var savedPositionMs: Long = 0L
        private set

    /** Момент (elapsedRealtime, мс), после которого окно считается просроченным. */
    var deadlineElapsedMs: Long = 0L
        private set

    /**
     * Открыть окно тишины. Позиция и длительность коэрцятся: отрицательные
     * значения не создают отрицательных задержек и не ломают таймеры.
     */
    fun start(positionMs: Long, durationMs: Long, nowElapsedMs: Long) {
        isActive = true
        savedPositionMs = positionMs.coerceAtLeast(0L)
        deadlineElapsedMs = nowElapsedMs.coerceAtLeast(0L) + durationMs.coerceAtLeast(0L)
    }

    /** Закрыть окно. Идемпотентно: вызывается из try/finally на всех путях. */
    fun end() {
        isActive = false
        savedPositionMs = 0L
        deadlineElapsedMs = 0L
    }

    /**
     * true — окно длится дольше своего срока + запаса. Это последняя страховка
     * «усиление никогда не остаётся нулевым»: даже если координатор/таймер
     * зависли, сервис принудительно вернёт звук.
     */
    fun isDeadlineExceeded(nowElapsedMs: Long): Boolean =
        isActive && nowElapsedMs >= deadlineElapsedMs + safetyGraceMs

    /** Программное усиление музыки с учётом окна: 0 в окне, иначе обычное. */
    fun applyGain(normalGain: Float): Float = if (isActive) 0f else normalGain

    companion object {
        /** Запас сверх длительности окна перед принудительным возвратом звука. */
        const val SAFETY_GRACE_MS: Long = 5_000L
    }
}
