// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalMoveApplierTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var applier: LocalMoveApplier
    private var accountId = 0L

    @BeforeEach
    fun setUp() = runTest {
        db = inMemoryDatabase()
        applier = LocalMoveApplier(
            db.messageDao(),
            db.pendingOperationDao(),
            PendingSyncMarker(db.messageDao(), db.pendingOperationDao())
        )
        accountId = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(accountId), folder(accountId, "Archive")))
        db.messageDao().upsert(
            listOf(
                message(accountId, uid = 1, labels = listOf("\\Inbox", "Work")),
                message(accountId, uid = 2, labels = listOf("\\Inbox"))
            )
        )
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun op(type: OperationType, uid: Long, payload: String) =
        NewOperation(accountId, type, "INBOX", uid, payload)

    private suspend fun queue(operation: NewOperation) {
        db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = operation.accountId,
                type = operation.type,
                folderPath = operation.folderPath,
                uid = operation.uid,
                payload = operation.payload,
                createdAt = Instant.EPOCH
            )
        )
    }

    private suspend fun labelsOf(uid: Long) = db.messageDao().get(accountId, "INBOX", uid)!!.labels

    private suspend fun pending(uid: Long) =
        db.messageDao().get(accountId, "INBOX", uid)!!.pendingSync

    @Test
    fun `an added label shows on the message at once and last`() = runTest {
        val change = op(OperationType.ADD_LABEL, 1, "Personal")
        queue(change)

        applier.apply(listOf(change))

        assertEquals(listOf("\\Inbox", "Work", "Personal"), labelsOf(1))
        assertEquals(listOf("\\Inbox"), labelsOf(2))
    }

    @Test
    fun `a removed label is gone from the message`() = runTest {
        val change = op(OperationType.REMOVE_LABEL, 1, "Work")
        queue(change)

        applier.apply(listOf(change))

        assertEquals(listOf("\\Inbox"), labelsOf(1))
    }

    @Test
    fun `adding a label the message has and removing one it lacks change nothing`() = runTest {
        val changes = listOf(
            op(OperationType.ADD_LABEL, 1, "Work"),
            op(OperationType.REMOVE_LABEL, 1, "Nothing")
        )
        changes.forEach { queue(it) }

        applier.apply(changes)

        assertEquals(listOf("\\Inbox", "Work"), labelsOf(1))
    }

    @Test
    fun `several label changes to one message are all applied`() = runTest {
        val changes = listOf(
            op(OperationType.ADD_LABEL, 1, "A"),
            op(OperationType.REMOVE_LABEL, 1, "\\Inbox"),
            op(OperationType.ADD_LABEL, 1, "B")
        )
        changes.forEach { queue(it) }

        applier.apply(changes)

        assertEquals(listOf("Work", "A", "B"), labelsOf(1))
    }

    @Test
    fun `undoing the label changes brings the labels back`() = runTest {
        val change = op(OperationType.REMOVE_LABEL, 1, "Work")
        queue(change)
        applier.apply(listOf(change))

        val undo = op(OperationType.ADD_LABEL, 1, "Work")
        queue(undo)
        applier.apply(listOf(undo))

        assertEquals(listOf("\\Inbox", "Work"), labelsOf(1))
    }

    @Test
    fun `a message with a queued change is marked as waiting to sync`() = runTest {
        val change = op(OperationType.ADD_LABEL, 1, "Personal")
        queue(change)

        applier.apply(listOf(change))

        assertTrue(pending(1))
        assertFalse(pending(2))
    }

    @Test
    fun `a move marks the message as waiting but keeps its row where it is`() = runTest {
        val move = op(OperationType.MOVE, 1, "Archive")
        queue(move)

        applier.apply(listOf(move))

        assertTrue(pending(1))
        assertNotNull(db.messageDao().get(accountId, "INBOX", 1))
        assertNull(db.messageDao().get(accountId, "Archive", 1))
    }

    @Test
    fun `once nothing waits for a message the mark is cleared`() = runTest {
        val move = op(OperationType.MOVE, 1, "Archive")
        queue(move)
        applier.apply(listOf(move))
        // The queue folded the move back into it: nothing is left.
        db.pendingOperationDao().all(accountId).forEach { db.pendingOperationDao().delete(it.id) }

        applier.apply(listOf(op(OperationType.MOVE, 1, "INBOX")))

        assertFalse(pending(1))
    }

    @Test
    fun `a change to a message that is not stored is ignored`() = runTest {
        val change = op(OperationType.ADD_LABEL, 99, "A")
        queue(change)

        applier.apply(listOf(change))

        assertNull(db.messageDao().get(accountId, "INBOX", 99))
    }
}
