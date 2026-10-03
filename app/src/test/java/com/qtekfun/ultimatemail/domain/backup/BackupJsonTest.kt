// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BackupJsonTest {
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(mapOf(*fields))

    @Test
    fun `a document survives writing and reading`() {
        val value = obj(
            "text" to JsonValue.Str("quote \" backslash \\ newline \n tab \t bell \u0007 ñ 日本"),
            "numbers" to JsonValue.Arr(listOf(JsonValue.Num(0), JsonValue.Num(-42))),
            "flags" to obj("yes" to JsonValue.Bool(true), "no" to JsonValue.Bool(false)),
            "nothing" to JsonValue.Null,
            "empty" to JsonValue.Arr(emptyList()),
            "none" to obj()
        )

        assertEquals(value, BackupJson.parse(BackupJson.write(value)))
    }

    @Test
    fun `whitespace and escapes of other writers are understood`() {
        val parsed = BackupJson.parse(" {\n \"a\" : [ 1 , \"\\u0041\\/\\b\\f\\r\" ] } ")

        assertEquals(
            obj("a" to JsonValue.Arr(listOf(JsonValue.Num(1), JsonValue.Str("A/\b\u000C\r")))),
            parsed
        )
    }

    @Test
    fun `strings never appear in the printed form of a value`() {
        assertFalse(JsonValue.Str("hunter2").toString().contains("hunter2"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", "{", "[1,", "{\"a\":}", "{\"a\" 1}", "{a:1}", "[1 2]", "tru", "nul",
            "\"unterminated", "\"bad \\q escape\"", "\"\\u12G4\"", "\"\\u12\"", "1.5", "1e3",
            "01", "-", "--1", "9999999999999999999", "{\"a\":1} extra", "[]]",
            "{\"a\":1,\"a\":2}", "\"tab\there\"", "{\"a\":1,}"
        ]
    )
    fun `invalid or unacceptable text is refused with a typed error`(text: String) {
        assertThrows(JsonFormatException::class.java) { BackupJson.parse(text) }
    }

    @Test
    fun `deeply nested input is refused instead of overflowing the stack`() {
        val deep = "[".repeat(5_000) + "]".repeat(5_000)

        assertThrows(JsonFormatException::class.java) { BackupJson.parse(deep) }
    }

    @Test
    fun `nesting up to the limit is fine`() {
        val ok = "[".repeat(16) + "]".repeat(16)

        BackupJson.parse(ok)
    }

    @Test
    fun `the error message does not repeat the content`() {
        val error = assertThrows(JsonFormatException::class.java) {
            BackupJson.parse("{\"password\":\"hunter2\" oops}")
        }

        assertFalse(error.message.orEmpty().contains("hunter2"))
    }
}
