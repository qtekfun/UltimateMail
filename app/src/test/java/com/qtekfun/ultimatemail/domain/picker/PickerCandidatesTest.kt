// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun PickerCandidates.describe(): List<String> = rows.map {
    when (it) {
        is PickerRow.Group -> "group:${it.path}@${it.depth}"

        is PickerRow.Destination ->
            "${it.folder.path}@${it.folder.depth}" + if (it.folder.enabled) "" else "!"
    }
}

class PickerCandidatesTest {
    @Test
    fun `folder mode lists special folders first and then the hierarchy`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("Personal"))

        assertEquals(
            listOf(
                "INBOX@0", "Sent@0", "Archive@0", "Trash@0", "Junk@0",
                "Personal@0!", "group:Work@0", "Work/Clients@1", "Work/Invoices@1"
            ),
            candidates.describe()
        )
    }

    @Test
    fun `folder mode leaves out drafts and the virtual gmail folders`() {
        val tree = tree(
            entity("INBOX", FolderRole.INBOX),
            entity("Drafts", FolderRole.DRAFTS),
            entity("All", FolderRole.ALL_MAIL),
            entity("Starred", FolderRole.STARRED)
        )

        val candidates = PickerCandidates.build(tree, PickerMode.FOLDERS, setOf("Other"))

        assertEquals(listOf("INBOX@0"), candidates.describe())
    }

    @Test
    fun `trash and spam stay selectable`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("INBOX"))

        assertTrue(candidates.byPath("Trash")!!.enabled)
        assertTrue(candidates.byPath("Junk")!!.enabled)
        assertEquals(FolderRole.TRASH, candidates.byPath("Trash")!!.role)
    }

    @Test
    fun `the folder all the messages are in is disabled`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("INBOX"))

        assertFalse(candidates.byPath("INBOX")!!.enabled)
        assertTrue(candidates.byPath("Sent")!!.enabled)
    }

    @Test
    fun `with messages from several folders nothing is disabled`() {
        val candidates = PickerCandidates.build(
            imapTree(),
            PickerMode.FOLDERS,
            setOf("INBOX", "Work/Clients")
        )

        assertTrue(candidates.folders.all { it.enabled })
    }

    @Test
    fun `containers are not offered but a group keeps the nesting readable`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("INBOX"))

        assertNull(candidates.byPath("Work"))
        assertTrue(candidates.rows.any { it is PickerRow.Group && it.path == "Work" })
        assertEquals(
            listOf(
                "INBOX",
                "Sent",
                "Archive",
                "Trash",
                "Junk",
                "Personal",
                "Work/Clients",
                "Work/Invoices"
            ),
            candidates.folders.map { it.path }
        )
    }

    @Test
    fun `a container with nothing to offer below it is dropped`() {
        val candidates = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

        assertFalse(candidates.rows.any { it is PickerRow.Group && it.path == "[Gmail]" })
    }

    @Test
    fun `label mode offers the inbox and the user's own labels`() {
        val candidates = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

        assertEquals(
            listOf(
                "INBOX@0",
                "[Gmail]/Trash@0",
                "[Gmail]/Spam@0",
                "Personal@0",
                "group:Work@0",
                "Work/Clients@1",
                "Work/Invoices@1"
            ),
            candidates.describe()
        )
    }

    @Test
    fun `label mode does not offer gmail's other folders or pseudo labels`() {
        val paths = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))
            .folders.map { it.path }

        assertEquals(
            listOf("[Gmail]/Spam", "[Gmail]/Trash"),
            paths.filter { it.startsWith("[Gmail]") }.sorted()
        )
    }

    @Test
    fun `label mode offers trash and spam as places to move to, not as labels`() {
        val candidates = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

        val trash = candidates.byPath("[Gmail]/Trash")!!
        val spam = candidates.byPath("[Gmail]/Spam")!!
        assertTrue(trash.moveTarget)
        assertTrue(spam.moveTarget)
        assertEquals(FolderRole.TRASH, trash.role)
        assertTrue(trash.enabled)
        assertFalse(candidates.byPath("INBOX")!!.moveTarget)
        assertFalse(candidates.byPath("Work/Invoices")!!.moveTarget)
    }

    @Test
    fun `a message already in trash cannot be moved to trash again`() {
        val candidates =
            PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("[Gmail]/Trash"))

        assertFalse(candidates.byPath("[Gmail]/Trash")!!.enabled)
        assertTrue(candidates.byPath("[Gmail]/Spam")!!.enabled)
    }

    @Test
    fun `folder mode never marks a destination as a move target`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("INBOX"))

        assertFalse(candidates.folders.any { it.moveTarget })
    }

    @Test
    fun `the inbox is the label backslash Inbox and labels use their path`() {
        val candidates = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

        assertEquals("\\Inbox", candidates.byPath("INBOX")!!.value)
        assertEquals("Work/Invoices", candidates.byPath("Work/Invoices")!!.value)
        assertEquals("Work/Invoices", candidates.byValue("Work/Invoices")!!.path)
        assertEquals("INBOX", candidates.byValue(GmailLabels.INBOX)!!.path)
    }

    @Test
    fun `in label mode the current folder is not disabled`() {
        val candidates = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

        assertTrue(candidates.byPath("INBOX")!!.enabled)
    }

    @Test
    fun `in folder mode the inbox value is its path`() {
        val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("Sent"))

        assertEquals("INBOX", candidates.byPath("INBOX")!!.value)
    }

    @Test
    fun `special folders take the names given for the user's language`() {
        val candidates = PickerCandidates.build(
            imapTree(),
            PickerMode.FOLDERS,
            setOf("INBOX"),
            roleNames = mapOf(FolderRole.SENT to "Enviados", FolderRole.INBOX to "Entrada")
        )

        assertEquals("Enviados", candidates.byPath("Sent")!!.name)
        assertEquals("Entrada", candidates.byPath("INBOX")!!.name)
        assertEquals("Personal", candidates.byPath("Personal")!!.name)
    }

    @Test
    fun `an empty tree has nothing to offer`() {
        val candidates = PickerCandidates.build(tree(), PickerMode.FOLDERS, setOf("INBOX"))

        assertTrue(candidates.rows.isEmpty())
        assertTrue(candidates.folders.isEmpty())
        assertTrue(PickerCandidates.Empty.rows.isEmpty())
    }

    @Test
    fun `the implicit label of the inbox and of a user label is known`() {
        val tree = gmailTree()

        assertEquals(GmailLabels.INBOX, PickerCandidates.implicitLabel(tree, "INBOX"))
        assertEquals("Work/Invoices", PickerCandidates.implicitLabel(tree, "Work/Invoices"))
        assertNull(PickerCandidates.implicitLabel(tree, "[Gmail]/All Mail"))
        assertNull(PickerCandidates.implicitLabel(tree, "[Gmail]"))
        assertNull(PickerCandidates.implicitLabel(tree, "Unknown"))
    }

    @Test
    fun `a plain imap folder is not a label`() {
        assertNull(PickerCandidates.implicitLabel(imapTree(), "Personal"))
    }
}
