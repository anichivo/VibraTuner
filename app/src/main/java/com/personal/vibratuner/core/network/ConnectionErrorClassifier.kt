package com.personal.vibratuner.core.network

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * ЕДИНАЯ чистая классификация «это сбой соединения?» для встроенных онлайн-
 * плейлистов (каталог «От автора» и его треки): скачивание и воспроизведение
 * сетевого потока. Сообщение об ошибке соединения показывается ТОЛЬКО по
 * результату [isConnectionError]; локальные файлы пользователя, отмена
 * пользователем и не-сетевые ошибки сюда не попадают.
 *
 * Функция намеренно не знает ни Android, ни UI: её можно покрыть юнит-тестами.
 */
object ConnectionErrorClassifier {

    /** Полуинтервал успешных HTTP-кодов: всё остальное — сбой сервера/сети. */
    private val HTTP_SUCCESS_RANGE = 200..299

    /**
     * Один сбой соединения по любому доступному признаку.
     *
     * @param cause исключение пути загрузки или причина [PlaybackException];
     * @param httpStatusCode фактический HTTP-код ответа сервера (скачивание);
     * @param playbackErrorCode код ошибки Media3 (`PlaybackException.errorCode`).
     * @return true — сообщение об ошибке соединения показывать можно.
     */
    fun isConnectionError(
        cause: Throwable? = null,
        httpStatusCode: Int? = null,
        playbackErrorCode: Int? = null
    ): Boolean {
        if (playbackErrorCode != null && isNetworkPlaybackCode(playbackErrorCode)) return true
        if (httpStatusCode != null && httpStatusCode !in HTTP_SUCCESS_RANGE) return true
        return hasNetworkCause(cause)
    }

    /**
     * Сетевой ли это поток. Истинно ТОЛЬКО для http/https: локальные файлы
     * пользователя (`content://`, `file://`) и встроенные ресурсы — не сеть.
     */
    fun isRemoteStream(uri: String?): Boolean {
        val value = uri?.trim().orEmpty()
        return value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
    }

    /** Коды Media3, которые означают именно сеть или HTTP-ответ сервера. */
    private fun isNetworkPlaybackCode(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> true
        else -> false
    }

    /** Ищем сетевую причину по всей цепочке `cause` (не глубже [MAX_CAUSE_DEPTH]). */
    private fun hasNetworkCause(cause: Throwable?): Boolean {
        var current = cause
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (isNetworkThrowable(current)) return true
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return false
    }

    private fun isNetworkThrowable(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is SocketTimeoutException,
        is SocketException,
        is SSLException -> true
        // Обрыв чтения сетевого потока приходит и «обычным» IOException.
        // Локальные ошибки записи до этого места практически не доходят:
        // файл MediaStore открывается и публикуется отдельными вызовами.
        is IOException -> true
        else -> false
    }

    /** Страховка от зацикленных цепочек `cause`. */
    private const val MAX_CAUSE_DEPTH = 8
}
