package com.personal.vibratuner.data.download

import java.io.File
import java.security.MessageDigest

/**
 * Локальные копии сетевых треков «От автора».
 *
 * С части 2 этого раунда НОВЫЕ файлы скачиваются в общую папку
 * `Download/VibraTuner/` через [MediaStoreDownloadStore]. Этот класс отвечает за:
 * - детерминированное имя файла по URL ([fileNameFor]) и MIME-тип ([mimeTypeFor]);
 * - СТАРЫЕ приватные файлы (`filesDir/downloads`), скачанные прошлой версией
 *   приложения: они остаются доступными для воспроизведения, пока не будут
 *   заменены публичной копией (сложная миграция не делается).
 *
 * Имя файла — детерминированная функция URL (SHA-256 + известное аудио-расширение),
 * поэтому отдельное поле «скачан» в Room НЕ нужно: признак загрузки — наличие
 * непустого файла (публичного или устаревшего приватного). Схема БД не меняется.
 *
 * Скачиваются ТОЛЬКО https-адреса; `content://` треки библиотеки остаются
 * на месте и не копируются.
 */
class TrackDownloadStore(private val directory: File) : DownloadedFileLayer {

    /** Файл, в который должен быть скачан [url]; `null` — URL не скачивается. */
    fun fileFor(url: String): File? {
        val name = fileNameFor(url) ?: return null
        return File(directory, name)
    }

    /** Временный файл рядом с целевым: сначала пишем в него, затем переименовываем. */
    fun tempFileFor(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

    /** Скачанный непустой файл для [url] или `null`, если его нет (играем из сети). */
    fun existingFile(url: String): File? {
        val file = fileFor(url) ?: return null
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Удаляет скачанный трек и его недописанный временный файл.
     * @return true, если готовый файл действительно был и удалён.
     */
    override fun delete(url: String): Boolean {
        val file = fileFor(url) ?: return false
        val temp = tempFileFor(file)
        val deleted = if (file.exists()) file.delete() else false
        if (temp.exists()) temp.delete()
        return deleted
    }

    /**
     * Есть ли ещё файл (готовый или недописанный) для [url]. Нужна проверке
     * после удаления: `delete()` мог не сработать, и это нельзя выдавать за успех.
     */
    override fun exists(url: String): Boolean {
        val file = fileFor(url) ?: return false
        val temp = tempFileFor(file)
        return file.exists() || temp.exists()
    }

    companion object {
        /** Подкаталог `filesDir`, в котором лежат скачанные треки. */
        const val DIRECTORY_NAME = "downloads"

        /** Суффикс недописанного файла: он никогда не считается скачанным треком. */
        const val PART_SUFFIX = ".part"

        private const val HTTPS_PREFIX = "https://"
        private const val FALLBACK_EXTENSION = "audio"
        private const val FALLBACK_MIME_TYPE = "application/octet-stream"

        /**
         * Расширения, которые Media3 понимает без дополнительного MIME.
         * Неизвестное расширение заменяется на нейтральное: содержимое
         * определяется по сигнатуре файла.
         */
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "webm", "mp4", "3gp"
        )

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Детерминированное имя файла для https-URL или `null` для не-https URI
         * (content://, file://): локальные файлы библиотеки не скачиваются.
         */
        fun fileNameFor(url: String): String? {
            val trimmed = url.trim()
            if (!trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(Charsets.UTF_8))
            val hash = buildString(digest.size * 2) {
                for (byte in digest) {
                    val value = byte.toInt() and 0xFF
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
            return "$hash.${extensionFor(trimmed)}"
        }

        private fun extensionFor(url: String): String {
            val withoutFragment = url.substringBefore('#').substringBefore('?')
            val fileName = withoutFragment.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return if (extension in AUDIO_EXTENSIONS) extension else FALLBACK_EXTENSION
        }

        /**
         * MIME-тип для MediaStore по расширению имени файла. Неизвестное
         * расширение получает нейтральный `application/octet-stream`: Media3
         * определяет формат по содержимому.
         */
        fun mimeTypeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a", "m4b", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                "3gp" -> "audio/3gpp"
                else -> FALLBACK_MIME_TYPE
            }

        /**
         * Совпадает ли имя файла с ожидаемым [expected] с учётом того, что
         * MediaStore при коллизии имён добавляет суффикс ` (1)`, ` (2)` и т.д.
         * Нужно и для поиска уже скачанного, и для удаления ВСЕХ копий при
         * удалении плейлиста — иначе переименованные копии оставались бы на
         * устройстве.
         */
        fun isSameFileName(candidate: String?, expected: String): Boolean {
            if (candidate == null) return false
            if (candidate == expected) return true
            val extension = expected.substringAfterLast('.', "")
            if (extension.isEmpty()) return false
            val stem = expected.removeSuffix(".$extension")
            if (!candidate.endsWith(".$extension")) return false
            // MediaStore обычно добавляет « (1)», но встречаются и варианты без
            // пробела; поддерживаем оба, чтобы не оставить копию при удалении.
            val prefix = when {
                candidate.startsWith("$stem (") -> "$stem ("
                candidate.startsWith("$stem(") -> "$stem("
                else -> return false
            }
            val suffixEnd = candidate.length - extension.length - 2
            if (suffixEnd <= prefix.length) return false
            return candidate.substring(prefix.length, suffixEnd).all { it.isDigit() }
        }
    }
}
