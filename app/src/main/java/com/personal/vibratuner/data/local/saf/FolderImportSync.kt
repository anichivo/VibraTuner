package com.personal.vibratuner.data.local.saf

import com.personal.vibratuner.data.local.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Сохранённая папка импорта (SAF tree-URI): чтение и запись. */
interface SavedFolderStore {
    suspend fun savedFolder(): String?
    suspend fun saveFolder(uri: String?)
}

/** Доступ к сохранённой папке и её сканирование (реализация — SAF/DocumentFile). */
interface SavedFolderScanner {
    fun hasReadPermission(treeUri: String): Boolean

    /**
     * Рекурсивно читает папку и возвращает треки. Может бросить исключение:
     * [FolderImportSync] логирует сбой и не падает.
     */
    fun scan(treeUri: String): List<TrackEntity>
}

/** Одноразовое событие автосканирования для корневого Snackbar. */
sealed interface FolderImportEvent {
    /** Доступ к сохранённой папке потерян: нужно выбрать её заново. */
    data object PermissionLost : FolderImportEvent

    /** Из сохранённой папки добавлены новые треки. */
    data class Added(val count: Int) : FolderImportEvent
}

/** Результат одного прохода автосканирования. */
enum class FolderScanOutcome {
    /** Пользователь ещё не выбирал папку. */
    NoFolder,

    /** Такой же проход уже был только что — пропущено (защита от частых вызовов). */
    Throttled,

    /** Persistable-доступ потерян; папка забыта, пользователю показано сообщение. */
    PermissionLost,

    /** Добавлены новые файлы. */
    Added,

    /** Новых файлов нет; существующие записи не тронуты. */
    Unchanged,

    /** Сбой сканирования/вставки: пишем лог и продолжаем, папка остаётся сохранённой. */
    Failed
}

/**
 * Автоматическое дочитывание НОВЫХ файлов из сохранённой папки импорта.
 *
 * Причина прежнего поведения: выбранная папка (`OpenDocumentTree`) нигде не
 * сохранялась — `takePersistableUriPermission` выдавался, но URI терялся после
 * закрытия экрана, поэтому новые файлы никогда не подхватывались без повторного
 * выбора папки.
 *
 * Правила:
 * - tree-URI хранится в СУЩЕСТВУЮЩЕМ DataStore (`import_folder_uri`);
 * - сканирование запускается на старте приложения, при возврате в foreground и
 *   при открытии библиотеки, ВСЕГДА в фоновом скоупе (`Dispatchers.IO`);
 * - часто повторяющиеся запросы схлопываются ([MIN_RESCAN_INTERVAL_MS]) и
 *   сериализуются [Mutex], поэтому параллельных сканирований нет;
 * - вставляются ТОЛЬКО новые файлы (единая [NewTrackInserter], канонический
 *   ключ файла), существующие записи и исчезнувшие из папки файлы не трогаются;
 * - потерянный persistable-доступ не роняет приложение: пишем лог, забываем
 *   URI и один раз просим выбрать папку снова через [FolderImportEvent.PermissionLost];
 * - ContentObserver/WorkManager не используются.
 *
 * Класс не зависит от Android API: покрыт JVM-тестами с фейками.
 */
