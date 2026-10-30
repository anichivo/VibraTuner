package com.personal.vibratuner.audio.microsound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Читает фактическую длительность файла, выбранного для «Включения колонки»
 * или для фейк-включения. Понимает и `builtin:<id>` (файл из `res/raw`), и
 * пользовательские URI. Используется только системный MediaMetadataRetriever,
 * без новых библиотек. Любая ошибка/неизвестная длительность означает 0:
 * звук пропускается, а основной будильник на T остаётся запланированным.
 */
object SpeakerOnDurationReader {
    private const val TAG = "SpeakerOnDuration"

    suspend fun readDurationMs(context: Context, uri: String): Long {
        if (uri.isBlank()) return 0L
        return withContext(Dispatchers.IO) {
            try {
                readDurationBlocking(context, uri)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось прочитать длительность звука $uri: ${e.message}", e)
                0L
            }
        }
    }

    private fun readDurationBlocking(context: Context, uri: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val builtin = BuiltinMicrosoundCatalog.findByStored(uri)
            if (builtin != null) {
                // Встроенный звук лежит в res/raw: открываем его как файл, чтобы
                // MediaMetadataRetriever не зависел от схемы URI.
                val descriptor = context.resources.openRawResourceFd(builtin.rawRes)
                if (descriptor == null) {
                    Log.w(TAG, "Встроенный звук $uri недоступен в res/raw — длительность неизвестна")
                    return 0L
                }
                descriptor.use { afd ->
                    retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } else {
                retriever.setDataSource(context, Uri.parse(uri))
            }
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = raw?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                Log.w(TAG, "Длительность звука $uri неизвестна — звук будет пропущен")
                0L
            } else {
                durationMs
            }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка освобождения MediaMetadataRetriever: ${e.message}")
            }
        }
    }
}
