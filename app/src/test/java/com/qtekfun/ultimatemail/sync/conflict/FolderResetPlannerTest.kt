// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FolderResetPlannerTest {
    private val messages = listOf(
        LocalMessage(10, 1, MessageIdentity("<a@x>")),
        LocalMessage(11, 2, MessageIdentity("<b@x>", 55)),
        LocalMessage(12, 3, MessageIdentity("<draft@x>"), unsyncedDraft = true),
        LocalMessage(13, 4, MessageIdentity())
    )

    private fun plan(stored: Long?, server: Long, pending: List<PendingTarget>) =
        FolderResetPlanner.plan(FolderSnapshot(1, "INBOX", stored, messages, pending), server)

    @Test
    fun `same uidvalidity needs no reset`() {
        assertNull(plan(7, 7, listOf(PendingTarget(1, 1))))
    }

    @Test
    fun `a folder never synced needs no reset`() {
        assertNull(plan(null, 7, emptyList()))
    }

    @Test
    fun `changed uidvalidity drops rows but keeps unsynced drafts`() {
        val plan = requireNotNull(plan(7, 8, emptyList()))
        assertEquals(listOf(10L, 11L, 13L), plan.dropRowIds)
        assertEquals(listOf(12L), plan.keepDraftRowIds)
        assertEquals(listOf<SyncNotice>(SyncNotice.FolderReset(1, "INBOX")), plan.notices)
    }

    @Test
    fun `pending operations are remapped by the identity of their message`() {
        val plan = requireNotNull(plan(7, 8, listOf(PendingTarget(100, 1), PendingTarget(101, 2))))
        assertEquals(
            listOf(
                OperationRemap(100, MessageIdentity("<a@x>")),
                OperationRemap(101, MessageIdentity("<b@x>", 55))
            ),
            plan.remaps
        )
        assertEquals(emptyList<Long>(), plan.discardOperationIds)
    }

    @Test
    fun `operations that cannot be identified are discarded with a notice each`() {
        val pending = listOf(
            PendingTarget(100, 1),
            PendingTarget(101, 4), // row exists but has no Message-ID
            PendingTarget(102, 99) // no row at all: the sync was interrupted midway
        )
        val plan = requireNotNull(plan(7, 8, pending))
        assertEquals(listOf(OperationRemap(100, MessageIdentity("<a@x>"))), plan.remaps)
        assertEquals(listOf(101L, 102L), plan.discardOperationIds)
        assertEquals(
            listOf(
                SyncNotice.FolderReset(1, "INBOX"),
                SyncNotice.MessageVanished(1, "INBOX", 101),
                SyncNotice.MessageVanished(1, "INBOX", 102)
            ),
            plan.notices
        )
    }

    @Test
    fun `an operation on an unsynced draft is remapped too`() {
        val plan = requireNotNull(plan(7, 8, listOf(PendingTarget(200, 3))))
        assertEquals(listOf(OperationRemap(200, MessageIdentity("<draft@x>"))), plan.remaps)
        assertEquals(listOf(12L), plan.keepDraftRowIds)
    }
}
