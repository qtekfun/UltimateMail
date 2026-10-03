// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TargetResolverTest {
    private val id = MessageIdentity("<m1@x>", 100)

    private fun op(
        kind: TargetKind,
        identity: MessageIdentity = id,
        argument: String = "Archive",
        uid: Long = 5
    ) = TargetedOperation(
        id = 42,
        accountId = 1,
        kind = kind,
        folderPath = "INBOX",
        uid = uid,
        identity = identity,
        argument = argument
    )

    private fun inbox(uid: Long, identity: MessageIdentity = id, labels: Set<String> = emptySet()) =
        ServerMessage("INBOX", uid, identity, labels)

    private val vanished = TargetResolution.Discard(SyncNotice.MessageVanished(1, "INBOX", 42))

    @Test
    fun `message still at its uid is addressed there`() {
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(inbox(5)))
        assertEquals(TargetResolution.Apply(5), result)
    }

    @Test
    fun `message that changed uid is found again by message id`() {
        val moved = inbox(9, MessageIdentity("<m1@x>"))
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(moved))
        assertEquals(TargetResolution.Apply(9), result)
    }

    @Test
    fun `message that changed uid is found again by gmail id`() {
        val moved = inbox(9, MessageIdentity("<renamed@x>", 100))
        val result = TargetResolver.resolve(op(TargetKind.ADD_LABEL), listOf(moved))
        assertEquals(TargetResolution.Apply(9), result)
    }

    @Test
    fun `a different message that reused the uid is not touched`() {
        val stranger = inbox(5, MessageIdentity("<other@x>", 200))
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(stranger))
        assertEquals(vanished, result)
    }

    @Test
    fun `a reused uid is skipped in favour of the real message under a new uid`() {
        val stranger = inbox(5, MessageIdentity("<other@x>", 200))
        val real = inbox(8)
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(stranger, real))
        assertEquals(TargetResolution.Apply(8), result)
    }

    @Test
    fun `a vanished message discards move and label operations with a notice`() {
        for (kind in listOf(TargetKind.MOVE, TargetKind.ADD_LABEL, TargetKind.REMOVE_LABEL)) {
            assertEquals(vanished, TargetResolver.resolve(op(kind), emptyList()), kind.name)
        }
    }

    @Test
    fun `the same message in another folder does not count as the source`() {
        val elsewhere = ServerMessage("Other", 5, id)
        val result = TargetResolver.resolve(op(TargetKind.ADD_LABEL), listOf(elsewhere))
        assertEquals(vanished, result)
    }

    @Test
    fun `without identity only the uid can find the message`() {
        val anonymous = MessageIdentity()
        val found = TargetResolver.resolve(
            op(TargetKind.MOVE, anonymous),
            listOf(inbox(5, MessageIdentity("<whatever@x>")))
        )
        assertEquals(TargetResolution.Apply(5), found)
        val gone = TargetResolver.resolve(op(TargetKind.MOVE, anonymous), listOf(inbox(6)))
        assertEquals(vanished, gone)
    }

    @Test
    fun `a move already in the destination completes without sending`() {
        val delivered = ServerMessage("Archive", 77, id)
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(delivered))
        assertEquals(TargetResolution.AlreadyApplied, result)
    }

    @Test
    fun `a move found in both folders is still applied from the source`() {
        val copy = ServerMessage("Archive", 77, id)
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(inbox(5), copy))
        assertEquals(TargetResolution.Apply(5), result)
    }

    @Test
    fun `a move whose message is in an unrelated folder is discarded`() {
        val elsewhere = ServerMessage("Trash", 3, id)
        val result = TargetResolver.resolve(op(TargetKind.MOVE), listOf(elsewhere))
        assertEquals(vanished, result)
    }

    @Test
    fun `a label already present completes and a missing one is applied`() {
        val has = inbox(5, labels = setOf("Work"))
        val add = op(TargetKind.ADD_LABEL, argument = "Work")
        assertEquals(TargetResolution.AlreadyApplied, TargetResolver.resolve(add, listOf(has)))
        assertEquals(TargetResolution.Apply(5), TargetResolver.resolve(add, listOf(inbox(5))))
    }

    @Test
    fun `a label already absent completes and a present one is removed`() {
        val remove = op(TargetKind.REMOVE_LABEL, argument = "Work")
        assertEquals(
            TargetResolution.AlreadyApplied,
            TargetResolver.resolve(remove, listOf(inbox(5)))
        )
        assertEquals(
            TargetResolution.Apply(5),
            TargetResolver.resolve(remove, listOf(inbox(5, labels = setOf("Work"))))
        )
    }

    @Test
    fun `delete is applied when the message exists and is done when it is gone`() {
        val delete = op(TargetKind.DELETE)
        assertEquals(TargetResolution.Apply(5), TargetResolver.resolve(delete, listOf(inbox(5))))
        assertEquals(TargetResolution.AlreadyApplied, TargetResolver.resolve(delete, emptyList()))
    }
}
