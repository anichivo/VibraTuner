package com.personal.vibratuner.audio.volume

import com.personal.vibratuner.audio.eq.EqualizerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Исходный уровень системной громкости потока и уровень, который выставило
 * приложение. [target] нужен, чтобы при восстановлении отличить «громкость
 * всё ещё наша» от «пользователь изменил её вручную» даже после смерти
 * процесса (максимум потока мог измениться, например при смене Bluetooth-устройства).
 */
data class VolumeBackupEntry(
    val original: Int,
    val target: Int
)

/**
 * Тонкая абстракция над AudioManager: единственное место, где приложение
 * реально читает и меняет системную громкость. JVM-тесты используют фейк,
 * поэтому Robolectric не нужен.
 */
interface VolumeBackend {
    fun getVolume(stream: Int): Int

    fun getMaxVolume(stream: Int): Int

    /** Выставляет индекс; реализация ОБЯЗАНА использовать flags = 0 (без UI). */
    fun setVolume(stream: Int, index: Int)
}

/**
 * Постоянное хранилище исходных уровней громкости. Живёт в существующем
 * `DataStore<Preferences>` приложения; нужен для восстановления после смерти
 * процесса/перезагрузки. Реализация за интерфейсом, чтобы тесты работали
 * с фейком без Android runtime.
 */
interface VolumeBackupStore {
    suspend fun load(): Map<Int, VolumeBackupEntry>

    suspend fun save(entries: Map<Int, VolumeBackupEntry>)
}

/**
 * Инъекция времени: используется для меток планового восстановления и лога.
 * В тестах с виртуальным временем сюда передаётся время тестового планировщика.
 */
fun interface TimeSource {
    fun nowMs(): Long

    companion object {
        val SYSTEM = TimeSource { System.currentTimeMillis() }
    }
}

/**
 * ЕДИНСТВЕННЫЙ класс, которому разрешено менять системную громкость (часть 3).
 *
 * Политика:
 * - поднимаем громкость потоков плееров до максимума, только когда
 *   `settings.enabled && settings.boostSystemVolume && appIsAudible`;
 * - перед первым подъёмом ОБЯЗАТЕЛЬНО сохраняем исходные уровни, затем ставим
 *   максимум; существующий резерв никогда не перезаписывается максимальным
 *   значением (повторный подъём идемпотентен);
 * - когда звук остановился/встал на паузу — возвращаем исходные уровни после
 *   короткой задержки [RESTORE_GRACE_MS], чтобы смена треков и прерывистая
 *   пауза не дёргали громкость;
 * - когда выключили эквалайзер/подъём системной громкости, остановили сервис,
 *   убрали задачу или явно остановили воспроизведение — восстанавливаем
 *   немедленно, без задержки;
 * - поток восстанавливается ТОЛЬКО если его текущая громкость всё ещё равна
 *   нашему максимуму. Если пользователь изменил её вручную — не перезаписываем,
 *   снимаем резерв и не форсируем подъём до следующего цикла;
 * - любая ошибка backend/store логируется и не выходит наружу: громкость не
 *   может остановить, задержать или уронить воспроизведение.
 *
 * Потоки передаются снаружи — они вычисляются из `AudioAttributes` реальных
 * плееров приложения (никакого захардкоженного STREAM_MUSIC).
 *
 * Потокобезопасность: [update] и [restoreImmediately] вызываются
 * в главном потоке; вся асинхронная работа идёт в [scope] на том же
 * последовательном диспетчере. Поэтому поля класса читаются и пишутся
 * в одном потоке, а [operationMutex] сериализует только подъём и recovery.
 */
