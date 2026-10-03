// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.data.local.model.OperationType.ADD_LABEL
import com.qtekfun.ultimatemail.data.local.model.OperationType.MOVE
import com.qtekfun.ultimatemail.data.local.model.OperationType.REMOVE_LABEL
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PickerOperationsTest {
    private val request = PickerRequest(
        accountId = 7,
        messages = listOf(MessageRef("INBOX", 10), MessageRef("INBOX", 11), MessageRef("Sent", 3))
    )

    private val folders = PickerCandidates.build(
        imapTree(),
        PickerMode.FOLDERS,
        setOf("INBOX", "Sent")
    )
    private val labels = PickerCandidates.build(gmailTree(), PickerMode.LABELS, setOf("INBOX"))

    private fun op(type: OperationType, folder: String, uid: Long, payload: String) =
        NewOperation(7, type, folder, uid, payload)

    @Test
    fun `a request needs messages`() {
        assertThrows(IllegalArgumentException::class.java) { PickerRequest(1, emptyList()) }
    }

    @Test
    fun `moving queues one move per message to the chosen folder`() {
        val result = PickerOperations.move(request, folders.byPath("Archive")!!)!!

        assertEquals(
            listOf(
                op(MOVE, "INBOX", 10, "Archive"),
                op(MOVE, "INBOX", 11, "Archive"),
                op(MOVE, "Sent", 3, "Archive")
            ),
            result.operations
        )
        assertEquals(7, result.accountId)
        assertEquals(3, result.messageCount)
        assertEquals(PickerOutcome.Moved("Archive", "Archive"), result.outcome)
    }

    @Test
    fun `undoing a move is a move back to where each message was`() {
        val result = PickerOperations.move(request, folders.byPath("Archive")!!)!!

        assertEquals(
            listOf(
                op(MOVE, "INBOX", 10, "INBOX"),
                op(MOVE, "INBOX", 11, "INBOX"),
                op(MOVE, "Sent", 3, "Sent")
            ),
            result.inverse
        )
    }

    @Test
    fun `messages already in the destination are not moved`() {
        val result = PickerOperations.move(request, folders.byPath("Sent")!!)!!

        assertEquals(
            listOf(op(MOVE, "INBOX", 10, "Sent"), op(MOVE, "INBOX", 11, "Sent")),
            result.operations
        )
        assertEquals(2, result.messageCount)
    }

    @Test
    fun `moving where every message already is does nothing`() {
        val only = PickerRequest(7, listOf(MessageRef("INBOX", 1)))

        assertNull(PickerOperations.move(only, folders.byPath("INBOX")!!))
    }

    @Test
    fun `labels are added to the messages that lack them`() {
        val has = listOf(setOf("Personal"), emptySet(), setOf("Work/Clients"))

        val result = PickerOperations.labels(
            request,
            has,
            LabelChanges(add = setOf("Personal"), remove = emptySet()),
            labels
        )!!

        assertEquals(
            listOf(
                op(ADD_LABEL, "INBOX", 11, "Personal"),
                op(ADD_LABEL, "Sent", 3, "Personal")
            ),
            result.operations
        )
        assertEquals(2, result.messageCount)
        assertEquals(PickerOutcome.LabelsChanged(listOf("Personal"), emptyList()), result.outcome)
    }

    @Test
    fun `labels are removed from the messages that have them`() {
        val has = listOf(setOf("Personal"), emptySet(), setOf("Personal", "Work/Clients"))

        val result = PickerOperations.labels(
            request,
            has,
            LabelChanges(add = emptySet(), remove = setOf("Personal")),
            labels
        )!!

        assertEquals(
            listOf(
                op(REMOVE_LABEL, "INBOX", 10, "Personal"),
                op(REMOVE_LABEL, "Sent", 3, "Personal")
            ),
            result.operations
        )
    }

    @Test
    fun `several changes give per message additions then removals in a fixed order`() {
        val has = listOf(setOf("Work/Clients"), setOf("Work/Clients"), emptySet())

        val result = PickerOperations.labels(
            request,
            has,
            LabelChanges(add = setOf("Work/Invoices", "Personal"), remove = setOf("Work/Clients")),
            labels
        )!!

        assertEquals(
            listOf(
                op(ADD_LABEL, "INBOX", 10, "Personal"),
                op(ADD_LABEL, "INBOX", 10, "Work/Invoices"),
                op(REMOVE_LABEL, "INBOX", 10, "Work/Clients"),
                op(ADD_LABEL, "INBOX", 11, "Personal"),
                op(ADD_LABEL, "INBOX", 11, "Work/Invoices"),
                op(REMOVE_LABEL, "INBOX", 11, "Work/Clients"),
                op(ADD_LABEL, "Sent", 3, "Personal"),
                op(ADD_LABEL, "Sent", 3, "Work/Invoices")
            ),
            result.operations
        )
        assertEquals(3, result.messageCount)
    }

    @Test
    fun `undoing label changes swaps every operation and reverses their order`() {
        val result = PickerOperations.labels(
            request,
            listOf(setOf("A"), emptySet(), emptySet()),
            LabelChanges(add = setOf("Personal"), remove = setOf("A")),
            labels
        )!!

        assertEquals(
            listOf(
                op(ADD_LABEL, "INBOX", 10, "Personal"),
                op(REMOVE_LABEL, "INBOX", 10, "A"),
                op(ADD_LABEL, "INBOX", 11, "Personal"),
                op(ADD_LABEL, "Sent", 3, "Personal")
            ),
            result.operations
        )
        assertEquals(
            listOf(
                op(REMOVE_LABEL, "Sent", 3, "Personal"),
                op(REMOVE_LABEL, "INBOX", 11, "Personal"),
                op(ADD_LABEL, "INBOX", 10, "A"),
                op(REMOVE_LABEL, "INBOX", 10, "Personal")
            ),
            result.inverse
        )
    }

    @Test
    fun `labels already as wanted give nothing to do`() {
        val has = listOf(setOf("Personal"), setOf("Personal"), setOf("Personal"))

        assertNull(
            PickerOperations.labels(
                request,
                has,
                LabelChanges(setOf("Personal"), emptySet()),
                labels
            )
        )
        assertNull(
            PickerOperations.labels(request, has, LabelChanges(emptySet(), setOf("Other")), labels)
        )
        assertNull(
            PickerOperations.labels(request, has, LabelChanges(emptySet(), emptySet()), labels)
        )
    }

    @Test
    fun `the outcome names labels by their display name and the inbox by its role name`() {
        val result = PickerOperations.labels(
            request,
            listOf(emptySet(), emptySet(), emptySet()),
            LabelChanges(add = setOf("Work/Invoices"), remove = setOf(GmailLabels.INBOX)),
            labels
        )

        // The inbox is not on a message here, so only the addition produces operations.
        assertEquals(
            PickerOutcome.LabelsChanged(added = listOf("Invoices"), removed = listOf("INBOX")),
            result!!.outcome
        )
    }

    @Test
    fun `a label the picker does not know keeps its own text as a name`() {
        val result = PickerOperations.labels(
            request,
            listOf(emptySet(), emptySet(), emptySet()),
            LabelChanges(add = setOf("Mystery"), remove = emptySet()),
            labels
        )!!

        assertEquals(PickerOutcome.LabelsChanged(listOf("Mystery"), emptyList()), result.outcome)
    }

    @Test
    fun `one label set per message is required`() {
        assertThrows(IllegalArgumentException::class.java) {
            PickerOperations.labels(
                request,
                listOf(emptySet()),
                LabelChanges(setOf("A"), emptySet()),
                labels
            )
        }
    }

    @Test
    fun `archiving on gmail removes the inbox label from messages that have it`() {
        val has =
            listOf(
                setOf(GmailLabels.INBOX),
                setOf("Personal"),
                setOf(GmailLabels.INBOX, "Personal")
            )

        val result = PickerOperations.archive(request, PickerMode.LABELS, gmailTree(), has)!!

        assertEquals(
            listOf(
                op(REMOVE_LABEL, "INBOX", 10, "\\Inbox"),
                op(REMOVE_LABEL, "Sent", 3, "\\Inbox")
            ),
            result.operations
        )
        assertEquals(PickerOutcome.Archived, result.outcome)
        assertEquals(
            listOf(op(ADD_LABEL, "Sent", 3, "\\Inbox"), op(ADD_LABEL, "INBOX", 10, "\\Inbox")),
            result.inverse
        )
    }

    @Test
    fun `archiving on gmail does nothing when nothing is in the inbox`() {
        val has = listOf(setOf("Personal"), emptySet(), emptySet())

        assertNull(PickerOperations.archive(request, PickerMode.LABELS, gmailTree(), has))
    }

    @Test
    fun `archiving elsewhere moves to the archive folder`() {
        val result = PickerOperations.archive(
            request,
            PickerMode.FOLDERS,
            imapTree(),
            listOf(emptySet(), emptySet(), emptySet())
        )!!

        assertEquals(
            listOf(
                op(MOVE, "INBOX", 10, "Archive"),
                op(MOVE, "INBOX", 11, "Archive"),
                op(MOVE, "Sent", 3, "Archive")
            ),
            result.operations
        )
        assertEquals(PickerOutcome.Archived, result.outcome)
    }

    @Test
    fun `archiving elsewhere with no archive folder does nothing`() {
        val noArchive = tree(entity("INBOX", FolderRole.INBOX))

        assertNull(
            PickerOperations.archive(request, PickerMode.FOLDERS, noArchive, emptyList())
        )
    }
}
