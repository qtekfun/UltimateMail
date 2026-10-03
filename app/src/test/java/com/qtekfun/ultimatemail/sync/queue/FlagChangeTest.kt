// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FlagChangeTest {
    @Test
    fun `every combination survives encoding`() {
        val values = listOf(true, false, null)
        for (seen in values) {
            for (flagged in values) {
                val change = FlagChange(seen, flagged)
                assertEquals(change, FlagChange.decode(change.encode()))
            }
        }
    }

    @Test
    fun `encoding is two characters`() {
        assertEquals("10", FlagChange(seen = true, flagged = false).encode())
        assertEquals("--", FlagChange().encode())
    }

    @Test
    fun `the later change wins where it says something`() {
        val merged = FlagChange(seen = false).over(FlagChange(seen = true, flagged = true))

        assertEquals(FlagChange(seen = false, flagged = true), merged)
    }

    @Test
    fun `the earlier change stays where the later one is silent`() {
        assertEquals(
            FlagChange(seen = true),
            FlagChange(flagged = null).over(FlagChange(seen = true))
        )
        assertEquals(FlagChange(flagged = false), FlagChange().over(FlagChange(flagged = false)))
    }

    @Test
    fun `rejects a payload of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { FlagChange.decode("1") }
    }

    @Test
    fun `rejects an unknown flag code`() {
        assertThrows(IllegalArgumentException::class.java) { FlagChange.decode("1x") }
    }
}
