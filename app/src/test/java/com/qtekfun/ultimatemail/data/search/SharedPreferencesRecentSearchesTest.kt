// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.search

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedPreferencesRecentSearchesTest {
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
        every { editor.remove(any()) } answers {
            stored.remove(firstArg<String>())
            editor
        }
        every { editor.apply() } just runs
    }

    private fun store() = SharedPreferencesRecentSearches(preferences)

    @Test
    fun `nothing is remembered at first`() {
        assertTrue(store().recent().isEmpty())
    }

    @Test
    fun `searches are read back newest first by a new store too`() {
        store().record("invoice")
        store().record("from:ana")

        assertEquals(listOf("from:ana", "invoice"), store().recent())
    }

    @Test
    fun `a repeated search is not duplicated`() {
        store().record("invoice")
        store().record("INVOICE")

        assertEquals(listOf("INVOICE"), store().recent())
    }

    @Test
    fun `removes one search`() {
        store().record("a")
        store().record("b")

        store().remove("a")

        assertEquals(listOf("b"), store().recent())
    }

    @Test
    fun `clear forgets everything and leaves nothing stored`() {
        store().record("a")

        store().clear()

        assertTrue(store().recent().isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `only the text of the search is stored`() {
        store().record("  big   news ")

        assertEquals(mapOf("recent" to "big news"), stored)
    }
}
