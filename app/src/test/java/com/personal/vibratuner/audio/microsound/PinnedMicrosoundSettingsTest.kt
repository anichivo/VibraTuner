package com.personal.vibratuner.audio.microsound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фейк-включение колонки не имеет собственного звука и усиления: оно играет
 * ровно звук «Включения колонки» ([PinnedMicrosoundSettings.uri]) с его gain
 * и запускается только при включённом звуке, выбранном звуке и включённом фейке.
 * Неизвестный встроенный id не считается выбранным звуком и не играет.
 */
class PinnedMicrosoundSettingsTest {

    private val enabledWithFile = PinnedMicrosoundSettings(
        enabled = true,
        uri = "content://sounds/speaker-on.wav",
        fileName = "speaker-on.wav",
        gain = 0.6f,
        fakeEnabled = true
    )

    @Test
    fun fakeCanPlayOnlyWhenSoundEnabledSoundSelectedAndFakeEnabled() {
        assertTrue(enabledWithFile.fakeCanPlay)

        assertFalse(
            "Выключенный звук «Включение колонки» отключает и фейк",
            enabledWithFile.copy(enabled = false).fakeCanPlay
        )
        assertFalse(
            "Без выбранного звука фейк не играет",
            enabledWithFile.copy(uri = "").fakeCanPlay
        )
        assertFalse(
            "Выключенный фейк не играет",
            enabledWithFile.copy(fakeEnabled = false).fakeCanPlay
        )
    }

    @Test
    fun fakeValuesSurviveWhenSoundIsDisabled() {
        // При выключении звука интервалы и флаг фейка не теряются; отдельного
        // звука у фейка нет — он всегда использует звук «Включения колонки».
        val disabled = enabledWithFile.copy(enabled = false)
        assertTrue(disabled.fakeEnabled)
        assertEquals(enabledWithFile.fakeMinIntervalMinutes, disabled.fakeMinIntervalMinutes)
        assertEquals(enabledWithFile.fakeMaxIntervalMinutes, disabled.fakeMaxIntervalMinutes)
        assertEquals(enabledWithFile.uri, disabled.uri)
    }

    @Test
    fun fakeAlwaysUsesSpeakerOnSound() {
        assertEquals("content://sounds/speaker-on.wav", enabledWithFile.uri)
        assertTrue(enabledWithFile.hasSound)
        assertTrue(enabledWithFile.canPlay)
        assertTrue(enabledWithFile.fakeCanPlay)
    }

    @Test
    fun builtinSpeakerOnSoundIsValid() {
        val stored = BuiltinMicrosoundCatalog.defaultStoredUri()
        val settings = enabledWithFile.copy(uri = stored, fileName = "")

        assertTrue(settings.hasSound)
        assertTrue(settings.canPlay)
        assertTrue(settings.fakeCanPlay)
    }

    @Test
    fun unknownBuiltinIsNotASoundAndDisablesPlayback() {
        val settings = enabledWithFile.copy(uri = "builtin:removed_sound")

        assertFalse(settings.hasSound)
        assertFalse(settings.canPlay)
        assertFalse(
            "Неизвестный встроенный звук не должен играть и спамить лог",
            settings.fakeCanPlay
        )
    }

    @Test
    fun blankSoundIsNotPlayable() {
        val settings = enabledWithFile.copy(uri = "")

        assertFalse(settings.hasSound)
        assertFalse(settings.canPlay)
        assertFalse(settings.fakeCanPlay)
    }

    @Test
    fun windowsAreAbsentByDefaultSoBehaviorIsUnchanged() {
        // Stage 3: отсутствие ключей DataStore означает «весь день» — и у
        // «Включения колонки», и у фейка. Прежние сохранённые данные дают
        // ровно то же поведение, что до появления окон.
        assertNull(enabledWithFile.window)
        assertNull(enabledWithFile.fakeWindow)
        assertNull(PinnedMicrosoundSettings().window)
        assertNull(PinnedMicrosoundSettings().fakeWindow)
    }

    @Test
    fun windowsAreIndependentPerSound() {
        val settings = enabledWithFile.copy(
            window = MicrosoundWindow(9 * 60, 21 * 60),
            fakeWindow = MicrosoundWindow(22 * 60, 6 * 60)
        )
        assertEquals(MicrosoundWindow(540, 1260), settings.window)
        assertEquals(MicrosoundWindow(1320, 360), settings.fakeWindow)
        // Окна не влияют на возможность играть: вне окна срабатывание просто
        // пропускается с записью в лог.
        assertTrue(settings.canPlay)
        assertTrue(settings.fakeCanPlay)
    }
}
