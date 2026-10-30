package com.personal.vibratuner.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerSettingsTest {

    @Test
    fun sanitize_returnsDefaults() {
        val s = EqualizerSettings.sanitize()
        assertEquals(false, s.enabled)
        assertEquals(0, s.bassPercent)
        assertEquals(0, s.treblePercent)
        assertEquals(0, s.overboostPercent)
        assertEquals(1.0f, s.speed, 0.001f)
        assertEquals(false, s.boostSystemVolume)
    }

    @Test
    fun sanitize_coercesPercentsAndAllowsSymmetricToneCut() {
        // Отрицательный бас/ВЧ — это СРЕЗ: он допустим до -100 %.
        val s = EqualizerSettings.sanitize(
            bassPercent = -10,
            treblePercent = -100,
            overboostPercent = -5
        )
        assertEquals(-10, s.bassPercent)
        assertEquals(-100, s.treblePercent)
        assertEquals(0, s.overboostPercent)

        val s2 = EqualizerSettings.sanitize(
            bassPercent = -101,
            treblePercent = 999,
            overboostPercent = 999
        )
        assertEquals(-100, s2.bassPercent)
        assertEquals(100, s2.treblePercent)
        assertEquals(500, s2.overboostPercent)
    }

    @Test
    fun sanitize_replacesNanSpeedAndClampsSpeed() {
        assertEquals(1.0f, EqualizerSettings.sanitize(speed = Float.NaN).speed, 0.001f)
        assertEquals(4.0f, EqualizerSettings.sanitize(speed = 99f).speed, 0.001f)
        assertEquals(0.25f, EqualizerSettings.sanitize(speed = -1f).speed, 0.001f)
    }

    @Test
    fun sanitize_existingInstance_coercesAllFields() {
        val raw = EqualizerSettings(
            enabled = true,
            bassPercent = -3,
            treblePercent = 250,
            overboostPercent = 999,
            speed = Float.NaN,
            boostSystemVolume = false
        )
        val s = EqualizerSettings.sanitize(raw)
        assertEquals(true, s.enabled)
        assertEquals(-3, s.bassPercent)
        assertEquals(100, s.treblePercent)
        assertEquals(500, s.overboostPercent)
        assertEquals(1.0f, s.speed, 0.001f)
        assertEquals(false, s.boostSystemVolume)
    }
}
