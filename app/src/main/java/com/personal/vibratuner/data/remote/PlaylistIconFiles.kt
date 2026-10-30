package com.personal.vibratuner.data.remote

import java.io.File
import java.security.MessageDigest

/**
 * Правила хранения и проверки PNG-иконок плейлистов каталога (Stage 2).
 *
 * Имя файла кеша — SHA-256 (hex, строчные буквы) от РАЗРЕШЁННОГО https-адреса
 * иконки плюс расширение `.png`. Следствия:
 * - один адрес — ровно один файл; повторное скачивание перезаписывает его;
 * - чтобы иконка обновилась, автор каталога ОБЯЗАН положить новый файл по
 *   НОВОМУ адресу (изменив имя файла в репозитории): содержимое по тому же
 *   адресу приложение не перекачивает;
 * - имя не зависит от входной строки и не может выйти за пределы каталога кеша
 *   (никаких `..` и разделителей пути).
 *
 * Здесь только чистые функции (хэш, сигнатура, лимит размера), поэтому класс
 * пригоден для JVM-юнит-тестов без Android.
 */
object PlaylistIconFiles {

    /** Подкаталог `cacheDir` приложения с иконками каталога. */
    const val DIRECTORY_NAME = "playlist_icons"

    /** Предел размера одной иконки: 256 КБ. */
    const val MAX_ICON_BYTES = 256 * 1024

    /** Суффикс недописанного файла: он НИКОГДА не считается готовой иконкой. */
    const val TEMP_SUFFIX = ".tmp"

    private const val EXTENSION = "png"
    private const val HTTPS_PREFIX = "https://"
    private const val PNG_SIGNATURE_LENGTH = 8

    /** Первые 8 байт настоящего PNG (RFC 2083): `89 50 4E 47 0D 0A 1A 0A`. */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50.toByte(),
        0x4E.toByte(),
        0x47.toByte(),
        0x0D.toByte(),
        0x0A.toByte(),
        0x1A.toByte(),
        0x0A.toByte()
    )

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Имя файла кеша для разрешённого адреса иконки; `null` — ссылка не https
     * или пустая, кешировать её нельзя.
     */
    fun fileNameFor(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
        val name = buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return "$name.$EXTENSION"
    }

    /** Файл иконки внутри [directory]; `null` — ссылку не кешируем. */
    fun fileFor(directory: File, url: String): File? =
        fileNameFor(url)?.let { name -> File(directory, name) }

    /** Размер в допустимых границах: 0 байт и больше 256 КБ — не иконка. */
    fun isWithinSizeLimit(bytes: Long): Boolean = bytes in 1..MAX_ICON_BYTES.toLong()

    /** Начинается ли массив с 8-байтной сигнатуры PNG. */
    fun isPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE_LENGTH) return false
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) return false
        }
        return true
    }

    /** Начинается ли файл с сигнатуры PNG; ошибки чтения — `false`. */
    fun isPngFile(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            val header = ByteArray(PNG_SIGNATURE_LENGTH)
            val read = file.inputStream().use { input -> input.read(header) }
            if (read < PNG_SIGNATURE_LENGTH) {
                false
            } else {
                isPngSignature(header)
            }
        } catch (e: Exception) {
            false
        }
    }
}
