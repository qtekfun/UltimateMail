// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.backup

import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.domain.backup.NoPendingFolderChoices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreferencePendingFolderChoicesTest {
    private val store = FakePreferenceStore()
    private val choices = PreferencePendingFolderChoices(store)

    @Test
    fun `choices are kept per account, with awkward paths`() {
        choices.save(1, mapOf("INBOX" to true, "Work/\"Q1\" \\ ñ" to false))
        choices.save(2, mapOf("Other" to false))

        assertEquals(mapOf("INBOX" to true, "Work/\"Q1\" \\ ñ" to false), choices.peek(1))
        assertEquals(mapOf("Other" to false), choices.peek(2))
        assertTrue(choices.peek(3).isEmpty())
    }

    @Test
    fun `saving again replaces and clearing forgets`() {
        choices.save(1, mapOf("A" to true))
        choices.save(1, mapOf("B" to false))
        assertEquals(mapOf("B" to false), choices.peek(1))

        choices.clear(1)

        assertTrue(choices.peek(1).isEmpty())
    }

    @Test
    fun `a damaged or foreign value counts as nothing waiting`() {
        store.putString("pending_folder_sync_1", "{not json")
        store.putString("pending_folder_sync_2", "[1,2]")
        store.putString("pending_folder_sync_3", "{\"a\":1,\"b\":true}")

        assertTrue(choices.peek(1).isEmpty())
        assertTrue(choices.peek(2).isEmpty())
        assertEquals(mapOf("b" to true), choices.peek(3))
    }

    @Test
    fun `the no-op implementation keeps nothing`() {
        NoPendingFolderChoices.save(1, mapOf("A" to true))
        NoPendingFolderChoices.clear(1)

        assertTrue(NoPendingFolderChoices.peek(1).isEmpty())
    }
}
