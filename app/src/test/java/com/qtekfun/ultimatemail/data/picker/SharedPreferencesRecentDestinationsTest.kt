// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.picker

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedPreferencesRecentDestinationsTest {
    private val stored = mutableMapOf<String, String>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val preferences = mockk<SharedPreferences>()

    init {
        every { preferences.getString(any(), any()) } answers { stored[firstArg()] }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just runs
    }

    private fun store() = SharedPreferencesRecentDestinations(preferences)

    @Test
    fun `nothing is remembered at first`() {
        assertTrue(store().recent(1).isEmpty())
        assertTrue(store().usage(1).isEmpty())
    }

    @Test
    fun `what was recorded is read back, most recent first, by a new store too`() {
        store().record(1, listOf("Work/Invoices"))
        store().record(1, listOf("Archive", "Work/Invoices"))

        val again = store()

        assertEquals(listOf("Work/Invoices", "Archive"), again.recent(1))
        assertEquals(mapOf("Work/Invoices" to 2, "Archive" to 1), again.usage(1))
    }

    @Test
    fun `each account has its own history`() {
        store().record(1, listOf("A"))
        store().record(2, listOf("B"))

        assertEquals(listOf("A"), store().recent(1))
        assertEquals(listOf("B"), store().recent(2))
    }

    @Test
    fun `recording nothing writes nothing`() {
        store().record(1, emptyList())

        verify(exactly = 0) { editor.putString(any(), any()) }
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a damaged stored value is ignored`() {
        stored["recent.1"] = "not\u0000a log"

        assertTrue(store().recent(1).isEmpty())
    }
}
