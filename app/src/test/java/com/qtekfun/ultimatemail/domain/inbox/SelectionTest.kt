// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectionTest {
    private val empty = Selection("folder/1/INBOX")

    @Test
    fun `nothing is selected at first and the mode is off`() {
        assertFalse(empty.active)
        assertEquals(0, empty.count)
    }

    @Test
    fun `the first pick enters selection mode and the last unpick leaves it`() {
        val one = empty.toggle("a")
        assertTrue(one.active)
        assertTrue("a" in one)

        val none = one.toggle("a")
        assertFalse(none.active)
    }

    @Test
    fun `taps toggle each conversation on its own`() {
        val picked = empty.toggle("a").toggle("b").toggle("a")

        assertEquals(setOf("b"), picked.keys)
    }

    @Test
    fun `select all picks everything shown`() {
        val picked = empty.toggle("a").selectAll(listOf("a", "b", "c"))

        assertEquals(setOf("a", "b", "c"), picked.keys)
        assertEquals(3, picked.count)
    }

    @Test
    fun `clearing keeps the list but drops the picks`() {
        val cleared = empty.toggle("a").clear()

        assertFalse(cleared.active)
        assertEquals("folder/1/INBOX", cleared.scopeKey)
    }

    @Test
    fun `another list starts with nothing selected, the same list keeps its picks`() {
        val picked = empty.toggle("a")

        assertSame(picked, picked.forScope("folder/1/INBOX"))
        val other = picked.forScope("unified")
        assertFalse(other.active)
        assertEquals("unified", other.scopeKey)
    }

    @Test
    fun `conversations that disappear are dropped from the selection`() {
        val picked = empty.selectAll(listOf("a", "b", "c"))

        val kept = picked.retain(setOf("b", "c", "d"))

        assertEquals(setOf("b", "c"), kept.keys)
        assertFalse(picked.retain(setOf("x")).active)
    }

    @Test
    fun `retaining when nothing vanished gives the same selection back`() {
        val picked = empty.toggle("a")

        assertSame(picked, picked.retain(setOf("a", "b")))
    }

    @Test
    fun `a selection rebuilt from what was saved is the same`() {
        val picked = empty.selectAll(listOf("a", "b"))

        val restored = Selection(picked.scopeKey, ArrayList(picked.keys).toSet())

        assertEquals(picked, restored)
    }

    @Test
    fun `the picked items come back in list order`() {
        val items = listOf(
            rowItem(threadId = "x"),
            rowItem(threadId = "y"),
            rowItem(threadId = "z")
        )
        val picked = empty.toggle(items[2].key).toggle(items[0].key)

        assertEquals(listOf("x", "z"), picked.pick(items).map { it.threadId })
    }

    @Test
    fun `Edit turns the mode on with nothing picked and it stays on when the last pick goes`() {
        val editing = empty.startEditing()
        assertTrue(editing.active)
        assertEquals(0, editing.count)

        val none = editing.toggle("a").toggle("a")
        assertTrue(none.active)
    }

    @Test
    fun `clear, which is Done, leaves the mode even after Edit`() {
        val done = empty.startEditing().toggle("a").clear()

        assertFalse(done.active)
        assertEquals(0, done.count)
    }

    @Test
    fun `a selection of another list is not editing`() {
        val other = empty.startEditing().forScope("folder/2/INBOX")

        assertFalse(other.active)
    }
}
