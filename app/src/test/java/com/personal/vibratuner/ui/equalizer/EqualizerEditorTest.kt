package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.data.local.datastore.EqualizerSettingsStore
import com.personal.vibratuner.data.repository.EqualizerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика экрана без Android: отложенная conflated-запись во время протяжки,
 * немедленная запись в конце жеста, сброс, пресеты и переключатели.
 * Используется фейковое хранилище и виртуальное время, Robolectric не нужен.
 */
class EqualizerEditorTest {

    private class RecordingStore(
        initial: EqualizerSettings = EqualizerSettings()
    ) : EqualizerSettingsStore {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<EqualizerSettings>()

        override val settings: Flow<EqualizerSettings> = state

        override suspend fun save(settings: EqualizerSettings) {
            val sanitized = EqualizerSettings.sanitize(settings)
            saved += sanitized
            state.value = sanitized
        }
    }

    private fun TestScope.newEditor(store: RecordingStore): EqualizerEditor =
        EqualizerEditor(
            repository = EqualizerRepository(store),
            scope = backgroundScope,
            debounceMillis = 100L
        )

    @Test
    fun initialSettingsComeFromRepository() = runTest {
        val store = RecordingStore(
            EqualizerSettings(enabled = true, bassPercent = 40, speed = 1.5f)
        )
        val editor = newEditor(store)
        testScheduler.runCurrent()

        assertEquals(store.state.value, editor.settings.value)
    }

    @Test
    fun dragging_writesOnlyLastValueAfterShortDebounce() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.BASS, 10f)
        editor.onSliderChanged(EqualizerControl.BASS, 55f)
        editor.onSliderChanged(EqualizerControl.BASS, 80f)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(99)
        testScheduler.runCurrent()
        assertTrue("До истечения debounce записи нет", store.saved.isEmpty())

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, store.saved.size)
        assertEquals(80, store.saved.single().bassPercent)
    }

    @Test
    fun continuousDrag_writesPeriodicallySoSoundChangesLive() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.BASS, 10f)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(listOf(10), store.saved.map { it.bassPercent })

        // Палец продолжает двигаться: следующий интервал снова даёт запись,
        // а не «молчание до конца жеста».
        editor.onSliderChanged(EqualizerControl.BASS, 60f)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(listOf(10, 60), store.saved.map { it.bassPercent })
    }

    @Test
    fun finish_flushesLastValueImmediatelyAndCancelsTimer() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.OVERBOOST, 120f)
        editor.onSliderChangeFinished()
        testScheduler.runCurrent()

        assertEquals(1, store.saved.size)
        assertEquals(120, store.saved.single().overboostPercent)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals("Отложенная запись отменена, дубля нет", 1, store.saved.size)
    }

    @Test
    fun sliderValueIsShownImmediatelyBeforePersisting() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.SPEED, 2f)

        assertEquals(2f, editor.settings.value.speed, 0.0001f)
        assertEquals("Репозиторий ещё не тронут", 1f, store.state.value.speed, 0.0001f)
    }

    @Test
    fun resetWritesDefaultAndCancelsPendingDrag() = runTest {
        val store = RecordingStore(EqualizerSettings(bassPercent = 40))
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.BASS, 80f)
        editor.reset(EqualizerControl.BASS)
        assertEquals(0, editor.settings.value.bassPercent)

        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertEquals(listOf(0), store.saved.map { it.bassPercent })
        assertEquals(0, store.state.value.bassPercent)
    }

    @Test
    fun resetSpeedReturnsToDefaultOneX() = runTest {
        val store = RecordingStore(EqualizerSettings(speed = 3f))
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.reset(EqualizerControl.SPEED)
        testScheduler.runCurrent()

        assertEquals(1f, editor.settings.value.speed, 0.0001f)
        assertEquals(1f, store.state.value.speed, 0.0001f)
    }

    @Test
    fun presetChangesOnlyToneValuesAndLeavesSpeedAndSwitches() = runTest {
        val store = RecordingStore(
            EqualizerSettings(
                enabled = true,
                speed = 2.5f,
                boostSystemVolume = false
            )
        )
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.selectPreset(EqualizerPreset.BASS)
        assertEquals("Значения видны сразу", 70, editor.settings.value.bassPercent)

        testScheduler.runCurrent()

        val saved = store.state.value
        assertEquals(70, saved.bassPercent)
        assertEquals(0, saved.treblePercent)
        assertEquals(0, saved.overboostPercent)
        assertEquals(2.5f, saved.speed, 0.0001f)
        assertEquals(false, saved.boostSystemVolume)
        assertEquals(true, saved.enabled)
        assertEquals(EqualizerPreset.BASS, EqualizerPreset.of(editor.settings.value))
    }

    @Test
    fun togglesArePersistedImmediately() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.setEnabled(true)
        assertTrue(editor.settings.value.enabled)
        testScheduler.runCurrent()
        assertEquals(true, store.state.value.enabled)

        editor.setBoostSystemVolume(false)
        testScheduler.runCurrent()
        assertEquals(false, store.state.value.boostSystemVolume)
    }

    @Test
    fun toggleDuringDragDoesNotLoseTheDraggedValue() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.BASS, 25f)
        editor.setEnabled(true)

        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        assertEquals(true, store.state.value.enabled)
        assertEquals(25, store.state.value.bassPercent)
    }

    @Test
    fun repositoryConfirmationClearsLocalDeltaWithoutChangingDisplay() = runTest {
        val store = RecordingStore()
        val editor = newEditor(store)
        testScheduler.runCurrent()

        editor.onSliderChanged(EqualizerControl.TREBLE, 55f)
        editor.onSliderChangeFinished()
        testScheduler.runCurrent()

        assertEquals(55, editor.settings.value.treblePercent)
        assertEquals(55, store.state.value.treblePercent)

        // Внешнее (не наше) изменение в репозитории обязано отразиться на экране.
        store.state.value = EqualizerSettings(bassPercent = 30)
        testScheduler.runCurrent()

        assertEquals(30, editor.settings.value.bassPercent)
        assertEquals(0, editor.settings.value.treblePercent)
    }
}
