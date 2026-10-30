package com.personal.vibratuner.audio.integration

import com.personal.vibratuner.audio.VolumeCalculator
import com.personal.vibratuner.audio.coordinator.CoordinatorEvent
import com.personal.vibratuner.audio.coordinator.CoordinatorState
import com.personal.vibratuner.audio.coordinator.CurrentSoundKind
import com.personal.vibratuner.audio.coordinator.PlaybackCoordinator
import com.personal.vibratuner.audio.eq.AudioEffectsFactory
import com.personal.vibratuner.audio.eq.AudioSessionIdListener
import com.personal.vibratuner.audio.eq.EqualizerEffect
import com.personal.vibratuner.audio.eq.EqualizerEngine
import com.personal.vibratuner.audio.eq.EqualizerPlayer
import com.personal.vibratuner.audio.eq.EqualizerSettings
import com.personal.vibratuner.audio.eq.LoudnessEnhancerEffect
import com.personal.vibratuner.audio.volume.SystemVolumeController
import com.personal.vibratuner.audio.volume.TimeSource
import com.personal.vibratuner.audio.volume.VolumeBackend
import com.personal.vibratuner.audio.volume.VolumeBackupEntry
import com.personal.vibratuner.audio.volume.VolumeBackupStore
import com.personal.vibratuner.data.local.datastore.EqualizerSettingsStore
import com.personal.vibratuner.data.local.db.TrackEntity
import com.personal.vibratuner.data.repository.EqualizerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Интеграционные JVM-тесты части 5: НАСТОЯЩИЕ `EqualizerEngine`,
 * `SystemVolumeController`, `EqualizerRepository` и `PlaybackCoordinator`
 * соединены так же, как в сервисе (настройки + слышимость → эффекты,
 * системная громкость, композиция громкости), но с фейковыми
 * Android-границами и виртуальным временем.
 *
 * Проверяются сбои эффектов/громкости, освобождение плеера во время apply,
 * 50 быстрых переключений ON/OFF, приоритет микрозвука во время прерывистой
 * паузы, немедленный возврат громкости при уничтожении сервиса и восстановление
 * после смерти процесса. Robolectric и новые библиотеки не используются.
 */
class EqualizerIntegrationTest {

    // ------------------------------------------------------------------
    // Фейковые Android-границы
    // ------------------------------------------------------------------

    private class FakeEffectsFactory : AudioEffectsFactory {
        var failAll = false

        var liveEffects = 0
            private set
        var createdEffects = 0
            private set

        override fun createEqualizer(audioSessionId: Int): EqualizerEffect {
            if (failAll) throw RuntimeException("equalizer unavailable")
            createdEffects++
            liveEffects++
            return FakeEqualizerEffect()
        }

        override fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancerEffect {
            if (failAll) throw RuntimeException("loudness unavailable")
            createdEffects++
            liveEffects++
            return FakeLoudnessEnhancerEffect()
        }

        private inner class FakeEqualizerEffect : EqualizerEffect {
            override val numberOfBands: Int = 5
            override fun centerFreqMilliHz(band: Int): Int = 60_000 * (band + 1)
            override fun bandLevelRangeMillibels(): IntRange = -1500..1500
            override fun setBandLevel(band: Int, levelMillibels: Int) = Unit
            override fun setEnabled(enabled: Boolean) = Unit
            override fun release() {
                liveEffects--
            }
        }

        private inner class FakeLoudnessEnhancerEffect : LoudnessEnhancerEffect {
            override fun setTargetGain(targetGainMillibels: Int) = Unit
            override fun setEnabled(enabled: Boolean) = Unit
            override fun release() {
                liveEffects--
            }
        }
    }

