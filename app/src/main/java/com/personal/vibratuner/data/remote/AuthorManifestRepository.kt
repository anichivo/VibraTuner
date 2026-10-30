package com.personal.vibratuner.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Итог загрузки манифеста «От автора» для UI. */
sealed interface AuthorManifestLoadResult {
    /** Данные есть: [fromCache] = true, если показан сохранённый кеш. */
    data class Data(
        val playlists: List<AuthorManifestPlaylist>,
        val fromCache: Boolean
    ) : AuthorManifestLoadResult

    /** Нет ни сети, ни валидного кеша — UI показывает ошибку и «Повторить». */
    data class Failed(val reason: String) : AuthorManifestLoadResult
}

/**
 * Загрузка и кеширование манифеста плейлистов «От автора» (этап 4).
 *
 * - сеть: `HttpURLConnection` (сторонних HTTP-библиотек в проекте нет),
 *   только https, connect timeout 10 с, read timeout 15 с, лимит ответа 1 МБ;
 * - кеш: сырой JSON в приватном файле приложения ([cacheFile]) через
 *   временный файл и rename (атомарно);
 * - при любой ошибке сети/разбора читается кеш; нет кеша — [AuthorManifestLoadResult.Failed];
 * - автоматических повторов НЕТ: повтор — только кнопкой в UI.
 *
 * Все блокирующие операции выполняются на [ioDispatcher].
 */
