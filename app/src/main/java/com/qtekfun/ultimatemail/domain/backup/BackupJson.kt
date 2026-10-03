// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

/** A JSON value. Only what the backup needs: numbers are whole numbers. */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue {
        // Strings may hold passwords: never print them by accident.
        override fun toString(): String = "Str(redacted)"
    }

    data class Num(val value: Long) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** The text is not valid (or not acceptable) JSON; the message never repeats the content. */
class JsonFormatException(message: String) : Exception(message)

/**
 * A small, strict JSON reader and writer, hand written because `org.json` is not available in
 * the JVM unit tests and no serialization library is a dependency. The reader never trusts its
 * input: nesting depth is limited, duplicate keys, fractional numbers and trailing text are
 * refused, and every error is a [JsonFormatException] (never a crash or a runaway loop).
 */
object BackupJson {
    private const val MAX_DEPTH = 16
    private const val MAX_DIGITS = 18
    private const val HEX_RADIX = 16
    private const val UNICODE_DIGITS = 4
    private const val LAST_CONTROL = 0x1F

    fun parse(text: String): JsonValue = Reader(text).readDocument()

    fun write(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.entries.forEachIndexed { index, (key, field) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(field, out)
                }
                out.append('}')
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonValue.Str -> writeString(value.value, out)

            is JsonValue.Num -> out.append(value.value)

            is JsonValue.Bool -> out.append(value.value)

            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")

                char == '\\' -> out.append("\\\\")

                char == '\n' -> out.append("\\n")

                char == '\r' -> out.append("\\r")

                char == '\t' -> out.append("\\t")

                char.code <= LAST_CONTROL ->
                    out.append("\\u").append(char.code.toString(HEX_RADIX).padStart(4, '0'))

                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun readDocument(): JsonValue {
            val value = readValue(0)
            skipWhitespace()
            if (pos != text.length) fail("trailing text")
            return value
        }

        private fun fail(reason: String): Nothing = throw JsonFormatException(reason)

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        private fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end")
            return when (text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) fail("unexpected token")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): JsonValue {
            pos++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a key")
                val key = readString()
                if (key in fields) fail("duplicate key")
                skipWhitespace()
                expect(':')
                fields[key] = readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(fields)
                    else -> fail("expected , or }")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue {
            pos++
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return JsonValue.Arr(items)
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun next(): Char {
            val char = peek() ?: fail("unexpected end")
            pos++
            return char
        }

        private fun expect(char: Char) {
            if (next() != char) fail("expected a different character")
        }

        private fun readNumber(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            val digitsStart = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            val digits = pos - digitsStart
            if (digits == 0 || digits > MAX_DIGITS) fail("bad number")
            if (digits > 1 && text[digitsStart] == '0') fail("bad number")
            if (peek().let { it == '.' || it == 'e' || it == 'E' }) fail("not a whole number")
            return JsonValue.Num(text.substring(start, pos).toLong())
        }

        private fun readString(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(readEscape())
                    char.code <= LAST_CONTROL -> fail("control character in string")
                    else -> out.append(char)
                }
            }
        }

        private fun readEscape(): Char = when (val code = next()) {
            '"', '\\', '/' -> code
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicode()
            else -> fail("bad escape")
        }

        private fun readUnicode(): Char {
            if (pos + UNICODE_DIGITS > text.length) fail("bad escape")
            val hex = text.substring(pos, pos + UNICODE_DIGITS)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad escape")
            pos += UNICODE_DIGITS
            return hex.toInt(HEX_RADIX).toChar()
        }
    }
}
