// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `instants round trip as epoch milliseconds`() {
        val instant = Instant.ofEpochMilli(123_456)

        assertEquals(123_456L, converters.instantToMillis(instant))
        assertEquals(instant, converters.millisToInstant(123_456))
        assertNull(converters.instantToMillis(null))
        assertNull(converters.millisToInstant(null))
    }

    @Test
    fun `string lists round trip, including the empty list and separators inside text`() {
        val values = listOf("a@x.test", "Work/Invoices", "with, comma")

        assertEquals(values, converters.textToStrings(converters.stringsToText(values)))
        assertEquals(
            emptyList<String>(),
            converters.textToStrings(converters.stringsToText(emptyList()))
        )
    }
}
