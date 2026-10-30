package com.personal.vibratuner.data.remote

/**
 * Дерево JSON, которое отдаёт [MiniJson].
 *
 * Платформенный `org.json` в JVM-юнит-тестах — заглушка (все методы бросают
 * `RuntimeException`), поэтому разбор манифеста «От автора» идёт через этот
 * минимальный ридер: он чистый, покрыт юнит-тестами и не требует библиотек.
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    /** Строковое значение или null, если узел не строка. */
    fun asStringOrNull(): String? = (this as? Str)?.value
}

/** Ошибка разбора JSON: текст пригоден для лога, без стектрейса. */
class JsonParseException(message: String) : Exception(message)

/**
 * Минимальный рекурсивный парсер JSON (RFC 8259) без зависимостей:
 * объекты, массивы, строки с escape-последовательностями, числа, true/false/null.
 * Дубликаты ключей объекта — побеждает последнее значение (как в `JSONObject`).
 */
object MiniJson {

    /** Защита от переполнения стека на враждебном ответе. */
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("лишние данные после JSON")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun fail(message: String): Nothing =
            throw JsonParseException("$message (позиция $pos)")

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("слишком глубокая вложенность")
            skipWhitespace()
            if (atEnd()) fail("неожиданный конец JSON")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.Null
                }
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("неожиданный символ '$c'")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') fail("ожидался ключ объекта")
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("незакрытый объект")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("ожидалась ',' или '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("незакрытый массив")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("ожидалась ',' или ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) fail("незакрытая строка")
                when (val c = text[pos++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscape())
                    else -> {
                        if (c < ' ') fail("недопустимый управляющий символ в строке")
                        builder.append(c)
                    }
                }
            }
        }

        private fun parseEscape(): Char {
            if (atEnd()) fail("незавершённая escape-последовательность")
            return when (val escaped = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> fail("неизвестная escape-последовательность \\$escaped")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (pos + 4 > text.length) fail("недостаточно символов в \\u")
            val hex = text.substring(pos, pos + 4)
            val isHex = hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (!isHex) fail("некорректный \\u$hex")
            pos += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            if (atEnd()) fail("некорректное число")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos].isDigit()) {
                while (!atEnd() && text[pos].isDigit()) pos++
            } else {
                fail("некорректное число")
            }
            if (!atEnd() && text[pos] == '.') {
                pos++
                requireDigits()
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                requireDigits()
            }
            val raw = text.substring(start, pos)
            return JsonValue.Num(raw.toDoubleOrNull() ?: fail("некорректное число $raw"))
        }

        private fun requireDigits() {
            if (atEnd() || !text[pos].isDigit()) fail("некорректное число")
            while (!atEnd() && text[pos].isDigit()) pos++
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, pos)) fail("ожидалось $literal")
            pos += literal.length
        }

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) fail("ожидался '$c'")
            pos++
        }
    }
}