    /**
     * Плеер с фиксированным sessionId и программной громкостью.
     * [onSpeed] позволяет смоделировать освобождение плеера прямо во время apply().
     */
    private class FakeEqualizerPlayer(
        override var audioSessionId: Int = 42,
        private val label: String = "player"
    ) : EqualizerPlayer {
        var speed: Float = 1.0f
            private set
        var volume: Float = 1.0f
        var speedCalls: Int = 0
            private set
        var isReleased: Boolean = false
            private set
        var onSpeed: ((FakeEqualizerPlayer) -> Unit)? = null

        private val listeners = mutableListOf<AudioSessionIdListener>()

        override fun setPlaybackParameters(speed: Float, pitch: Float) {
            check(!isReleased) { "$label released" }
            this.speed = speed
            speedCalls++
            onSpeed?.invoke(this)
        }

        override fun addAudioSessionIdListener(listener: AudioSessionIdListener) {
            listeners.add(listener)
        }

        override fun removeAudioSessionIdListener(listener: AudioSessionIdListener) {
            listeners.remove(listener)
        }

        fun markReleased() {
            isReleased = true
        }

        val listenerCount: Int get() = listeners.size
    }

    private class FakeVolumeBackend(
        val volumes: MutableMap<Int, Int> = mutableMapOf(),
        private val maxVolumes: MutableMap<Int, Int> = mutableMapOf()
    ) : VolumeBackend {
        var throwOnGet = false
        var throwOnMax = false
        var throwOnSet = false

        override fun getVolume(stream: Int): Int {
            if (throwOnGet) throw SecurityException("getVolume failed")
            return volumes[stream] ?: 0
        }

        override fun getMaxVolume(stream: Int): Int {
            if (throwOnMax) throw IllegalArgumentException("stream unsupported")
            return maxVolumes[stream] ?: 0
        }

        override fun setVolume(stream: Int, index: Int) {
            if (throwOnSet) throw SecurityException("setVolume failed")
            volumes[stream] = index
        }
    }

    private class FakeVolumeBackupStore : VolumeBackupStore {
        var saved: Map<Int, VolumeBackupEntry> = emptyMap()
            private set

        override suspend fun load(): Map<Int, VolumeBackupEntry> = saved

        override suspend fun save(entries: Map<Int, VolumeBackupEntry>) {
            saved = entries.toMap()
        }
    }

    private class FakeEqualizerStore(
        initial: EqualizerSettings = EqualizerSettings.DEFAULT
    ) : EqualizerSettingsStore {
        private val state = MutableStateFlow(initial)
        var saves: Int = 0
            private set
        var lastSaved: EqualizerSettings = initial
            private set

        override val settings: Flow<EqualizerSettings> = state

        override suspend fun save(settings: EqualizerSettings) {
            saves++
            lastSaved = settings
            state.value = settings
        }
    }

    // ------------------------------------------------------------------
    // Сборка «как в сервисе»
    // ------------------------------------------------------------------

    private class Wiring(
        private val scope: TestScope,
        initialSettings: EqualizerSettings = EqualizerSettings.DEFAULT
    ) {
        val factory = FakeEffectsFactory()
        val backend = FakeVolumeBackend(
            volumes = mutableMapOf(STREAM to 5),
            maxVolumes = mutableMapOf(STREAM to 15)
        )
        val backupStore = FakeVolumeBackupStore()
        val equalizerStore = FakeEqualizerStore(initialSettings)
        val repository = EqualizerRepository(equalizerStore)
        val coordinator = PlaybackCoordinator()
        val engine = EqualizerEngine(factory) { }
        val controller = SystemVolumeController(
            backend = backend,
            backupStore = backupStore,
            streams = setOf(STREAM),
            scope = scope,
            graceMs = SystemVolumeController.RESTORE_GRACE_MS,
            timeSource = TimeSource { scope.testScheduler.currentTime }
        )

        val musicPlayer = FakeEqualizerPlayer(audioSessionId = 41, label = "music")
        val microsoundPlayer = FakeEqualizerPlayer(audioSessionId = 42, label = "microsound")

        /** Громкость сеанса: null — ручная музыка/микрозвук вне сеанса. */
        var alarmGain: Float? = null

        init {
            engine.attach(musicPlayer)
            engine.attach(microsoundPlayer)
        }

        /** Та же связка, что в сервисе: settings + appIsAudible → engine + controller. */
        fun startSettingsFlow(scope: CoroutineScope) {
            scope.launch {
                combine(repository.settings, coordinator.appIsAudible) { settings, audible ->
                    settings to audible
                }.collect { (settings, audible) ->
                    engine.apply(settings)
                    controller.update(settings, audible)
                }
            }
        }

        /** Точка «старт/смена трека»: громкость музыки из единой композиции. */
        fun applyMusicVolume(settings: EqualizerSettings = equalizerStore.lastSaved) {
            musicPlayer.volume = VolumeCalculator.effectiveVolumes(
                sessionVolume = VolumeCalculator.calculateTargetMusicGain(alarmGain, 1.0f),
                microsoundVolume = VolumeCalculator.NEUTRAL_GAIN,
                duckingFactor = VolumeCalculator.NEUTRAL_GAIN,
                settings = settings
            ).music
        }

        /** Точка «старт микрозвука»: своё усиление относительно громкости сеанса. */
        fun applyMicrosoundVolume(
            ownGain: Float,
            settings: EqualizerSettings = equalizerStore.lastSaved
        ) {
            microsoundPlayer.volume = VolumeCalculator.effectiveVolumes(
                sessionVolume = alarmGain ?: VolumeCalculator.NEUTRAL_GAIN,
                microsoundVolume = ownGain,
                duckingFactor = VolumeCalculator.NEUTRAL_GAIN,
                settings = settings
            ).microsound
        }

        fun currentVolume(): Int = backend.getVolume(STREAM)
    }

