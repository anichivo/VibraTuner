package com.personal.vibratuner.core.network

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единая классификация сбоев соединения: по ней показывается сообщение
 * «Ошибка соединения…». Ошибка не-сетевого характера НИКОГДА не даёт true,
 * иначе подсказка про VPN появлялась бы на пустом месте.
 */
class ConnectionErrorClassifierTest {

    @Test
    fun downloadNetworkExceptionsAreConnectionErrors() {
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = UnknownHostException("bad.host")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = SocketTimeoutException("timeout")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = ConnectException("refused")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = SSLHandshakeException("ssl")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = IOException("connection reset")))
    }

    @Test
    fun nestedNetworkCauseIsDetected() {
        val wrapped = RuntimeException("download failed", UnknownHostException("dns"))
        assertTrue(ConnectionErrorClassifier.isConnectionError(cause = wrapped))
    }

    @Test
    fun non2xxHttpStatusIsConnectionError() {
        assertTrue(ConnectionErrorClassifier.isConnectionError(httpStatusCode = 404))
        assertTrue(ConnectionErrorClassifier.isConnectionError(httpStatusCode = 500))
        assertFalse(ConnectionErrorClassifier.isConnectionError(httpStatusCode = 200))
        assertFalse(ConnectionErrorClassifier.isConnectionError(httpStatusCode = 204))
    }

    @Test
    fun playbackNetworkAndHttpCodesAreConnectionErrors() {
        assertTrue(
            ConnectionErrorClassifier.isConnectionError(
                playbackErrorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
        assertTrue(
            ConnectionErrorClassifier.isConnectionError(
                playbackErrorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        )
        assertTrue(
            ConnectionErrorClassifier.isConnectionError(
                playbackErrorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            )
        )
    }

    @Test
    fun nonNetworkErrorsAreNotConnectionErrors() {
        // Ошибка разбора/декодера, отсутствие файла и вообще отсутствие ошибки.
        assertFalse(ConnectionErrorClassifier.isConnectionError())
        assertFalse(ConnectionErrorClassifier.isConnectionError(cause = IllegalArgumentException("bad codec")))
        assertFalse(
            ConnectionErrorClassifier.isConnectionError(
                playbackErrorCode = PlaybackException.ERROR_CODE_DECODING_FAILED
            )
        )
        assertFalse(
            ConnectionErrorClassifier.isConnectionError(
                playbackErrorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            )
        )
    }

    @Test
    fun onlyHttpStreamsAreRemote() {
        assertTrue(ConnectionErrorClassifier.isRemoteStream("https://example.com/a.mp3"))
        assertTrue(ConnectionErrorClassifier.isRemoteStream("HTTP://example.com/a.mp3"))
        assertFalse(ConnectionErrorClassifier.isRemoteStream("content://media/1"))
        assertFalse(ConnectionErrorClassifier.isRemoteStream("file:///data/a.mp3"))
        assertFalse(ConnectionErrorClassifier.isRemoteStream(null))
        assertFalse(ConnectionErrorClassifier.isRemoteStream(""))
    }
}
