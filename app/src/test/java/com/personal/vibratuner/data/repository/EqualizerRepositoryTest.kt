package com.personal.vibratuner.data.repository

import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.data.local.datastore.EqualizerSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerRepositoryTest {

    private class FakeEqualizerSettingsStore(
        initial: EqualizerSettings = EqualizerSettings()
    ) : EqualizerSettingsStore {
        val state = MutableStateFlow(initial)
        override val settings: Flow<EqualizerSettings> = state
        override suspend fun save(settings: EqualizerSettings) {
            state.value = settings
        }
    }

    @Test
    fun setters_persistAndReadBack() = runTest {
        val repo = EqualizerRepository(FakeEqualizerSettingsStore())

        repo.setEnabled(true)
        repo.setBassPercent(40)
        repo.setTreblePercent(60)
        repo.setOverboostPercent(120)
        repo.setSpeed(2.0f)
        repo.setBoostSystemVolume(false)

        val s = repo.settings.first()
        assertEquals(true, s.enabled)
        assertEquals(40, s.bassPercent)
        assertEquals(60, s.treblePercent)
        assertEquals(120, s.overboostPercent)
        assertEquals(2.0f, s.speed, 0.001f)
        assertEquals(false, s.boostSystemVolume)
    }

    @Test
    fun write_sanitizesOutOfRangeValues() = runTest {
        val repo = EqualizerRepository(FakeEqualizerSettingsStore())

        repo.setBassPercent(-10)
        repo.setTreblePercent(999)
        repo.setOverboostPercent(999)
        repo.setSpeed(Float.NaN)
        repo.setSpeed(99f)

        val s = repo.settings.first()
        assertEquals("Отрицательный бас — законный срез", -10, s.bassPercent)
        assertEquals(100, s.treblePercent)
        assertEquals(500, s.overboostPercent)
        assertEquals(4.0f, s.speed, 0.001f)
    }

    @Test
    fun read_sanitizesSeededInvalidValues() = runTest {
        val store = FakeEqualizerSettingsStore(
            EqualizerSettings(
                bassPercent = -5,
                treblePercent = 500,
                overboostPercent = 999,
                speed = Float.NaN
            )
        )
        val repo = EqualizerRepository(store)

        val s = repo.settings.first()
        assertEquals(-5, s.bassPercent)
        assertEquals(100, s.treblePercent)
        assertEquals(500, s.overboostPercent)
        assertEquals(1.0f, s.speed, 0.001f)
    }

    @Test
    fun defaults_areEqualizerOff() = runTest {
        val repo = EqualizerRepository(FakeEqualizerSettingsStore())

        val s = repo.settings.first()
        assertEquals(false, s.enabled)
        assertEquals(0, s.bassPercent)
        assertEquals(0, s.treblePercent)
        assertEquals(0, s.overboostPercent)
        assertEquals(1.0f, s.speed, 0.001f)
        assertEquals(false, s.boostSystemVolume)
    }
}
