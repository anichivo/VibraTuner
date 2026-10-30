package com.personal.vibratuner.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты [EqualizerEngine] с фейковыми фабрикой эффектов и плеером.
 * Без Robolectric: движок работает только через тонкие интерфейсы.
 */
class EqualizerEngineTest {

    // --- Фейки ---

    private class FakeAudioEffectsFactory : AudioEffectsFactory {
        var failEqualizer = false
        var failLoudness = false
        var equalizerThrowsOnSetBandLevel = false
        var bandRange: IntRange = -1500..1500

        var liveEffectCount = 0
            private set

        val equalizers = mutableListOf<FakeEqualizerEffect>()
        val loudnessEnhancers = mutableListOf<FakeLoudnessEnhancerEffect>()

        override fun createEqualizer(audioSessionId: Int): EqualizerEffect {
            if (failEqualizer) throw RuntimeException("equalizer unavailable")
            return FakeEqualizerEffect(this).also { equalizers.add(it); liveEffectCount++ }
        }

        override fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancerEffect {
            if (failLoudness) throw RuntimeException("loudness unavailable")
            return FakeLoudnessEnhancerEffect(this).also { loudnessEnhancers.add(it); liveEffectCount++ }
        }

        fun onEffectReleased() {
            liveEffectCount--
        }
    }

    private class FakeEqualizerEffect(
        private val factory: FakeAudioEffectsFactory
    ) : EqualizerEffect {
        override val numberOfBands: Int = 5
        var enabled: Boolean? = null
        var released = 0
            private set
        val bandLevels = mutableMapOf<Int, Int>()

        override fun centerFreqMilliHz(band: Int): Int = when (band) {
            0 -> 60_000
            1 -> 230_000
            2 -> 910_000
            3 -> 3_600_000
            4 -> 14_000_000
            else -> 0
        }

        override fun bandLevelRangeMillibels(): IntRange = factory.bandRange

        override fun setBandLevel(band: Int, levelMillibels: Int) {
            if (factory.equalizerThrowsOnSetBandLevel) throw RuntimeException("band apply failed")
            bandLevels[band] = levelMillibels
        }

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun release() {
            released++
            factory.onEffectReleased()
        }
    }

    private class FakeLoudnessEnhancerEffect(
        private val factory: FakeAudioEffectsFactory
    ) : LoudnessEnhancerEffect {
        var targetGain: Int? = null
        var enabled: Boolean? = null
        var released = 0
            private set

        override fun setTargetGain(targetGainMillibels: Int) {
            targetGain = targetGainMillibels
        }

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun release() {
            released++
            factory.onEffectReleased()
        }
    }

    private class FakeEqualizerPlayer : EqualizerPlayer {
        override var audioSessionId: Int = 0
        var lastSpeed: Float? = null
        var lastPitch: Float? = null
        private val listeners = mutableListOf<AudioSessionIdListener>()

        override fun setPlaybackParameters(speed: Float, pitch: Float) {
            lastSpeed = speed
            lastPitch = pitch
        }

        override fun addAudioSessionIdListener(listener: AudioSessionIdListener) {
            listeners.add(listener)
        }

        override fun removeAudioSessionIdListener(listener: AudioSessionIdListener) {
            listeners.remove(listener)
        }

        fun setSessionId(id: Int) {
            audioSessionId = id
            listeners.toList().forEach { it.onAudioSessionIdChanged(id) }
        }

        val listenerCount: Int get() = listeners.size
    }

    private fun engine(factory: FakeAudioEffectsFactory, log: (String) -> Unit = {}): EqualizerEngine =
        EqualizerEngine(factory, log)

    // --- Тесты ---

    @Test
    fun attach_appliesCurrentSettings_whenSessionAvailable() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        e.apply(
            EqualizerSettings(
                enabled = true,
                bassPercent = 40,
                treblePercent = 60,
                overboostPercent = 120,
                speed = 2.0f
            )
        )
        val player = FakeEqualizerPlayer().apply { audioSessionId = 42 }

        e.attach(player)

