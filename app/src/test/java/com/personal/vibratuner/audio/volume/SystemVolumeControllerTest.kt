package com.personal.vibratuner.audio.volume

import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты state machine [SystemVolumeController] с фейковыми backend/store
 * и виртуальным временем. Robolectric и новые библиотеки не используются.
 */
class SystemVolumeControllerTest {

    private val settingsOn = EqualizerSettings(enabled = true, boostSystemVolume = true)
    private val settingsEqualizerOff = EqualizerSettings(enabled = false, boostSystemVolume = true)
    private val settingsSystemVolumeOff = EqualizerSettings(enabled = true, boostSystemVolume = false)

    // ------------------------------------------------------------------
    // Фейки
    // ------------------------------------------------------------------

    private class FakeVolumeBackend(
        val volumes: MutableMap<Int, Int> = mutableMapOf(),
        private val maxVolumes: MutableMap<Int, Int> = mutableMapOf()
    ) : VolumeBackend {
        val setCalls = mutableListOf<Pair<Int, Int>>()
        var throwOnGet = false
        var throwOnMax = false
        var throwOnSet = false
        var onSet: ((Int, Int) -> Unit)? = null

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
            onSet?.invoke(stream, index)
            setCalls += stream to index
            volumes[stream] = index
        }
    }

    private class FakeBackupStore(
        initial: Map<Int, VolumeBackupEntry> = emptyMap()
    ) : VolumeBackupStore {
        var saved: Map<Int, VolumeBackupEntry> = initial.toMap()
            private set
        var saveCount: Int = 0
            private set
        var throwOnLoad = false
        var throwOnSave = false
        var persistThenThrow = false

        override suspend fun load(): Map<Int, VolumeBackupEntry> {
            if (throwOnLoad) throw IllegalStateException("load failed")
            return saved
        }

        override suspend fun save(entries: Map<Int, VolumeBackupEntry>) {
            saveCount++
            saved = entries.toMap()
            if (throwOnSave || persistThenThrow) throw IllegalStateException("save failed")
        }
    }

    private fun TestScope.newController(
        backend: VolumeBackend,
        store: VolumeBackupStore,
        streams: Set<Int>
    ): SystemVolumeController = SystemVolumeController(
        backend = backend,
        backupStore = store,
        streams = streams,
        scope = this,
        graceMs = SystemVolumeController.RESTORE_GRACE_MS,
        timeSource = TimeSource { testScheduler.currentTime }
    )

    // ------------------------------------------------------------------
    // shouldBoost
    // ------------------------------------------------------------------

    @Test
    fun equalizerOff_doesNotTouchSystemVolume() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(EqualizerSettings.DEFAULT, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals(5, backend.getVolume(3))
        assertTrue(backend.setCalls.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun onlyBoostSystemVolumeOff_doesNotBoostEvenIfEqualizerOn() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsSystemVolumeOff, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals(5, backend.getVolume(3))
        assertTrue("Подъём выключен — громкость не меняется", backend.setCalls.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun equalizerOnButNoSound_doesNotBoost() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()

        assertTrue(backend.setCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // Подъём и grace-восстановление
    // ------------------------------------------------------------------

    @Test
    fun boostThenGraceRestore() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals("Громкость поднята до максимума", 15, backend.getVolume(3))
        assertEquals(mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)

        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        assertEquals("Во время grace громкость ещё поднята", 15, backend.getVolume(3))

        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()

        assertEquals("Возвращена исходная громкость", 5, backend.getVolume(3))
        assertTrue("Резерв очищен", store.saved.isEmpty())
    }

    @Test
    fun graceRestore_happensExactlyAt1500ms() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(1_499)
        testScheduler.runCurrent()
        assertEquals("За 1 мс до 1500 громкость ещё на максимуме", 15, backend.getVolume(3))

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("Ровно на 1500 мс громкость возвращена", 5, backend.getVolume(3))
    }

    @Test
    fun reBoostInsideGraceWindow_cancelsRestoreAndKeepsOriginalBackup() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(1_000)
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()

        assertEquals("Повторный звук поднял громкость снова", 15, backend.getVolume(3))
        assertEquals(
            "Резерв сохранил ИСХОДНЫЙ уровень",
            mapOf(3 to VolumeBackupEntry(5, 15)),
            store.saved
        )
    }

    // ------------------------------------------------------------------
    // Немедленное восстановление
    // ------------------------------------------------------------------

    @Test
    fun equalizerOff_restoresImmediatelyWithoutGrace() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertEquals(15, backend.getVolume(3))

        controller.update(settingsEqualizerOff, appIsAudible = true)
        assertEquals("Эквалайзер выключен — уровень вернулся сразу", 5, backend.getVolume(3))

        testScheduler.runCurrent()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun systemVolumeSwitchOff_restoresImmediately() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        controller.update(settingsSystemVolumeOff, appIsAudible = true)

        assertEquals(5, backend.getVolume(3))
    }

    @Test
    fun equalizerOffDuringGrace_restoresImmediately() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals("Grace ещё не истёк", 15, backend.getVolume(3))

        // Звук уже молчит, но выключение эквалайзера обязано вернуть уровень сразу.
        controller.update(settingsEqualizerOff, appIsAudible = false)

        assertEquals("Без ожидания остатка grace", 5, backend.getVolume(3))
        testScheduler.runCurrent()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun restoreImmediately_usedForDestroyAndTaskRemoved() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        controller.restoreImmediately()

        assertEquals("Восстановление без grace", 5, backend.getVolume(3))
        testScheduler.runCurrent()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun restoreImmediately_withoutBackup_isSafeNoOp() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.restoreImmediately()
        testScheduler.runCurrent()

        assertEquals(5, backend.getVolume(3))
        assertTrue(backend.setCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // Ручное изменение громкости пользователем
    // ------------------------------------------------------------------

    @Test
    fun userChangedVolume_restoreDoesNotOverwriteAndDropsBackup() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        // Пользователь вручную изменил громкость, пока играла музыка.
        backend.volumes[3] = 8

        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()

        assertEquals("Ручной уровень пользователя не перезаписан", 8, backend.getVolume(3))
        assertEquals("Был только подъём, обратной записи нет", listOf(3 to 15), backend.setCalls)
        assertTrue("Резерв снят", store.saved.isEmpty())
    }

    // ------------------------------------------------------------------
    // Несколько потоков
    // ------------------------------------------------------------------

    @Test
    fun twoStreams_eachRestoredToItsOwnLevel() = runTest {
        val backend = FakeVolumeBackend(
            volumes = mutableMapOf(3 to 5, 4 to 9),
            maxVolumes = mutableMapOf(3 to 15, 4 to 7)
        )
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3, 4))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertEquals(15, backend.getVolume(3))
        assertEquals(7, backend.getVolume(4))
        assertEquals(
            mapOf(
                3 to VolumeBackupEntry(5, 15),
                4 to VolumeBackupEntry(9, 7)
            ),
            store.saved
        )

        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()

        assertEquals("Поток 3 вернулся к 5", 5, backend.getVolume(3))
        assertEquals("Поток 4 вернулся к 9", 9, backend.getVolume(4))
        assertTrue(store.saved.isEmpty())
    }

    // ------------------------------------------------------------------
    // Идемпотентность и порядок сохранения
    // ------------------------------------------------------------------

    @Test
    fun doubleBoost_isIdempotent() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertEquals(1, store.saveCount)

        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals("Повторный подъём не перезаписывает резерв", 1, store.saveCount)
        assertEquals(mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)
        assertEquals(15, backend.getVolume(3))
    }

    @Test
    fun backupIsPersistedBeforeFirstSet() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        var backupPresentAtFirstSet = false
        backend.onSet = { stream, _ ->
            if (backend.setCalls.isEmpty()) {
                backupPresentAtFirstSet = store.saved.containsKey(stream)
            }
        }

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        assertTrue(
            "К моменту первого setVolume резерв уже сохранён",
            backupPresentAtFirstSet
        )
        assertEquals(listOf(3 to 15), backend.setCalls)
    }

    @Test
    fun failedPersist_doesNotRaiseVolume_andRecoveryLeavesUserLevel() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore().apply { persistThenThrow = true }
        val controller = newController(backend, store, setOf(3))

        // Процесс «умер» сразу после записи резерва, но до setVolume.
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals("Без сохранённого резерва громкость не поднимается", 5, backend.getVolume(3))
        assertTrue(backend.setCalls.isEmpty())
        assertEquals(mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)

        // Новый процесс: громкость осталась пользовательской, target не достигнут.
        store.persistThenThrow = false
        val newBackend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val recovered = newController(newBackend, store, emptySet())
        recovered.recoverIfPending()

        assertEquals(5, newBackend.getVolume(3))
        assertTrue("Пользовательский уровень не перезаписан", newBackend.setCalls.isEmpty())
        assertTrue("Устаревший резерв снят", store.saved.isEmpty())
    }

    // ------------------------------------------------------------------
    // Crash/kill recovery
    // ------------------------------------------------------------------

    @Test
    fun recoverIfPending_restoresPersistedBackup() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 15), mutableMapOf(3 to 15))
        val store = FakeBackupStore(mapOf(3 to VolumeBackupEntry(4, 15)))
        val controller = newController(backend, store, emptySet())

        controller.recoverIfPending()

        assertEquals("Исходная громкость восстановлена", 4, backend.getVolume(3))
        assertEquals(listOf(3 to 4), backend.setCalls)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun recoverIfPending_withoutBackup_doesNothing() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 15), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, emptySet())

        controller.recoverIfPending()

        assertTrue(backend.setCalls.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun recoverIfPending_whenCurrentDiffers_doesNotOverwrite() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 8), mutableMapOf(3 to 15))
        val store = FakeBackupStore(mapOf(3 to VolumeBackupEntry(4, 15)))
        val controller = newController(backend, store, emptySet())

        controller.recoverIfPending()

        assertEquals("Ручной уровень пользователя не перезаписан", 8, backend.getVolume(3))
        assertTrue(backend.setCalls.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun recoverIfPending_skippedWhileLiveControllerBoosts() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertEquals(15, backend.getVolume(3))

        controller.recoverIfPending()

        assertEquals("Живой подъём не откатывается recovery", 15, backend.getVolume(3))
        assertEquals(mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)
    }

    // ------------------------------------------------------------------
    // Ошибки backend/store
    // ------------------------------------------------------------------

    @Test
    fun backendExceptions_areSwallowed() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        backend.throwOnMax = true
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertTrue(backend.setCalls.isEmpty())

        backend.throwOnMax = false
        backend.throwOnGet = true
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SystemVolumeController.RESTORE_GRACE_MS)
        testScheduler.runCurrent()

        backend.throwOnGet = false
        backend.throwOnSet = true
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        // Ни одно исключение не вышло наружу; резерв записан, подъём не случился.
        assertTrue(store.saved.isNotEmpty())
        assertTrue("Сбой set не добавил вызовов", backend.setCalls.isEmpty())
        assertEquals(5, backend.getVolume(3))
    }

    @Test
    fun backendExceptionsDuringRestore_areSwallowed() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertEquals(15, backend.getVolume(3))

        backend.throwOnGet = true
        controller.restoreImmediately()
        assertEquals("Не удалось прочитать — уровень не тронут", 15, backend.volumes[3])
        assertEquals("Резерв оставлен для следующей попытки", mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)

        backend.throwOnGet = false
        backend.throwOnSet = true
        controller.restoreImmediately()
        assertEquals("Не удалось записать — уровень не тронут", 15, backend.volumes[3])
        assertEquals(mapOf(3 to VolumeBackupEntry(5, 15)), store.saved)

        backend.throwOnSet = false
        controller.restoreImmediately()
        assertEquals(5, backend.getVolume(3))
        testScheduler.runCurrent()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun storeLoadAndSaveFailures_areSwallowed() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 15))
        val store = FakeBackupStore().apply { throwOnLoad = true }
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertTrue("Без резерва подъём не выполняется", backend.setCalls.isEmpty())

        store.throwOnLoad = false
        store.throwOnSave = true
        controller.update(settingsOn, appIsAudible = false)
        testScheduler.runCurrent()
        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()
        assertTrue("Сбой записи резерва отменяет подъём", backend.setCalls.isEmpty())
    }

    @Test
    fun maxIndexZero_isSkipped() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 5), mutableMapOf(3 to 0))
        val store = FakeBackupStore()
        val controller = newController(backend, store, setOf(3))

        controller.update(settingsOn, appIsAudible = true)
        testScheduler.runCurrent()

        assertEquals(5, backend.getVolume(3))
        assertTrue(backend.setCalls.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun recoverWithBackendException_isSwallowedAndKeepsBackup() = runTest {
        val backend = FakeVolumeBackend(mutableMapOf(3 to 15), mutableMapOf(3 to 15))
        val store = FakeBackupStore(mapOf(3 to VolumeBackupEntry(4, 15)))
        val controller = newController(backend, store, emptySet())
        backend.throwOnGet = true

        controller.recoverIfPending()

        assertTrue(backend.setCalls.isEmpty())
        assertEquals("Резерв сохранён для следующей попытки", mapOf(3 to VolumeBackupEntry(4, 15)), store.saved)
    }
}
