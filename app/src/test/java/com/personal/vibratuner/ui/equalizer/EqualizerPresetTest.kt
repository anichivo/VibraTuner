package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Чистое сопоставление пресетов и применение пресета (без Android). */
class EqualizerPresetTest {

    @Test
    fun everyPresetMatchesItsExactValues() {
        assertEquals(
            EqualizerPreset.NEUTRAL,
            EqualizerPreset.of(EqualizerSettings(bassPercent = 0, treblePercent = 0, overboostPercent = 0))
        )
        assertEquals(
            EqualizerPreset.BASS,
            EqualizerPreset.of(EqualizerSettings(bassPercent = 70, treblePercent = 0, overboostPercent = 0))
        )
        assertEquals(
            EqualizerPreset.TREBLE,
            EqualizerPreset.of(EqualizerSettings(bassPercent = 0, treblePercent = 70, overboostPercent = 0))
        )
        assertEquals(
            EqualizerPreset.LOUD,
            EqualizerPreset.of(EqualizerSettings(bassPercent = 0, treblePercent = 0, overboostPercent = 200))
        )
    }

    @Test
    fun oneValueOff_isNotSelected() {
        val almostBass = EqualizerSettings(bassPercent = 70, treblePercent = 1, overboostPercent = 0)
        assertNull(EqualizerPreset.of(almostBass))
        assertNull(
            EqualizerPreset.of(
                EqualizerSettings(bassPercent = 70, treblePercent = 0, overboostPercent = 10)
            )
        )
        assertNull(
            EqualizerPreset.of(
                EqualizerSettings(bassPercent = 69, treblePercent = 0, overboostPercent = 0)
            )
        )
    }

    @Test
    fun speedAndSwitchesDoNotAffectMatching() {
        val settings = EqualizerSettings(
            enabled = false,
            bassPercent = 70,
            treblePercent = 0,
            overboostPercent = 0,
            speed = 2.5f,
            boostSystemVolume = false
        )
        assertEquals(EqualizerPreset.BASS, EqualizerPreset.of(settings))
    }

    @Test
    fun applyPresetChangesOnlyToneValues() {
        val original = EqualizerSettings(
            enabled = true,
            bassPercent = 10,
            treblePercent = 20,
            overboostPercent = 30,
            speed = 3.0f,
            boostSystemVolume = false
        )
        val applied = EqualizerPreset.LOUD.applyTo(original)

        assertEquals(0, applied.bassPercent)
        assertEquals(0, applied.treblePercent)
        assertEquals(200, applied.overboostPercent)
        assertEquals(original.enabled, applied.enabled)
        assertEquals(3.0f, applied.speed, 0.0001f)
        assertEquals(false, applied.boostSystemVolume)
    }
}
