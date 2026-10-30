package com.personal.vibratuner.data.local.db

/**
 * Проверка имени плейлиста. Чистая функция без Android-зависимостей:
 * пробелы по краям отбрасываются, пустое имя и имена длиннее [MAX_NAME_LENGTH]
 * отклоняются (null). Одинаковые имена разрешены.
 */
object PlaylistNameValidator {

    const val MAX_NAME_LENGTH = 50

    fun validate(rawName: String): String? {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_NAME_LENGTH) return null
        return trimmed
    }
}
