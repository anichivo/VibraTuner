package com.personal.vibratuner.data.remote

import java.net.URI
import java.net.URISyntaxException

/**
 * Разрешение необязательного поля `icon` плейлиста каталога «От автора».
 *
 * Значение поля может быть:
 * - абсолютным https-адресом PNG (`https://.../icon.png`);
 * - относительным путём от места, где лежит манифест (`icons/icon.png`,
 *   `./icons/icon.png`, `../shared/icon.png`).
 *
 * Относительный путь разрешается по RFC 3986 от каталога манифеста
 * (`java.net.URI.resolve`): для `.../main/manifest.json` и `icons/a.png`
 * получается `.../main/icons/a.png`. Принимается только результат со схемой
 * https: `http`, `content://`, `file://`, `data:` и прочие схемы НИКОГДА не
 * используются — приложение не качает иконки по небезопасным схемам.
 *
 * Функция чистая (без Android-зависимостей) и покрыта JVM-тестами.
 */
object CatalogIconUrl {

    private const val HTTPS_PREFIX = "https://"

    /**
     * Абсолютный https-адрес иконки или `null`, если ссылку использовать
     * нельзя (пусто, не https, битый URI без базы, нет адреса манифеста).
     */
    fun resolve(reference: String?, manifestUrl: String?): String? {
        val trimmed = reference?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Ссылка со собственной схемой (в т.ч. «C:») не зависит от манифеста.
        if (hasScheme(trimmed)) return requireHttps(trimmed)

        val base = manifestUrl?.trim().orEmpty()
        if (base.isEmpty()) return null
        val baseUri = try {
            URI(base)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!baseUri.scheme.equals("https", ignoreCase = true)) return null
        val relative = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        val resolved = try {
            baseUri.resolve(relative).toString()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return requireHttps(resolved)
    }

    /** Есть ли у ссылки собственная схема (`https:`, `data:`, `C:` и т.п.). */
    private fun hasScheme(reference: String): Boolean {
        val colon = reference.indexOf(':')
        if (colon <= 0) return false
        val scheme = reference.substring(0, colon)
        if (!scheme[0].isLetter()) return false
        return scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    /** Оставляем только абсолютный https-адрес с непустым хостом. */
    private fun requireHttps(value: String): String? {
        if (!value.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return value
    }
}
