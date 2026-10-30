package com.personal.vibratuner.data.local.db

/**
 * Единая нормализация URI трека для проверки уникальности.
 * Схема URI регистронезависима, поэтому приводится к нижнему регистру;
 * пробелы по краям отбрасываются.
 */
object TrackUriNormalizer {

    fun normalize(uri: String): String {
        val trimmed = uri.trim()
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex <= 0) return trimmed
        val scheme = trimmed.substring(0, colonIndex)
        val isScheme = scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        return if (isScheme) {
            scheme.lowercase() + trimmed.substring(colonIndex)
        } else {
            trimmed
        }
    }
}