class FolderImportSync(
    private val savedFolder: SavedFolderStore,
    private val scanner: SavedFolderScanner,
    private val inserter: NewTrackInserter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowNanos: () -> Long = { System.nanoTime() },
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    private val minIntervalMs: Long = MIN_RESCAN_INTERVAL_MS
) {

    private val mutex = Mutex()

    /** Время последнего НАЧАТОГО прохода (наносекунды монотонных часов). */
    private var lastScanAtNanos = Long.MIN_VALUE

    /** Сообщение о потерянном доступе показываем один раз до нового выбора папки. */
    private var permissionLostReported = false

    private val _event = MutableStateFlow<FolderImportEvent?>(null)

    /**
     * Единственное одноразовое событие автосканирования. Хранится состоянием
     * (а не SharedFlow без replay), чтобы сообщение не потерялось, если событие
     * случится до подписки UI на старте.
     */
    val event: StateFlow<FolderImportEvent?> = _event.asStateFlow()

    /** Событие показано (или его не нужно показывать) — больше не повторяем. */
    fun consumeEvent() {
        _event.value = null
    }

    /**
     * Пользователь выбрал папку: запоминаем tree-URI. Доступ уже должен быть
     * persistable (проверяется вызывающей стороной до вызова).
     */
    suspend fun rememberFolder(treeUri: String) {
        val normalized = treeUri.trim()
        if (normalized.isEmpty()) return
        try {
            savedFolder.saveFolder(normalized)
            permissionLostReported = false
            _event.value = null
            logger("Папка импорта сохранена для автосканирования", null)
        } catch (e: Exception) {
            logger("Не удалось сохранить папку импорта", e)
        }
    }

    /** Пользовательское сканирование уже произошло — не сканируем повторно сразу. */
    fun markScannedNow() {
        lastScanAtNanos = nowNanos()
    }

    /**
     * Запрашивает фоновое автосканирование. Вызывать можно с главного потока:
     * работа уходит в IO-скоуп, ошибки логируются и не выходят наружу.
     */
    fun requestRescan(reason: String) {
        scope.launch {
            try {
                scanSaved(reason)
            } catch (e: Exception) {
                logger("Автосканирование папки ($reason) не выполнено", e)
            }
        }
    }

    /**
     * Один проход автосканирования. Публичный и suspend — его же вызывают
     * JVM-тесты с фейковыми слоями (без корутинного скоупа и Android).
     */
    suspend fun scanSaved(reason: String): FolderScanOutcome = mutex.withLock {
        val folder = try {
            savedFolder.savedFolder()
        } catch (e: Exception) {
            logger("Не удалось прочитать сохранённую папку импорта", e)
            return@withLock FolderScanOutcome.Failed
        }
        if (folder.isNullOrBlank()) return@withLock FolderScanOutcome.NoFolder

        val now = nowNanos()
        if (lastScanAtNanos != Long.MIN_VALUE &&
            now - lastScanAtNanos < minIntervalMs * NANOS_PER_MS
        ) {
            return@withLock FolderScanOutcome.Throttled
        }
        lastScanAtNanos = now

        val hasPermission = try {
            scanner.hasReadPermission(folder)
        } catch (e: Exception) {
            logger("Не удалось проверить доступ к папке импорта", e)
            false
        }
        if (!hasPermission) {
            return@withLock permissionLost(folder, reason, error = null)
        }

        val tracks = try {
            scanner.scan(folder)
        } catch (e: SecurityException) {
            // Доступ пропал между проверкой и сканированием.
            return@withLock permissionLost(folder, reason, e)
        } catch (e: Exception) {
            logger("Не удалось прочитать папку импорта ($reason)", e)
            return@withLock FolderScanOutcome.Failed
        }

        val result = try {
            inserter.insertNew(tracks)
        } catch (e: Exception) {
            logger("Не удалось добавить новые треки из папки ($reason)", e)
            return@withLock FolderScanOutcome.Failed
        }
        val added = result?.added ?: 0
        if (added <= 0) {
            logger(
                "Автосканирование папки ($reason): новых треков нет " +
                    "(проверено ${tracks.size})",
                null
            )
            return@withLock FolderScanOutcome.Unchanged
        }
        logger("Автосканирование папки ($reason): добавлено треков $added", null)
        _event.value = FolderImportEvent.Added(added)
        FolderScanOutcome.Added
    }

    /** Потерянный доступ: лог, забыть URI (иначе сканирование бессмысленно), событие. */
    private suspend fun permissionLost(
        folder: String,
        reason: String,
        error: Throwable?
    ): FolderScanOutcome {
        logger(
            "Нет persistable-доступа к сохранённой папке импорта ($reason): " +
                "нужно выбрать папку заново",
            error
        )
        try {
            savedFolder.saveFolder(null)
        } catch (e: Exception) {
            logger("Не удалось забыть недоступную папку импорта", e)
        }
        if (!permissionLostReported) {
            permissionLostReported = true
            _event.value = FolderImportEvent.PermissionLost
        }
        return FolderScanOutcome.PermissionLost
    }

    companion object {
        /**
         * Старт приложения, возврат в foreground и открытие библиотеки часто
         * приходят почти одновременно: не сканируем одну и ту же папку дважды.
         */
        const val MIN_RESCAN_INTERVAL_MS = 2_000L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
