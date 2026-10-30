package com.personal.vibratuner.audio.coordinator

import kotlin.random.Random

/**
 * Выбор следующего трека для режима SHUFFLE (этап 3).
 *
 * Чистая функция без Android и без состояния: координатор владеет режимом,
 * а сервис передаёт сюда РАЗМЕР текущего источника и индекс играющего трека.
 * Текущий трек исключается, если треков больше одного, — так выполняется
 * требование «без немедленного повтора». Индексы — позиции в очереди/списке,
 * который сервис реально проигрывает.
 */
object TrackQueueSelector {

    /**
     * Случайный индекс в `0 until size`, отличный от [currentIndex]
     * (когда треков больше одного).
     *
     * @return null — выбирать не из чего (пустой источник).
     */
    fun randomIndex(
        size: Int,
        currentIndex: Int,
        random: Random = Random.Default
    ): Int? {
        if (size <= 0) return null
        if (size == 1) return 0
        val excluded = currentIndex.takeIf { it in 0 until size }
            ?: return random.nextInt(size)
        // Равномерно выбираем из size - 1 остальных и «перескакиваем» исключённый.
        val candidate = random.nextInt(size - 1)
        return if (candidate >= excluded) candidate + 1 else candidate
    }
}
