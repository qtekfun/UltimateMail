// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.folder.FolderListing
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The whole path from the picker's result to Room, with the real queue and local applier. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveLabelActionsTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var actions: MoveLabelActions
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val recents = InMemoryRecentDestinations()
    private var folders = 0L
    private var labels = 0L

    @BeforeEach
    fun setUp() = runTest {
        db = inMemoryDatabase()
        val dispatcher = UnconfinedTestDispatcher()
        val queue = OperationQueue(
            db.pendingOperationDao(),
            mockk(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            dispatcher
        )
        actions = MoveLabelActions(
            queue,
            LocalMoveApplier(
                db.messageDao(),
                db.pendingOperationDao(),
                PendingSyncMarker(db.messageDao(), db.pendingOperationDao())
            ),
            recents,
            scheduler,
            PickerSource(db, FolderListing(db)),
            dispatcher
        )
        folders = db.accountDao().insert(account("folders@example.test"))
        db.folderDao().upsert(
            listOf(
                FolderEntity(folders, "INBOX", "INBOX", FolderRole.INBOX),
                FolderEntity(folders, "Archive", "Archive", FolderRole.ARCHIVE),
                FolderEntity(folders, "Receipts", "Receipts")
            )
        )
        db.messageDao().upsert(listOf(message(folders, 1), message(folders, 2)))
        labels = db.accountDao().insert(account("labels@example.test"))
        db.folderDao().upsert(
            listOf(
                FolderEntity(labels, "INBOX", "INBOX", FolderRole.INBOX),
                FolderEntity(labels, "Work", "Work", isLabel = true)
            )
        )
        db.messageDao().upsert(
            listOf(
                message(labels, 1, labels = listOf("\\Inbox")),
                message(labels, 2),
                message(labels, 3, folderPath = "Work", labels = listOf("Work"))
            )
        )
    }

    @AfterEach
    fun tearDown() = db.close()

    private suspend fun queued(accountId: Long) =
        db.pendingOperationDao().all(accountId).map { it.type to (it.uid to it.payload) }

    private val foldersRequest get() =
        PickerRequest(folders, listOf(MessageRef("INBOX", 1), MessageRef("INBOX", 2)))

    private val labelsRequest get() =
        PickerRequest(labels, listOf(MessageRef("INBOX", 1), MessageRef("INBOX", 2)))

    private suspend fun inboxUids(accountId: Long) = db.conversationDao()
        .observeConversations(accountId, "INBOX", 50).first().map { it.latest.uid }

    private fun moveToReceipts(): PickerResult = PickerOperations.move(
        foldersRequest,
        PickerFolder("Receipts", "Receipts", FolderRole.OTHER, false, 0, "Receipts")
    )!!

    @Test
    fun `committing a move queues it, hides the message, marks it pending and asks for a sync`() =
        runTest {
            val result = moveToReceipts()

            actions.commit(result, listOf("Receipts"))

            assertEquals(
                listOf(
                    OperationType.MOVE to (1L to "Receipts"),
                    OperationType.MOVE to (2L to "Receipts")
                ),
                queued(folders)
            )
            assertEquals(emptyList<Long>(), inboxUids(folders))
            assertTrue(db.messageDao().get(folders, "INBOX", 1)!!.pendingSync)
            assertEquals(listOf(folders to listOf("Receipts")), recents.recorded)
            verify { scheduler.requestSync(folders, true) }
        }

    @Test
    fun `undoing a move cancels it in the queue and the message is back`() = runTest {
        val result = moveToReceipts()
        actions.commit(result, listOf("Receipts"))

        actions.undo(result)

        assertEquals(emptyList<Any>(), queued(folders))
        assertEquals(listOf(2L, 1L), inboxUids(folders))
        assertFalse(db.messageDao().get(folders, "INBOX", 1)!!.pendingSync)
    }

    @Test
    fun `committing label changes queues them and shows the labels at once`() = runTest {
        val result = PickerOperations.labels(
            labelsRequest,
            listOf(setOf("\\Inbox"), emptySet()),
            LabelChanges(add = setOf("Work"), remove = setOf("\\Inbox")),
            PickerCandidates.Empty
        )!!

        actions.commit(result, listOf("Work"))

        assertEquals(
            listOf(
                OperationType.ADD_LABEL to (1L to "Work"),
                OperationType.REMOVE_LABEL to (1L to "\\Inbox"),
                OperationType.ADD_LABEL to (2L to "Work")
            ),
            queued(labels)
        )
        assertEquals(listOf("Work"), db.messageDao().get(labels, "INBOX", 1)!!.labels)
        assertEquals(listOf("Work"), db.messageDao().get(labels, "INBOX", 2)!!.labels)
        assertEquals(listOf(2L), inboxUids(labels))
    }

    @Test
    fun `undoing label changes restores the labels`() = runTest {
        val result = PickerOperations.labels(
            labelsRequest,
            listOf(setOf("\\Inbox"), emptySet()),
            LabelChanges(add = setOf("Work"), remove = setOf("\\Inbox")),
            PickerCandidates.Empty
        )!!
        actions.commit(result, emptyList())

        actions.undo(result)

        assertEquals(listOf("\\Inbox"), db.messageDao().get(labels, "INBOX", 1)!!.labels)
        assertEquals(emptyList<String>(), db.messageDao().get(labels, "INBOX", 2)!!.labels)
        assertEquals(listOf(2L, 1L), inboxUids(labels))
        verify(exactly = 2) { scheduler.requestSync(labels, true) }
    }

    @Test
    fun `an archive needs no destination to remember`() = runTest {
        val result = actions.archive(foldersRequest)!!

        assertEquals(PickerOutcome.Archived, result.outcome)
        assertEquals(
            listOf(
                OperationType.MOVE to (1L to "Archive"),
                OperationType.MOVE to (2L to "Archive")
            ),
            queued(folders)
        )
        assertTrue(recents.recorded.single().second.isEmpty())
    }

    @Test
    fun `archiving on a label account removes the inbox label from messages that have it`() =
        runTest {
            val request =
                PickerRequest(labels, listOf(MessageRef("INBOX", 1), MessageRef("Work", 3)))

            val result = actions.archive(request)!!

            assertEquals(
                listOf(OperationType.REMOVE_LABEL to (1L to "\\Inbox")),
                queued(labels)
            )
            assertEquals(1, result.messageCount)
            assertEquals(emptyList<String>(), db.messageDao().get(labels, "INBOX", 1)!!.labels)
        }

    @Test
    fun `archiving something already archived does nothing`() = runTest {
        val request = PickerRequest(labels, listOf(MessageRef("Work", 3)))

        assertNull(actions.archive(request))
        assertEquals(emptyList<Any>(), queued(labels))
    }

    @Test
    fun `archiving for an account that is gone does nothing`() = runTest {
        assertNull(actions.archive(PickerRequest(999, listOf(MessageRef("INBOX", 1)))))
    }

    @Test
    fun `an operation can be queued directly too`() = runTest {
        val change = NewOperation(folders, OperationType.MOVE, "INBOX", 1, "Receipts")
        actions.commit(
            PickerResult(
                folders,
                PickerOutcome.Moved("Receipts", "Receipts"),
                1,
                listOf(change),
                listOf(change.copy(payload = "INBOX"))
            )
        )

        assertEquals(listOf(OperationType.MOVE to (1L to "Receipts")), queued(folders))
    }
}
