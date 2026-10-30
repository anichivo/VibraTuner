package com.personal.vibratuner.data.remote

import android.util.Log
import com.personal.vibratuner.core.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Последний опубликованный релиз GitHub, найденный проверкой обновлений.
 *
 * [versionName] — поле `tag_name` (должно выглядеть как `versionName`
 * приложения, например `v1.1.0`), [releaseUrl] — `html_url` страницы релиза.
 */
data class LatestRelease(
    val versionName: String,
    val releaseUrl: String
)

/**
 * Сравнение версий «релиз» и «установлено». Чистые функции без Android —
 * покрыты JVM-тестами (`UpdateCheckerTest`).
 *
 * Правила:
 * - начальная `v`/`V` отбрасывается: `v1.2.0` = `1.2.0`;
 * - версия делится по точкам, сравниваются ЧИСЛОВЫЕ сегменты;
 * - отсутствующий сегмент считается нулём: `1.0` = `1.0.0`;
 * - суффикс после `-` или `+` игнорируется: `1.1.0-beta` = `1.1.0`,
 *   `1.1.0+build.7` = `1.1.0`;
 * - неразбираемая версия (пустая, буквы, пустой сегмент, огромное число) —
 *   «обновления нет»: [isNewer] возвращает `false`, [isParsable] — `false`,
 *   чтобы вызывающий записал это в лог.
 *
 * Функция НИКОГДА не бросает исключений.
 */
object UpdateVersion {

    /** Есть ли в [latest] версия новее [installed]. Любая непонятная версия — `false`. */
    fun isNewer(latest: String?, installed: String?): Boolean {
        val latestSegments = segments(latest) ?: return false
        val installedSegments = segments(installed) ?: return false
        val size = maxOf(latestSegments.size, installedSegments.size)
        for (index in 0 until size) {
            val latestPart = latestSegments.getOrElse(index) { 0L }
            val installedPart = installedSegments.getOrElse(index) { 0L }
            if (latestPart > installedPart) return true
            if (latestPart < installedPart) return false
        }
        return false
    }

    /** Разбирается ли версия (нужно только для честного лога). */
    fun isParsable(version: String?): Boolean = segments(version) != null

    /** Числовые сегменты версии или `null`, если версия не разбирается. */
    fun segments(version: String?): List<Long>? {
        val trimmed = version?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withoutPrefix = trimmed.removePrefix("v").removePrefix("V")
        val core = withoutPrefix.substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return null
        val parts = core.split('.')
        val result = ArrayList<Long>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || !part.all { it.isDigit() }) return null
            result += part.toLongOrNull() ?: return null
        }
        return result
    }
}

/**
 * Разбор ответа GitHub `GET /repos/{owner}/{repo}/releases/latest`.
 *
 * Берутся только два поля: `tag_name` и `html_url`. Черновики и пререлизы
 * отсекает сам GitHub (эндпоинт `/releases/latest`), дополнительных проверок
 * не нужно.
 *
 * Платформенный `org.json` в JVM-юнит-тестах — заглушка (все методы бросают),
 * поэтому используется собственный `MiniJson`, как в §11 правил проекта.
 * Чистая функция без Android; покрыта JVM-тестами.
 *
 * `null` — ответ не JSON, поля отсутствуют/не строки/пустые, либо `html_url`
 * не https. Исключений не бросает.
 */
object LatestReleaseParser {

    fun parse(json: String?): LatestRelease? {
        if (json.isNullOrBlank()) return null
        val root = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            return null
        }
        val obj = root as? JsonValue.Obj ?: return null
        val versionName = obj.fields["tag_name"]?.asStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val releaseUrl = obj.fields["html_url"]?.asStringOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: return null
        return LatestRelease(versionName = versionName, releaseUrl = releaseUrl)
    }
}

/**
 * Правило «не чаще одного раза в сутки». Чистая функция без Android.
 *
 * [lastCheckTimeMs] = 0 (проверки не было) или отрицательное — проверка нужна.
 * Если часы устройства ушли назад (`nowMs < lastCheckTimeMs`), проверка не
 * запускается: иначе неудачный запрос повторялся бы на каждом старте.
 */
object UpdateCheckInterval {

    const val INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    fun isDue(lastCheckTimeMs: Long, nowMs: Long): Boolean =
        lastCheckTimeMs <= 0L || nowMs - lastCheckTimeMs >= INTERVAL_MS
}

/**
 * Проверка обновлений по GitHub Releases (часть 1: только данные).
 *
 * - сеть: ТОЛЬКО `HttpURLConnection` (новых библиотек нет), только https;
 *   connect/read timeout по 10 с; заголовки `Accept: application/vnd.github+json`
 *   и `User-Agent`; блокирующие операции — на [ioDispatcher] (`Dispatchers.IO`);
 * - ответ больше 1 МБ обрезается и считается ошибкой;
 * - HTTP 404 (у репозитория нет релизов), нет сети, таймаут, не-200, битый
 *   JSON и любое исключение — `null` + строка в лог;
 * - метод НИКОГДА не бросает исключений вызывающему (кроме отмены корутины) и
 *   ничего не сохраняет: решение о записи принимает [UpdateRepository].
 */
class UpdateChecker(
    private val apiUrl: String = AppConfig.GITHUB_LATEST_RELEASE_API_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
    }
) {

    /** Последний релиз или `null` при ЛЮБОЙ ошибке (уже записана в лог). */
    suspend fun fetchLatestRelease(): LatestRelease? = withContext(ioDispatcher) {
        try {
            val body = requestBody()
            if (body == null) {
                null
            } else {
                LatestReleaseParser.parse(body) ?: run {
                    logger("Ответ GitHub не разобран (нет tag_name/html_url или битый JSON)", null)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("Проверка обновлений не удалась: ${e.message ?: e::class.java.simpleName}", e)
            null
        }
    }

    private fun requestBody(): String? {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code == HTTP_NOT_FOUND) {
                logger("У репозитория нет опубликованных релизов (HTTP 404) — обновления нет", null)
                return null
            }
            if (code != HTTP_OK) {
                logger("GitHub ответил HTTP $code — обновление не проверено", null)
                return null
            }
            connection.inputStream.use { stream ->
                return readLimited(stream)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_RESPONSE_BYTES) {
                logger("Ответ GitHub больше $MAX_RESPONSE_BYTES байт — пропущен", null)
                return null
            }
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val TIMEOUT_MS = 10_000
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val MAX_RESPONSE_BYTES = 1_000_000

        /** GitHub требует непустой User-Agent у анонимных запросов. */
        const val USER_AGENT = "VibraTuner-Android"
    }
}
