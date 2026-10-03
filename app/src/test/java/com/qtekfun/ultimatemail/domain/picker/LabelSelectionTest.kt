// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabelSelectionTest {
    private val selection = LabelSelection.of(
        listOf(setOf("A", "B"), setOf("A"), setOf("A", "C"))
    )

    @Test
    fun `a label all messages have starts checked`() {
        assertEquals(CheckState.CHECKED, selection.stateOf("A"))
    }

    @Test
    fun `a label only some messages have starts partial`() {
        assertEquals(CheckState.PARTIAL, selection.stateOf("B"))
        assertEquals(CheckState.PARTIAL, selection.stateOf("C"))
    }

    @Test
    fun `a label no message has starts unchecked`() {
        assertEquals(CheckState.UNCHECKED, selection.stateOf("Z"))
    }

    @Test
    fun `nothing changed at the start`() {
        assertFalse(selection.changed)
        assertTrue(selection.changes().isEmpty)
    }

    @Test
    fun `tapping a partial label checks it for every message`() {
        val next = selection.toggle("B")

        assertEquals(CheckState.CHECKED, next.stateOf("B"))
        assertEquals(LabelChanges(add = setOf("B"), remove = emptySet()), next.changes())
        assertTrue(next.changed)
    }

    @Test
    fun `tapping a partial label twice clears it for every message`() {
        val next = selection.toggle("B").toggle("B")

        assertEquals(CheckState.UNCHECKED, next.stateOf("B"))
        assertEquals(LabelChanges(add = emptySet(), remove = setOf("B")), next.changes())
    }

    @Test
    fun `tapping a checked label clears it, and tapping again is back where it started`() {
        val cleared = selection.toggle("A")
        assertEquals(CheckState.UNCHECKED, cleared.stateOf("A"))
        assertEquals(setOf("A"), cleared.changes().remove)

        val back = cleared.toggle("A")
        assertEquals(CheckState.CHECKED, back.stateOf("A"))
        assertTrue(back.changes().isEmpty)
        assertFalse(back.changed)
    }

    @Test
    fun `tapping an unchecked label checks it, and tapping again is back where it started`() {
        val added = selection.toggle("Z")
        assertEquals(CheckState.CHECKED, added.stateOf("Z"))
        assertEquals(setOf("Z"), added.changes().add)

        val back = added.toggle("Z")
        assertEquals(CheckState.UNCHECKED, back.stateOf("Z"))
        assertFalse(back.changed)
    }

    @Test
    fun `changes collect additions and removals together`() {
        val next = selection.toggle("A").toggle("Z").toggle("Y").toggle("B")

        assertEquals(
            LabelChanges(add = setOf("Z", "Y", "B"), remove = setOf("A")),
            next.changes()
        )
    }

    @Test
    fun `a selection is not changed by the toggles made from it`() {
        selection.toggle("A")

        assertEquals(CheckState.CHECKED, selection.stateOf("A"))
    }

    @Test
    fun `with one message a label is either checked or unchecked`() {
        val one = LabelSelection.of(listOf(setOf("A")))

        assertEquals(CheckState.CHECKED, one.stateOf("A"))
        assertEquals(CheckState.UNCHECKED, one.stateOf("B"))
    }

    @Test
    fun `with no messages every label is unchecked`() {
        assertEquals(CheckState.UNCHECKED, LabelSelection.of(emptyList()).stateOf("A"))
    }
}