class SystemVolumeController(
    private val backend: VolumeBackend,
    private val backupStore: VolumeBackupStore,
    streams: Set<Int> = emptySet(),
    private val scope: CoroutineScope,
    private val graceMs: Long = RESTORE_GRACE_MS,
    private val timeSource: TimeSource = TimeSource.SYSTEM,
    private val log: (String) -> Unit = {}
) {

    private val targetStreams: Set<Int> = streams.toSet()

    /** Резерв в памяти: поток -> исходный и выставленный уровни. */
    private val backup = linkedMapOf<Int, VolumeBackupEntry>()
    private var backupLoaded: Boolean = false

    /** Текущее решение state machine; сюда же смотрит in-flight подъём. */
    private var shouldBoost: Boolean = false

    /** Последнее значение «эквалайзер и переключатель разрешают подъём». */
    private var boostAllowed: Boolean = false
    private var graceJob: Job? = null

    private val operationMutex = Mutex()
    private val persistMutex = Mutex()

    /**
     * Новые входы state machine. Вызывается на КАЖДОЕ изменение настроек
     * эквалайзера или флага слышимости; повтор того же решения — no-op.
     */
    fun update(newSettings: EqualizerSettings, appIsAudible: Boolean) {
        val settings = EqualizerSettings.sanitize(newSettings)
        val boostAllowedNow = settings.enabled && settings.boostSystemVolume
        val newShouldBoost = boostAllowedNow && appIsAudible
        val wasBoostAllowed = boostAllowed
        boostAllowed = boostAllowedNow

        if (newShouldBoost == shouldBoost) {
            // Особый случай: звук уже замолчал, ждёт grace-восстановления, а
            // пользователь в этот момент выключил эквалайзер/переключатель.
            // shouldBoost при этом не меняется, но ждать больше нельзя:
            // по политике выключение настроек возвращает уровень сразу.
            if (!boostAllowedNow && wasBoostAllowed) {
                log("Подъём запрещён во время grace: немедленное восстановление")
                cancelGraceRestore()
                if (restoreBackupNow("эквалайзер/подъём выключен")) persistAsync()
            }
            return
        }

        shouldBoost = newShouldBoost
        if (newShouldBoost) {
            cancelGraceRestore()
            log("Эквалайзер и звук активны: поднимаю системную громкость до максимума")
            scope.launch { boostIfNeeded() }
            return
        }

        if (!boostAllowedNow) {
            // Эквалайзер выключен или выключен подъём системной громкости:
            // ждать нечего, пользователь ждёт возврат уровня сразу.
            log("Подъём системной громкости выключен: немедленное восстановление")
            cancelGraceRestore()
            if (restoreBackupNow("эквалайзер/подъём выключен")) persistAsync()
            return
        }

        // Политика разрешает подъём, но звука нет: это пауза/остановка/
        // прерывистая пауза. Даём короткую задержку, чтобы смена треков
        // и короткие паузы не заставляли громкость мигать.
        scheduleGraceRestore()
    }

    /**
     * Немедленный возврат исходной громкости без grace-задержки. Вызывается
     * на путях остановки сервиса/задачи/явной остановки воспроизведения.
     * Безопасен и идемпотентен: без резерва — no-op.
     */
    fun restoreImmediately() {
        val wasBoosted = shouldBoost
        shouldBoost = false
        cancelGraceRestore()
        log(
            if (wasBoosted) {
                "Немедленное восстановление системной громкости (остановка)"
            } else {
                "Немедленное восстановление системной громкости"
            }
        )
        if (restoreBackupNow("немедленное восстановление")) persistAsync()
    }

    /**
     * Восстановление забытого подъёма после смерти процесса/перезагрузки.
     * Действует по тому же правилу «только если громкость всё ещё наша».
     * Если живой контроллер сейчас поднимает громкость, ничего не делает:
     * его резерв должен пережить восстановление и быть возвращён позже.
     *
     * `suspend`, чтобы вызывающий (Application/сервис/BOOT_COMPLETED) мог
     * дождаться и сохранить очищенный резерв до завершения процесса.
     */
    suspend fun recoverIfPending() {
        operationMutex.withLock {
            if (shouldBoost) {
                log("Recovery пропущен: громкость поднимает живой контроллер")
                return
            }
            val stored = try {
                backupStore.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Не удалось прочитать резерв системной громкости: ${e.message}")
                return
            }
            backupLoaded = true
            backup.clear()
            backup.putAll(stored)
            if (stored.isEmpty()) {
                log("Незавершённого подъёма системной громкости нет")
                return
            }
            log("Найден незавершённый подъём (${stored.size} поток(а)): восстанавливаю")
            if (restoreBackupNow("восстановление после сбоя")) {
                // Дожидаемся записи: BOOT_COMPLETED может завершить процесс
                // сразу после возврата из ресивера.
                saveBackupSnapshot(backup.toMap())
            }
        }
    }

    // ------------------------------------------------------------------
    // Внутренняя механика
    // ------------------------------------------------------------------

    private suspend fun boostIfNeeded() {
        operationMutex.withLock {
            if (!backupLoaded) {
                val stored = try {
                    backupStore.load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Не удалось прочитать резерв системной громкости: ${e.message}")
                    return
                }
                backup.clear()
                backup.putAll(stored)
                backupLoaded = true
            }
            if (!shouldBoost) {
                log("Подъём громкости отменён: звук уже не играет")
                return
            }
            if (targetStreams.isEmpty()) {
                log("Нет потоков для подъёма системной громкости")
                return
            }

            val newEntries = linkedMapOf<Int, VolumeBackupEntry>()
            val maxLevels = linkedMapOf<Int, Int>()
            for (stream in targetStreams) {
                val max = try {
                    backend.getMaxVolume(stream)
                } catch (e: Exception) {
                    log("Поток $stream недоступен (max): ${e.message}")
                    continue
                }
                if (max <= 0) {
                    log("Поток $stream: максимальный индекс 0 — пропуск")
                    continue
                }
                val current = try {
                    backend.getVolume(stream)
                } catch (e: Exception) {
                    log("Поток $stream недоступен (volume): ${e.message}")
                    continue
                }
                val existing = backup[stream]
                when {
                    existing == null ->
                        newEntries[stream] = VolumeBackupEntry(original = current, target = max)
                    // Пользователь успел изменить громкость, пока резерв не был
                    // восстановлен: запоминаем ЕГО уровень, а не максимум.
                    current != existing.target ->
                        newEntries[stream] = VolumeBackupEntry(original = current, target = max)
                }
                maxLevels[stream] = max
            }
            if (maxLevels.isEmpty()) {
                log("Нечего поднимать: все потоки недоступны")
                return
            }

            val previous = backup.toMap()
            if (newEntries.isNotEmpty()) {
                backup.putAll(newEntries)
                // КРИТИЧНО: сначала постоянный резерв, и только потом максимум.
                if (!saveBackupSnapshot(backup.toMap())) {
                    backup.clear()
                    backup.putAll(previous)
                    log("Подъём отменён: исходная громкость не сохранена")
                    return
                }
            }
            if (!shouldBoost) {
                // Состояние изменилось, пока писали резерв: громкость не
                // поднимаем, а только что сохранённые записи откатываем.
                if (newEntries.isNotEmpty()) {
                    backup.clear()
                    backup.putAll(previous)
                    saveBackupSnapshot(backup.toMap())
                }
                log("Подъём отменён после сохранения: звук уже не играет")
                return
            }

            for ((stream, max) in maxLevels) {
                try {
                    backend.setVolume(stream, max)
                    log("Поток $stream: громкость поднята до $max")
                } catch (e: Exception) {
                    // Сбой одного потока не мешает остальным и не трогает playback.
                    log("Не удалось поднять громкость потока $stream: ${e.message}")
                }
            }
        }
    }

    private fun scheduleGraceRestore() {
        cancelGraceRestore()
        val dueAtMs = timeSource.nowMs() + graceMs
        log("Звук остановился: восстановление громкости через $graceMs мс (до $dueAtMs)")
        graceJob = scope.launch {
            delay(graceMs)
            if (shouldBoost) {
                log("Grace-восстановление отменено: звук снова играет")
                return@launch
            }
            log("Звук не вернулся: возвращаю исходную системную громкость")
            if (restoreBackupNow("звук остановился")) persistAsync()
        }
    }

    private fun cancelGraceRestore() {
        graceJob?.cancel()
        graceJob = null
    }

    /**
     * Синхронное восстановление по правилу «только если текущий уровень всё
     * ещё равен нашему target». Возвращает true, если резерв изменился и его
     * нужно сохранить.
     */
    private fun restoreBackupNow(reason: String): Boolean {
        if (!backupLoaded) {
            log("Восстановление пропущено: резерв ещё не загружен")
            return false
        }
        if (backup.isEmpty()) return false

        var changed = false
        for ((stream, entry) in backup.toMap()) {
            val current = try {
                backend.getVolume(stream)
            } catch (e: Exception) {
                log("Не удалось прочитать громкость потока $stream: ${e.message}")
                continue
            }
            if (current != entry.target) {
                log(
                    "Поток $stream: пользователь изменил громкость " +
                        "($current вместо ${entry.target}); не перезаписываю"
                )
                backup.remove(stream)
                changed = true
                continue
            }
            try {
                backend.setVolume(stream, entry.original)
            } catch (e: Exception) {
                log("Не удалось вернуть громкость потока $stream: ${e.message}")
                continue
            }
            backup.remove(stream)
            changed = true
            log("Поток $stream: возвращена исходная громкость ${entry.original} ($reason)")
        }
        return changed
    }

    private suspend fun saveBackupSnapshot(snapshot: Map<Int, VolumeBackupEntry>): Boolean =
        persistMutex.withLock {
            try {
                backupStore.save(snapshot)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Не удалось сохранить резерв системной громкости: ${e.message}")
                false
            }
        }

    private fun persistAsync() {
        if (!backupLoaded) return
        val snapshot = backup.toMap()
        scope.launch { saveBackupSnapshot(snapshot) }
    }

    companion object {
        /**
         * Задержка восстановления после остановки звука: короткие паузы между
         * треками и прерывистая пауза не должны дёргать системную громкость.
         */
        const val RESTORE_GRACE_MS = 1_500L
    }
}
