// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FolderExpansionTest {
    @Test
    fun `everything starts collapsed`() {
        assertTrue(FolderExpansion().pathsOf(1).isEmpty())
    }

    @Test
    fun `toggling opens a parent and toggling again closes it`() {
        val open = FolderExpansion().toggle(1, "Work")
        assertEquals(setOf("Work"), open.pathsOf(1))

        assertTrue(open.toggle(1, "Work").pathsOf(1).isEmpty())
    }

    @Test
    fun `accounts do not share open parents`() {
        val expansion = FolderExpansion().toggle(1, "Work").toggle(2, "Home")

        assertEquals(setOf("Work"), expansion.pathsOf(1))
        assertEquals(setOf("Home"), expansion.pathsOf(2))
    }

    @Test
    fun `an account id that is a prefix of another does not leak`() {
        val expansion = FolderExpansion().toggle(12, "Work")

        assertTrue(expansion.pathsOf(1).isEmpty())
    }

    @Test
    fun `revealing opens every ancestor and keeps what was open`() {
        val expansion = FolderExpansion().toggle(1, "Other").reveal(1, listOf("A/B", "A"))

        assertEquals(setOf("Other", "A", "A/B"), expansion.pathsOf(1))
    }

    @Test
    fun `it survives saved state`() {
        val expansion = FolderExpansion().toggle(1, "A/B").toggle(2, "C")

        val restored = FolderExpansion.fromSaved(expansion.toSaved())

        assertEquals(setOf("A/B"), restored.pathsOf(1))
        assertEquals(setOf("C"), restored.pathsOf(2))
        assertTrue(FolderExpansion.fromSaved(null).pathsOf(1).isEmpty())
    }

    @Test
    fun `sections start closed and toggle without showing up as a folder`() {
        val closed = FolderExpansion()
        assertFalse(closed.isSectionOpen(1))

        val open = closed.toggleSection(1)
        assertTrue(open.isSectionOpen(1))
        assertFalse(open.isSectionOpen(2))
        assertTrue(open.pathsOf(1).isEmpty())
        assertFalse(open.toggleSection(1).isSectionOpen(1))
    }

    @Test
    fun `a section and its folders are open independently and survive saved state`() {
        val expansion = FolderExpansion().revealSection(1).toggle(1, "Work")
        val restored = FolderExpansion.fromSaved(expansion.toSaved())

        assertTrue(restored.isSectionOpen(1))
        assertEquals(setOf("Work"), restored.pathsOf(1))
        assertTrue(FolderExpansion().toggle(1, "Work").pathsOf(1).isNotEmpty())
        assertFalse(FolderExpansion().toggle(1, "Work").isSectionOpen(1))
    }
}
