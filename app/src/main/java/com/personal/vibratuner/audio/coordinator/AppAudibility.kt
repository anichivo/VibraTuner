package com.personal.vibratuner.audio.coordinator

/**
 * Чистое правило «приложение реально слышно или уже запускает звук».
 *
 * Это НЕ `currentSound`: звук считается слышимым и тогда, когда плеер ещё
 * готовится к старту (намерение играть уже есть), и во время смены трека.
 * Тишина — покой, музыка на паузе, окно тишины прерывистой музыки и
 * 10-секундная пауза после «Включения колонки».
 *
 * Окно тишины (этап 4) намеренно не слышимо, хотя `currentSound` в нём остаётся
 * музыкой: плеер играет с усилением 0, поэтому поднимать системную громкость
 * (и удерживать её) незачем.
 *
 * Единственный потребитель — `SystemVolumeController` (часть 3), который
 * поднимает системную громкость только при `appIsAudible == true`.
 */
internal object AppAudibility {

    fun isAudible(state: CoordinatorState): Boolean = when (state) {
        is CoordinatorState.Idle -> false
        is CoordinatorState.MusicPlaying -> !state.isPaused
        is CoordinatorState.ChoppyPauseActive -> false
        is CoordinatorState.MicroSoundActive -> !state.isSpeakerOnSilence
        // Предпрослушивание трека никогда не поднимает системную громкость
        // (Stage 2): это прослушивание внутри окна, а не воспроизведение музыки.
        is CoordinatorState.Previewing -> false
    }
}
