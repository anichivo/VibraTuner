package com.personal.vibratuner.audio

import com.personal.vibratuner.audio.eq.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeCalculatorTest {

    // --- Прогресс нарастания громкости (Volume Ramp) ---

    @Test
    fun rampProgress_scalesLinearlyFromZeroToOne() {
        val durationMs = 10_000L

        assertEquals(0.0f, VolumeCalculator.calculateRampProgress(0L, durationMs), 0.001f)
        assertEquals(0.25f, VolumeCalculator.calculateRampProgress(2_500L, durationMs), 0.001f)
        assertEquals(0.50f, VolumeCalculator.calculateRampProgress(5_000L, durationMs), 0.001f)
        assertEquals(1.0f, VolumeCalculator.calculateRampProgress(10_000L, durationMs), 0.001f)
        assertEquals(1.0f, VolumeCalculator.calculateRampProgress(15_000L, durationMs), 0.001f)
    }

    @Test
    fun rampProgress_zeroOrNegativeDuration_returnsImmediateFullGain() {
        assertEquals(1.0f, VolumeCalculator.calculateRampProgress(0L, 0L), 0.001f)
        assertEquals(1.0f, VolumeCalculator.calculateRampProgress(100L, -500L), 0.001f)
    }

    // --- Выбор усиления по источнику запуска (предел музыки удалён) ---

    @Test
    fun alarmPlayback_usesSessionGain() {
        assertEquals(
            0.90f,
            VolumeCalculator.calculateTargetMusicGain(alarmGain = 0.90f, currentGain = 1.0f),
            0.001f
        )
    }

    @Test
    fun manualPlayback_usesCurrentGain() {
        assertEquals(
            1.0f,
            VolumeCalculator.calculateTargetMusicGain(alarmGain = null, currentGain = 1.0f),
            0.001f
        )
        assertEquals(
            0.30f,
            VolumeCalculator.calculateTargetMusicGain(alarmGain = null, currentGain = 0.30f),
            0.001f
        )
    }

    @Test
    fun manualPlayback_outOfRangeCurrentGain_isClamped() {
        assertEquals(
            0.0f,
            VolumeCalculator.calculateTargetMusicGain(alarmGain = null, currentGain = -0.2f),
            0.001f
        )
        assertEquals(
            1.0f,
            VolumeCalculator.calculateTargetMusicGain(alarmGain = null, currentGain = 1.5f),
            0.001f
        )
    }

    @Test
    fun alarmGain_isClampedToValidRange() {
        assertEquals(0.0f, VolumeCalculator.calculateAlarmMusicGain(-0.5f), 0.001f)
        assertEquals(1.0f, VolumeCalculator.calculateAlarmMusicGain(1.5f), 0.001f)
        assertEquals(0.80f, VolumeCalculator.calculateAlarmMusicGain(0.80f), 0.001f)
    }

    // --- Единая композиция громкости (часть 5) ---

    @Test
    fun effectiveVolumes_musicIsSessionVolumeTimesDucking() {
        val volumes = VolumeCalculator.effectiveVolumes(
            sessionVolume = 0.9f,
            duckingFactor = 0.5f
        )

        assertEquals(0.45f, volumes.music, 0.001f)
    }

    @Test
    fun effectiveVolumes_rampGoesFromZeroToSessionGain() {
        val alarmGain = 0.90f
        val rampDurationMs = 10_000L

        fun musicAt(elapsedMs: Long): Float = VolumeCalculator.effectiveVolumes(
            sessionVolume = alarmGain,
            duckingFactor = VolumeCalculator.calculateRampProgress(elapsedMs, rampDurationMs)
        ).music

        assertEquals(0.0f, musicAt(0L), 0.001f)
        assertEquals(0.45f, musicAt(5_000L), 0.001f)
        assertEquals(0.90f, musicAt(10_000L), 0.001f)
        assertEquals(0.90f, musicAt(30_000L), 0.001f)
    }

    @Test
    fun effectiveVolumes_microsoundIsRelativeToSessionVolumeAndOwnGain() {
        val volumes = VolumeCalculator.effectiveVolumes(
            sessionVolume = 0.5f,
            microsoundVolume = 0.8f
        )

        assertEquals(0.5f, volumes.music, 0.001f)
        assertEquals(0.4f, volumes.microsound, 0.001f)
    }

    @Test
    fun effectiveVolumes_withoutSession_microsoundKeepsOwnGain() {
        val volumes = VolumeCalculator.effectiveVolumes(
            sessionVolume = VolumeCalculator.NEUTRAL_GAIN,
            microsoundVolume = 0.8f
        )

        assertEquals(1.0f, volumes.music, 0.001f)
        assertEquals(0.8f, volumes.microsound, 0.001f)
    }

    @Test
    fun effectiveVolumes_neverAmplifyAboveOne() {
        val volumes = VolumeCalculator.effectiveVolumes(
            sessionVolume = 2.5f,
            microsoundVolume = 3.0f,
            duckingFactor = 2.0f
        )

        assertEquals(1.0f, volumes.music, 0.001f)
        assertEquals(1.0f, volumes.microsound, 0.001f)
    }

    @Test
    fun effectiveVolumes_negativeAndNaNAreSanitized() {
        assertEquals(0.0f, VolumeCalculator.sanitizeGain(-0.3f), 0.001f)
        assertEquals(1.0f, VolumeCalculator.sanitizeGain(Float.NaN), 0.001f)
        assertEquals(0.75f, VolumeCalculator.sanitizeGain(0.75f), 0.001f)
    }

    @Test
    fun effectiveVolumes_doNotDependOnEqualizerSettings() {
        val off = VolumeCalculator.effectiveVolumes(
            sessionVolume = 0.8f,
            microsoundVolume = 0.8f,
            settings = EqualizerSettings.DEFAULT
        )
        val on = VolumeCalculator.effectiveVolumes(
            sessionVolume = 0.8f,
            microsoundVolume = 0.8f,
            settings = EqualizerSettings(
                enabled = true,
                bassPercent = 100,
                treblePercent = 100,
                overboostPercent = 200,
                speed = 4.0f
            )
        )

        assertEquals("Эквалайзер ON не меняет громкость музыки", off.music, on.music, 0.0001f)
        assertEquals(
            "Эквалайзер ON не меняет громкость микрозвука",
            off.microsound,
            on.microsound,
            0.0001f
        )
    }
}
