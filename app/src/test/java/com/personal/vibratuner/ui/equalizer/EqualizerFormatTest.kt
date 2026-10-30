package com.personal.vibratuner.ui.equalizer

import com.personal.vibratuner.audio.eq.EqualizerSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подписи значений и проекция состояния экрана (чистые функции, без Android). */
class EqualizerFormatTest {

    private val ru = Locale.forLanguageTag("ru-RU")
    private val dbUnit = "дБ"

    /** Типичный диапазон полос Android: ±1500 мБ = ±15 дБ. */
    private val hardwareRange = -1500..1500

    @Test
    fun toneLabelShowsNeutralAndBoostInDecibels() {
        assertEquals("0 дБ", EqualizerFormat.tone(0, hardwareRange, dbUnit))
        assertEquals("+3 дБ", EqualizerFormat.tone(20, hardwareRange, dbUnit))
        assertEquals("+6 дБ", EqualizerFormat.tone(40, hardwareRange, dbUnit))
        assertEquals("+15 дБ", EqualizerFormat.tone(100, hardwareRange, dbUnit))
    }

    @Test
    fun toneLabelShowsCutInDecibels() {
        // Ровно то, что просил владелец: отрицательное значение видно как «-8 дБ».
        assertEquals("-8 дБ", EqualizerFormat.tone(-53, hardwareRange, dbUnit))
        assertEquals("-3 дБ", EqualizerFormat.tone(-20, hardwareRange, dbUnit))
        assertEquals("-15 дБ", EqualizerFormat.tone(-100, hardwareRange, dbUnit))
    }

    @Test
    fun toneLabelClampsToValidRange() {
        assertEquals("+15 дБ", EqualizerFormat.tone(150, hardwareRange, dbUnit))
        assertEquals("-15 дБ", EqualizerFormat.tone(-150, hardwareRange, dbUnit))
    }

    @Test
    fun toneLabelUsesActualHardwareRange() {
        // Телефон с несимметричным диапазоном: срез ограничен его минимумом.
        val range = -1000..1500
        assertEquals("+15 дБ", EqualizerFormat.tone(100, range, dbUnit))
        assertEquals("-10 дБ", EqualizerFormat.tone(-100, range, dbUnit))
        assertEquals("-5 дБ", EqualizerFormat.tone(-50, range, dbUnit))
    }

    @Test
    fun toneUnitComesFromResources() {
        assertEquals("0 dB", EqualizerFormat.tone(0, hardwareRange, "dB"))
    }

    @Test
    fun overboostLabelAlwaysHasPlusSign() {
        assertEquals("+0%", EqualizerFormat.overboost(0))
        assertEquals("+120%", EqualizerFormat.overboost(120))
        assertEquals("+200%", EqualizerFormat.overboost(200))
        assertEquals("+500%", EqualizerFormat.overboost(500))
        assertEquals("Это верхняя граница нового диапазона", "+500%", EqualizerFormat.overboost(900))
    }

    @Test
    fun speedLabelUsesDecimalCommaAndTrimsZeros() {
        assertEquals("x1", EqualizerFormat.speed(1.0f, ru))
        assertEquals("x1,5", EqualizerFormat.speed(1.5f, ru))
        assertEquals("x0,25", EqualizerFormat.speed(0.25f, ru))
        assertEquals("x4", EqualizerFormat.speed(4.0f, ru))
    }

    @Test
    fun speedLabelRoundsToTwoFractionDigits() {
        assertEquals("x1,41", EqualizerFormat.speed(1.4142f, ru))
    }

    @Test
    fun speedLabelIsLocaleAware() {
        assertEquals("x1.5", EqualizerFormat.speed(1.5f, Locale.US))
    }

    @Test
    fun speedLabelClampsAndReplacesNaN() {
        assertEquals("x0,25", EqualizerFormat.speed(0.1f, ru))
        assertEquals("x4", EqualizerFormat.speed(9.0f, ru))
        assertEquals("x1", EqualizerFormat.speed(Float.NaN, ru))
    }

    @Test
    fun screenStateSelectsExactPresetAndBuildsAllLabels() {
        val state = equalizerScreenState(
            EqualizerSettings(
                enabled = true,
                bassPercent = 70,
                treblePercent = -40,
                overboostPercent = 0,
                speed = 1.5f
            ),
            hardwareRange,
            dbUnit,
            ru
        )

        assertNull("Срез баса выводит из пресета «Басы»", state.selectedPreset)
        assertEquals("+11 дБ", state.bassLabel)
        assertEquals("-6 дБ", state.trebleLabel)
        assertEquals("+0%", state.overboostLabel)
        assertEquals("x1,5", state.speedLabel)
        assertTrue(state.controlsEnabled)
    }

    @Test
    fun screenStateMatchesPresetWithoutCut() {
        val state = equalizerScreenState(
            EqualizerSettings(enabled = true, bassPercent = 70),
            hardwareRange,
            dbUnit,
            ru
        )

        assertEquals(EqualizerPreset.BASS, state.selectedPreset)
        assertEquals("0 дБ", state.trebleLabel)
    }

    @Test
    fun screenStateWithoutExactMatchHasNoSelectedPreset() {
        val state = equalizerScreenState(
            EqualizerSettings(enabled = true, bassPercent = 70, treblePercent = 5),
            hardwareRange,
            dbUnit,
            ru
        )
        assertNull(state.selectedPreset)
    }

    @Test
    fun disabledEqualizerDisablesControlsButKeepsValuesVisible() {
        val state = equalizerScreenState(
            EqualizerSettings(
                enabled = false,
                bassPercent = 40,
                treblePercent = -25,
                overboostPercent = 120,
                speed = 0.5f
            ),
            hardwareRange,
            dbUnit,
            ru
        )

        assertFalse(state.controlsEnabled)
        assertEquals("+6 дБ", state.bassLabel)
        assertEquals("-4 дБ", state.trebleLabel)
        assertEquals("+120%", state.overboostLabel)
        assertEquals("x0,5", state.speedLabel)
    }
}