class AuthorManifestRepository(
    private val manifestUrl: String,
    private val cacheFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Stage 3: только кеш, БЕЗ сети. Нужен экрану «Звуки» для иконок копий
     * каталога при холодном старте/офлайне: адреса иконок уже сохранены в
     * кеше манифеста, а сами PNG лежат в `PlaylistIconCache`.
     *
     * `null` — кеша нет или он повреждён; сеть при этом НИКОГДА не трогается.
     */
    suspend fun loadCached(): List<AuthorManifestPlaylist>? = withContext(ioDispatcher) {
        val cached = readCache() ?: return@withContext null
        when (val parsed = AuthorManifestParser.parse(cached, manifestUrl)) {
            is AuthorManifestParseResult.Success -> {
                logWarnings(parsed.warnings)
                parsed.playlists
            }

            is AuthorManifestParseResult.Failure -> {
                Log.w(TAG, "Кеш манифеста не разобран (иконки копий): ${parsed.reason}")
                null
            }
        }
    }

    /** Загружает манифест: сначала сеть, при ошибке — кеш. */
    suspend fun load(): AuthorManifestLoadResult = withContext(ioDispatcher) {
        if (!isConfigured()) {
            val reason = "адрес манифеста не задан (AUTHOR_MANIFEST_URL — шаблон или пусто)"
            Log.w(TAG, "Манифест «От автора» не загружен: $reason")
            return@withContext fromCacheOrFail(reason)
        }

        var failureReason: String? = null
        val fetched = fetchRaw()
        when (fetched) {
            is FetchResult.Success -> {
                // Относительные пути `icon` разрешаются от адреса манифеста.
                when (val parsed = AuthorManifestParser.parse(fetched.body, manifestUrl)) {
                    is AuthorManifestParseResult.Success -> {
                        logWarnings(parsed.warnings)
                        Log.i(
                            TAG,
                            "Манифест «От автора» получен из сети: плейлистов ${parsed.playlists.size}, " +
                                "с иконками ${parsed.playlists.count { it.icon != null }}"
                        )
                        writeCache(fetched.body)
                        return@withContext AuthorManifestLoadResult.Data(
                            playlists = parsed.playlists,
                            fromCache = false
                        )
                    }

                    is AuthorManifestParseResult.Failure -> {
                        logWarnings(parsed.warnings)
                        failureReason = parsed.reason
                        Log.w(TAG, "Манифест «От автора» не разобран: ${parsed.reason}")
                    }
                }
            }

            is FetchResult.Failure -> {
                failureReason = fetched.reason
                Log.w(TAG, "Манифест «От автора» не получен: ${fetched.reason}")
            }
        }
        fromCacheOrFail(failureReason ?: "неизвестная ошибка")
    }

    /** Кеш после неудачного обновления; при отсутствии/порче — [AuthorManifestLoadResult.Failed]. */
    private fun fromCacheOrFail(failureReason: String): AuthorManifestLoadResult {
        val cached = readCache()
        if (cached == null) {
            Log.w(TAG, "Кеша манифеста нет — показываем ошибку (причина: $failureReason)")
            return AuthorManifestLoadResult.Failed(failureReason)
        }
        return when (val parsed = AuthorManifestParser.parse(cached, manifestUrl)) {
            is AuthorManifestParseResult.Success -> {
                logWarnings(parsed.warnings)
                Log.i(
                    TAG,
                    "Показан кеш манифеста «От автора»: плейлистов ${parsed.playlists.size}, " +
                        "причина: $failureReason"
                )
                AuthorManifestLoadResult.Data(playlists = parsed.playlists, fromCache = true)
            }

            is AuthorManifestParseResult.Failure -> {
                Log.w(TAG, "Кеш манифеста повреждён: ${parsed.reason}")
                AuthorManifestLoadResult.Failed("$failureReason; кеш повреждён: ${parsed.reason}")
            }
        }
    }

    /** Шаблон из репозитория или пустое значение — сеть не трогаем. */
    private fun isConfigured(): Boolean {
        val url = manifestUrl.trim()
        return url.isNotEmpty() && !url.contains(PLACEHOLDER_MARKER)
    }

    private fun fetchRaw(): FetchResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(manifestUrl.trim())
            if (!url.protocol.equals("https", ignoreCase = true)) {
                return FetchResult.Failure("адрес манифеста должен быть https")
            }
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            // GitHub-адрес манифеста отвечает 302 на raw-хост: следуем редиректам
            // (в т.ч. на другой хост) так же, как загрузчик треков и иконок.
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return FetchResult.Failure("HTTP $code")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        return FetchResult.Failure("размер манифеста превышает 1 МБ")
                    }
                    output.write(buffer, 0, read)
                }
                return FetchResult.Success(String(output.toByteArray(), Charsets.UTF_8))
            }
        } catch (e: Exception) {
            return FetchResult.Failure("сеть: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** Атомарная запись: временный файл рядом, затем rename поверх кеша. */
    private fun writeCache(raw: String) {
        val parent = cacheFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tempFile = File(parent, cacheFile.name + TEMP_SUFFIX)
        try {
            tempFile.writeText(raw, Charsets.UTF_8)
            if (!tempFile.renameTo(cacheFile)) {
                // На некоторых файловых системах rename поверх существующего файла не работает.
                if (cacheFile.exists() && cacheFile.delete() && tempFile.renameTo(cacheFile)) {
                    Log.i(TAG, "Кеш манифеста обновлён: ${cacheFile.name}")
                } else {
                    Log.w(TAG, "Не удалось переименовать кеш манифеста")
                    tempFile.delete()
                }
            } else {
                Log.i(TAG, "Кеш манифеста обновлён: ${cacheFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось записать кеш манифеста: ${e.message}")
            tempFile.delete()
        }
    }

    private fun readCache(): String? {
        return try {
            if (!cacheFile.exists() || !cacheFile.isFile) return null
            if (cacheFile.length() > MAX_RESPONSE_BYTES) {
                Log.w(TAG, "Кеш манифеста больше 1 МБ — игнорируем")
                return null
            }
            cacheFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать кеш манифеста: ${e.message}")
            null
        }
    }

    private fun logWarnings(warnings: List<String>) {
        warnings.forEach { warning -> Log.w(TAG, "Манифест «От автора»: $warning") }
    }

    private sealed interface FetchResult {
        data class Success(val body: String) : FetchResult
        data class Failure(val reason: String) : FetchResult
    }

    companion object {
        /** Имя файла кеша в `filesDir`: сырой JSON манифеста. */
        const val CACHE_FILE_NAME = "author_manifest.json"

        private const val TEMP_SUFFIX = ".tmp"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        private const val BUFFER_SIZE = 8 * 1024

        /** Признак незаполненного шаблона из `AppConfig`. */
        private const val PLACEHOLDER_MARKER = "OWNER/REPO"

        private const val TAG = "AuthorManifest"
    }
}