    private val sampleTrack = TrackEntity(
        id = 1,
        uri = "content://music/track.mp3",
        title = "Трек"
    )

    private fun TestScope.wiring(
        initialSettings: EqualizerSettings = EqualizerSettings.DEFAULT
    ): Wiring = Wiring(this, initialSettings).also { it.startSettingsFlow(backgroundScope) }

    // ------------------------------------------------------------------
    // 1. Сбои и быстрые переключения
    // ------------------------------------------------------------------

    @Test
    fun effectCreationThrows_playbackAndSystemVolumeBoostContinue() = runTest {
        val factory = FakeEffectsFactory().apply { failAll = true }
        val engine = EqualizerEngine(factory) { }
        val coordinator = PlaybackCoordinator()
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeVolumeBackupStore()
        val controller = SystemVolumeController(
            backend = backend,
            backupStore = store,
            streams = setOf(3),
            scope = this,
            timeSource = TimeSource { testScheduler.currentTime }
        )
        val player = FakeEqualizerPlayer(audioSessionId = 5)

        engine.apply(ENGINE_SETTINGS)
        engine.attach(player) // создание эффектов бросает — движок не падает
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))
        controller.update(ENGINE_SETTINGS, coordinator.appIsAudible.value)
        testScheduler.runCurrent()

        assertEquals("Скорость всё равно применена", 2.0f, player.speed, 0.001f)
        assertEquals("Сбои эффектов не отменяют подъём системной громкости", 15, backend.getVolume(3))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)
        assertEquals("Живых эффектов нет", 0, factory.liveEffects)
    }

    @Test
    fun volumeBackendThrows_effectsAndPlaybackContinue() = runTest {
        val factory = FakeEffectsFactory()
        val engine = EqualizerEngine(factory) { }
        val coordinator = PlaybackCoordinator()
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeVolumeBackupStore()
        val controller = SystemVolumeController(
            backend = backend,
            backupStore = store,
            streams = setOf(3),
            scope = this,
            timeSource = TimeSource { testScheduler.currentTime }
        )
        val player = FakeEqualizerPlayer(audioSessionId = 5)
        engine.apply(ENGINE_SETTINGS)
        engine.attach(player)
        coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))

        backend.throwOnMax = true
        controller.update(ENGINE_SETTINGS, coordinator.appIsAudible.value)
        testScheduler.runCurrent()

        assertEquals("Эффекты применены, несмотря на сбой громкости", 2, factory.liveEffects)
        assertEquals(2.0f, player.speed, 0.001f)
        assertEquals("Уровень пользователя не тронут", 5, backend.getVolume(3))
        assertTrue(coordinator.state is CoordinatorState.MusicPlaying)

        // Подъём прошёл, но setVolume падает при возврате — исключение гасится.
        backend.throwOnMax = false
        controller.update(ENGINE_SETTINGS, false)
        testScheduler.runCurrent()
        controller.update(ENGINE_SETTINGS, true)
        testScheduler.runCurrent()
        assertEquals(15, backend.getVolume(3))
        backend.throwOnSet = true
        controller.restoreImmediately()
        assertEquals("Не удалось вернуть — уровень не тронут", 15, backend.getVolume(3))

        backend.throwOnSet = false
        controller.restoreImmediately()
        assertEquals(5, backend.getVolume(3))
        assertEquals("Живые эффекты не затронуты сбоем громкости", 2, factory.liveEffects)
    }

    @Test
    fun playerReleasedWhileApplyRuns_leavesNoEffectsAndDoesNotThrow() = runTest {
        val factory = FakeEffectsFactory()
        val engine = EqualizerEngine(factory) { }
        val player = FakeEqualizerPlayer(audioSessionId = 7)
        engine.attach(player) // DEFAULT: speed 1x, эффекты не создаются

        var released = false
        player.onSpeed = { p ->
            if (!released) {
                released = true
                p.markReleased()
                engine.detach(p)
            }
        }

        engine.apply(EqualizerSettings(enabled = true, bassPercent = 50, overboostPercent = 50))

        assertTrue("Плеер освобождён во время apply", released)
        assertEquals("Эффекты не созданы для освобождённого плеера", 0, factory.liveEffects)
        assertEquals("Слушатель смены sessionId снят", 0, player.listenerCount)

        // Повторное применение безопасно: плеера в движке уже нет.
        engine.apply(EqualizerSettings(enabled = true, bassPercent = 80))
        assertEquals(0, factory.liveEffects)
    }

    @Test
    fun rapidToggle50Times_finalStateMatchesLastValue_noEffectLeaks() = runTest {
        // Сценарий проверяет подъём системной громкости: он ВЫКЛЮЧЕН по умолчанию.
        val w = wiring(EqualizerSettings(boostSystemVolume = true))
        testScheduler.runCurrent()

        // Музыка играет, эквалайзер ON → системная громкость поднята.
        w.alarmGain = 0.5f
        w.applyMusicVolume()
        w.coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))
        w.repository.setEnabled(true)
        w.repository.setBassPercent(50)
        w.repository.setSpeed(2.0f)
        testScheduler.runCurrent()
        assertEquals(15, w.currentVolume())
        val volumeBefore = w.musicPlayer.volume
        assertTrue("Эффекты создавались по ходу переключений", w.factory.createdEffects > 0)

        // 50 быстрых переключений; последнее — OFF.
        repeat(50) { index ->
            w.repository.setEnabled(index % 2 == 0)
            testScheduler.runCurrent()
        }

        assertFalse("Последнее сохранённое значение — OFF", w.equalizerStore.lastSaved.enabled)
        assertEquals("Эффекты освобождены", 0, w.factory.liveEffects)
        assertEquals("Скорость сброшена в 1x", 1.0f, w.musicPlayer.speed, 0.001f)
        assertEquals("Громкость плеера не зависит от переключений", volumeBefore, w.musicPlayer.volume, 0.0001f)
        assertEquals("Системная громкость возвращена немедленно", 5, w.currentVolume())

        // Завершаем цикл включением: ровно один комплект эффектов на каждый плеер.
        w.repository.setEnabled(true)
        testScheduler.runCurrent()
        assertTrue(w.equalizerStore.lastSaved.enabled)
        assertEquals("По два живых эффекта (полосы + перегруз) на каждый из двух плееров", 4, w.factory.liveEffects)
        assertEquals(2.0f, w.musicPlayer.speed, 0.001f)
        assertEquals(volumeBefore, w.musicPlayer.volume, 0.0001f)
    }

    // ------------------------------------------------------------------
    // 2. Приоритет микрозвука и системная громкость
    // ------------------------------------------------------------------

    @Test
    fun microsoundDuringSilentWindow_priorityPreserved_volumeReboostedAndRestored() = runTest {
        val w = wiring(EqualizerSettings(enabled = true, bassPercent = 60, boostSystemVolume = true))
        testScheduler.runCurrent()
        w.alarmGain = 0.8f
        w.applyMusicVolume()
        w.coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))
        testScheduler.runCurrent()
        assertEquals("Музыка сеанса: громкость = 0.8", 0.8f, w.musicPlayer.volume, 0.001f)
        assertEquals("Эквалайзер ON и звук: системная громкость максимальна", 15, w.currentVolume())

        // Окно тишины: плеер продолжает играть с усилением 0, но приложение не
        // слышно → через grace возвращается уровень пользователя (системную
        // громкость поднимает только реально слышимый звук).
        w.coordinator.processEvent(
            CoordinatorEvent.TriggerChoppyPause(
                durationMs = 10_000L,
                timestampMs = testScheduler.currentTime,
                positionMs = 12_000L
            )
        )
        testScheduler.runCurrent()
        assertEquals("Окно тишины не убирает музыку из currentSound", CurrentSoundKind.MUSIC, w.coordinator.currentSound.value?.kind)
        assertFalse("Но приложение не слышно", w.coordinator.appIsAudible.value)
        assertEquals("Во время grace громкость ещё наша", 15, w.currentVolume())
        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()
        assertEquals("После grace — уровень пользователя", 5, w.currentVolume())

        // Микрозвук во время окна тишины: приоритет MICROSOUND сохранён.
        w.applyMicrosoundVolume(ownGain = 0.5f)
        assertEquals(
            "Микрозвук относительно громкости сеанса: 0.8 × 0.5",
            0.4f,
            w.microsoundPlayer.volume,
            0.001f
        )
        w.coordinator.processEvent(
            CoordinatorEvent.TriggerMicroSound(
                soundUri = "content://sounds/chime.wav",
                gain = 0.5f,
                maxDurationMs = 5_000L,
                timestampMs = testScheduler.currentTime
            )
        )
        testScheduler.runCurrent()
        assertTrue(w.coordinator.state is CoordinatorState.MicroSoundActive)
        assertEquals("Микрозвук снова поднял системную громкость", 15, w.currentVolume())

        // Микрозвук закончился: окно тишины отменено, музыка снова звучит.
        w.coordinator.processEvent(CoordinatorEvent.MicroSoundCompleted(testScheduler.currentTime))
        testScheduler.runCurrent()
        assertTrue(
            "После микрозвука музыка играет, а не ждёт остаток окна",
            w.coordinator.state is CoordinatorState.MusicPlaying
        )
        assertEquals("Звук снова играет → громкость поднята", 15, w.currentVolume())

        // Пользовательская пауза: возврат после grace, эффекты живы.
        w.coordinator.processEvent(CoordinatorEvent.UserPause)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()
        assertEquals(5, w.currentVolume())
        assertTrue("Эффекты не освобождены паузой", w.factory.liveEffects > 0)
    }

    // ------------------------------------------------------------------
    // 3. Уничтожение сервиса и смерть процесса
    // ------------------------------------------------------------------

    @Test
    fun serviceDestroyedWhileBoosted_restoresImmediatelyAndReleasesEffects() = runTest {
        val w = wiring(
            EqualizerSettings(enabled = true, bassPercent = 60, overboostPercent = 80, boostSystemVolume = true)
        )
        testScheduler.runCurrent()
        w.coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))
        testScheduler.runCurrent()
        assertEquals(15, w.currentVolume())
        assertTrue(w.factory.liveEffects > 0)

        // onDestroy сервиса: сначала эффекты, затем немедленный возврат громкости.
        w.engine.releaseAll()
        w.controller.restoreImmediately()
        testScheduler.runCurrent()

        assertEquals("Громкость возвращена без grace", 5, w.currentVolume())
        assertEquals("Живых эффектов не осталось", 0, w.factory.liveEffects)
        assertTrue("Резерв очищен", w.backupStore.saved.isEmpty())
    }

    @Test
    fun appKilledWhileBoosted_recoveryOnNextStartRestoresSystemVolume() = runTest {
        val w = wiring(EqualizerSettings(enabled = true, bassPercent = 60, boostSystemVolume = true))
        testScheduler.runCurrent()
        w.coordinator.processEvent(CoordinatorEvent.UserPlay(sampleTrack))
        testScheduler.runCurrent()
        assertEquals(15, w.currentVolume())
        assertEquals(
            "Резерв сохранён до подъёма",
            mapOf(3 to VolumeBackupEntry(original = 5, target = 15)),
            w.backupStore.saved
        )

        // Процесс убит: живой контроллер и эффекты исчезли, громкость осталась 15.
        val persisted = w.backupStore
        val newBackend = FakeVolumeBackend(mutableMapOf(3 to 15), mutableMapOf(3 to 15))
        val recovered = SystemVolumeController(
            backend = newBackend,
            backupStore = persisted,
            streams = emptySet(),
            scope = this,
            timeSource = TimeSource { testScheduler.currentTime }
        )

        // Тот же вход, что Application/onCreate сервиса/BOOT_COMPLETED.
        recovered.recoverIfPending()

        assertEquals("Исходная громкость восстановлена после перезапуска", 5, newBackend.getVolume(3))
        assertTrue("Резерв очищен", persisted.saved.isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. Композиция сеанса и громкость микрозвуков
    // ------------------------------------------------------------------

    @Test
    fun trackChangeAndEqualizerToggle_doNotChangeOrDoubleSessionVolume() = runTest {
        val w = wiring()
        testScheduler.runCurrent()
        w.alarmGain = 0.6f

        w.applyMusicVolume()
        w.applyMusicVolume() // смена трека: повторное применение не удваивает и не теряет gain
        assertEquals("0.6, а не 0.36 и не 1.0", 0.6f, w.musicPlayer.volume, 0.001f)

        w.repository.setEnabled(true)
        w.repository.setOverboostPercent(200)
        w.repository.setSpeed(2.0f)
        testScheduler.runCurrent()
        w.applyMusicVolume() // даже если громкость переприменили после настроек
        assertEquals(
            "Эквалайзер ON не меняет player.volume (перегруз — только LoudnessEnhancer)",
            0.6f,
            w.musicPlayer.volume,
            0.001f
        )

        w.applyMicrosoundVolume(ownGain = 0.5f)
        assertEquals("Микрозвук сеанса: 0.6 × 0.5", 0.3f, w.microsoundPlayer.volume, 0.001f)

        w.alarmGain = null
        w.applyMusicVolume()
        w.applyMicrosoundVolume(ownGain = 0.5f)
        assertEquals("Ручная музыка: полная громкость", 1.0f, w.musicPlayer.volume, 0.001f)
        assertEquals("Микрозвук вне сеанса: только своё усиление", 0.5f, w.microsoundPlayer.volume, 0.001f)

        // Выключение эквалайзера не трогает громкость плеера и сразу снимает подъём.
        w.repository.setEnabled(false)
        testScheduler.runCurrent()
        w.applyMusicVolume()
        assertEquals(1.0f, w.musicPlayer.volume, 0.001f)
        assertEquals(5, w.currentVolume())
    }

    @Test
    fun storedSettingsAreAppliedWhenServiceStarts() = runTest {
        val stored = EqualizerSettings(
            enabled = true,
            bassPercent = 40,
            treblePercent = 30,
            overboostPercent = 100,
            speed = 2.0f,
            boostSystemVolume = true
        )
        val repository = EqualizerRepository(FakeEqualizerStore(stored))
        val settings = repository.settings.first()
        val factory = FakeEffectsFactory()
        val engine = EqualizerEngine(factory) { }
        val player = FakeEqualizerPlayer(audioSessionId = 9)

        // Сервис при старте читает настройки из репозитория и применяет их
        // к плеерам до первого play (attach → applyTo).
        engine.apply(settings)
        engine.attach(player)

        assertTrue("Сохранённый ON пережил перезапуск", settings.enabled)
        assertEquals(2.0f, player.speed, 0.001f)
        assertEquals("Эффекты созданы при старте", 2, factory.liveEffects)
    }

    private companion object {
        const val STREAM = 3

        /** Настройки для сценариев со сбоями (без DataStore-репозитория). */
        val ENGINE_SETTINGS = EqualizerSettings(
            enabled = true,
            bassPercent = 50,
            overboostPercent = 100,
            speed = 2.0f,
            // Сценарии ниже проверяют именно подъём системной громкости:
            // по умолчанию (по требованию пользователя) он ВЫКЛЮЧЕН.
            boostSystemVolume = true
        )
    }
}