        assertEquals(2.0f, player.lastSpeed ?: 0f, 0.001f)
        assertEquals(2.0f, player.lastPitch ?: 0f, 0.001f)
        assertEquals(1, factory.equalizers.size)
        assertEquals(1, factory.loudnessEnhancers.size)
        assertTrue(factory.equalizers[0].enabled ?: false)
        assertEquals(5, factory.equalizers[0].bandLevels.size)
        assertEquals("40 % баса на 60 Гц", 600, factory.equalizers[0].bandLevels[0] ?: -1)
        assertEquals("60 % ВЧ на 14 кГц", 900, factory.equalizers[0].bandLevels[4] ?: -1)
        assertEquals(
            EqualizerMath.overboostGainMillibels(120),
            factory.loudnessEnhancers[0].targetGain ?: -1
        )
        assertTrue(factory.loudnessEnhancers[0].enabled ?: false)
    }

    @Test
    fun apply_updatesLivePlayer_withoutRecreatingEffects() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 42 }
        e.attach(player)
        e.apply(EqualizerSettings(enabled = true, bassPercent = 10))
        val eqBefore = factory.equalizers[0]

        e.apply(EqualizerSettings(enabled = true, bassPercent = 90, overboostPercent = 50))

        assertEquals(1, factory.equalizers.size)
        assertSame(eqBefore, factory.equalizers[0])
        assertEquals("90 % баса на 60 Гц", 1350, factory.equalizers[0].bandLevels[0] ?: -1)
        assertEquals(
            EqualizerMath.overboostGainMillibels(50),
            factory.loudnessEnhancers[0].targetGain ?: -1
        )
    }

    @Test
    fun toggleOff_revertsEffectsAndSpeed_toggleOn_reappliesStoredValues() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 7 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 60, speed = 1.8f))
        e.attach(player)
        assertEquals(1, factory.equalizers.size)

        e.apply(EqualizerSettings(enabled = false, bassPercent = 60, speed = 1.8f))
        assertEquals(1.0f, player.lastSpeed ?: 0f, 0.001f)
        assertEquals(1.0f, player.lastPitch ?: 0f, 0.001f)
        assertEquals(1, factory.equalizers[0].released)
        assertEquals(0, factory.liveEffectCount)

        e.apply(EqualizerSettings(enabled = true, bassPercent = 60, speed = 1.8f))
        assertEquals(1.8f, player.lastSpeed ?: 0f, 0.001f)
        assertEquals(2, factory.equalizers.size)
        assertEquals(900, factory.equalizers[1].bandLevels[0] ?: -1)
    }

    @Test
    fun audioSessionIdChange_recreatesEffects() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer() // session 0
        e.apply(EqualizerSettings(enabled = true, bassPercent = 20))
        e.attach(player)
        assertEquals(0, factory.equalizers.size)

        player.setSessionId(11)
        assertEquals(1, factory.equalizers.size)
        val first = factory.equalizers[0]

        player.setSessionId(12)
        assertEquals(2, factory.equalizers.size)
        assertEquals(1, first.released)
    }

    @Test
    fun unsetSession_postponesEffectCreation() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer() // session 0
        e.apply(EqualizerSettings(enabled = true, speed = 2.5f))
        e.attach(player)

        assertEquals(2.5f, player.lastSpeed ?: 0f, 0.001f)
        assertEquals(0, factory.liveEffectCount)

        player.setSessionId(5)
        assertEquals(2, factory.liveEffectCount)
    }

    @Test
    fun eachEffectFailsIndependently_othersStillWork() {
        val factory = FakeAudioEffectsFactory().apply { failEqualizer = true }
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 30, overboostPercent = 80))

        e.attach(player)

        assertEquals(0, factory.equalizers.size)
        assertEquals(1, factory.loudnessEnhancers.size)
        assertTrue((factory.loudnessEnhancers[0].targetGain ?: 0) > 0)
    }

    @Test
    fun exceptionsNeverPropagate_outOfEngine() {
        val factory = FakeAudioEffectsFactory().apply {
            failEqualizer = true
            failLoudness = true
        }
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 30))

        e.attach(player) // не бросает
        e.apply(EqualizerSettings(enabled = true, bassPercent = 40)) // не бросает

        assertEquals(0, factory.liveEffectCount)
    }

    @Test
    fun effectApplyFailure_doesNotPropagate_otherEffectsStillConfigured() {
        val factory = FakeAudioEffectsFactory().apply { equalizerThrowsOnSetBandLevel = true }
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 50, overboostPercent = 100))

        e.attach(player)

        assertTrue((factory.loudnessEnhancers[0].targetGain ?: 0) > 0)
    }

    @Test
    fun detach_releasesExactlyOnce() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 10, overboostPercent = 10))
        e.attach(player)
        assertEquals(2, factory.liveEffectCount)

        e.detach(player)

        assertEquals(0, factory.liveEffectCount)
        assertEquals(1, factory.equalizers[0].released)
        assertEquals(1, factory.loudnessEnhancers[0].released)
    }

    @Test
    fun doubleDetach_isSafe() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 10))
        e.attach(player)

        e.detach(player)
        e.detach(player) // no-op

        assertEquals(1, factory.equalizers[0].released)
    }

    @Test
    fun neutralControls_disableTheirEffect() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(
            EqualizerSettings(
                enabled = true,
                bassPercent = 0,
                treblePercent = 0,
                overboostPercent = 0,
                speed = 1.5f
            )
        )

        e.attach(player)

        assertEquals(1.5f, player.lastSpeed ?: 0f, 0.001f)
        assertFalse(factory.equalizers[0].enabled ?: true)
        assertFalse(factory.loudnessEnhancers[0].enabled ?: true)
        assertEquals(0, factory.equalizers[0].bandLevels.size)
    }

    @Test
    fun speed_appliesWithinSpecRange() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }

        e.apply(EqualizerSettings(enabled = true, speed = 0.25f))
        e.attach(player)
        assertEquals(0.25f, player.lastSpeed ?: 0f, 0.001f)

        e.apply(EqualizerSettings(enabled = true, speed = 4.0f))
        assertEquals(4.0f, player.lastSpeed ?: 0f, 0.001f)
        assertEquals(4.0f, player.lastPitch ?: 0f, 0.001f)
    }

    @Test
    fun speed_isClampedToSpecRange() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }

        e.apply(EqualizerSettings(enabled = true, speed = 99f))
        e.attach(player)

        assertEquals(4.0f, player.lastSpeed ?: 0f, 0.001f)
    }

    @Test
    fun manyShortLivedAttachDetach_leaveZeroLiveEffects() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        e.apply(EqualizerSettings(enabled = true, bassPercent = 30, overboostPercent = 30))

        repeat(100) {
            val player = FakeEqualizerPlayer().apply { audioSessionId = 9 }
            e.attach(player)
            e.detach(player)
        }

        assertEquals(0, factory.liveEffectCount)
    }

    @Test
    fun negativeBassAndTrebleCutBands() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = -100, treblePercent = -100))

        e.attach(player)

        val levels = factory.equalizers[0].bandLevels
        assertEquals("низ срезан до аппаратного минимума", -1500, levels[0] ?: 0)
        assertEquals("верх срезан до аппаратного минимума", -1500, levels[4] ?: 0)
        assertTrue("эквалайзер включён и для среза", factory.equalizers[0].enabled ?: false)
    }

    @Test
    fun cutIsLimitedByActualHardwareRangeAndPublished() {
        val factory = FakeAudioEffectsFactory().apply { bandRange = -900..1200 }
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = -100))

        e.attach(player)

        assertEquals(
            "Срез не выходит за минимум устройства",
            -900,
            factory.equalizers[0].bandLevels[0] ?: 0
        )
        assertEquals(
            "Диапазон устройства опубликован для подписей экрана",
            -900..1200,
            EqualizerBandRange.millibels.value
        )
    }

    @Test
    fun attachTwice_isIdempotent() {
        val factory = FakeAudioEffectsFactory()
        val e = engine(factory)
        val player = FakeEqualizerPlayer().apply { audioSessionId = 3 }
        e.apply(EqualizerSettings(enabled = true, bassPercent = 10))

        e.attach(player)
        e.attach(player)

        assertEquals(1, factory.equalizers.size)
        assertEquals(1, player.listenerCount)
    }
}
