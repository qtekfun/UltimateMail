// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DraftServerSyncTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): ComposeHarness {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount()
        return h
    }

    private suspend fun ComposeHarness.saves() =
        db.pendingOperationDao().all(accountId).filter { it.type == OperationType.SAVE_DRAFT }

    @Test
    fun `a dirty draft gets a save in the queue with its text, key and a keyed Message-ID`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")

            assertTrue(h.serverSync.request(draft.id))

            val operation = h.saves().single()
            assertEquals("" to draft.id, operation.folderPath to operation.uid)
            val queued = OutgoingPayload.decodeQueued(operation.payload)!!
            assertEquals(draft.id, queued.draftId)
            assertEquals(draft.key, queued.draftKey)
            assertEquals(draft.revision, queued.revision)
            assertEquals(draft.key, DraftMessageIds.keyOf(queued.message.messageId))
            assertEquals("Text", queued.message.text)
            assertEquals(listOf("bob@example.test"), queued.message.to.map { it.address })
            assertTrue(queued.attachments.isEmpty() && queued.source == null)
            // A plain save does not hurry the sync.
            assertTrue(h.scheduler.requests.isEmpty())
        }

    @Test
    fun `a second request refreshes the waiting save instead of queueing another`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id)
        val first = h.saves().single()
        h.repository.save(
            draft.id,
            DraftEdit(draft.to, emptyList(), emptyList(), "Subject", "Newer text")
        )

        assertTrue(h.serverSync.request(draft.id))

        val only = h.saves().single()
        assertEquals(first.id, only.id)
        assertEquals("Newer text", OutgoingPayload.decode(only.payload)!!.text)
    }

    @Test
    fun `once a save was handed to the server the next one waits for the interval`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id)
        h.db.pendingOperationDao().markStarted(h.saves().single().id, h.clock.instant())

        assertFalse(h.serverSync.request(draft.id))
        assertEquals(1, h.saves().size)

        h.clock.now = h.clock.now.plus(DraftServerSync.MIN_INTERVAL).plusSeconds(1)
        assertTrue(h.serverSync.request(draft.id))
        assertEquals(2, h.saves().size)
    }

    @Test
    fun `forcing queues at once and asks for a sync`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id)
        h.db.pendingOperationDao().markStarted(h.saves().single().id, h.clock.instant())

        assertTrue(h.serverSync.request(draft.id, force = true))

        assertEquals(2, h.saves().size)
        assertEquals(listOf<Long?>(h.accountId), h.scheduler.requests)
    }

    @Test
    fun `a draft that was uploaded and not edited since is left alone unless forced`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.db.draftDao().markUploaded(
            draft.id,
            "<um-draft.${draft.key}.1@example.test>",
            draft.revision
        )

        assertFalse(h.serverSync.request(draft.id))
        assertTrue(h.serverSync.request(draft.id, force = true))
    }

    @Test
    fun `nothing is queued without a Drafts folder or a draft, or once it is in the outbox`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.db.folderDao().deleteAllExcept(h.accountId, listOf("INBOX"))

            assertFalse(h.serverSync.request(draft.id))
            assertFalse(h.serverSync.request(999))

            h.db.folderDao().upsert(listOf(folder(h.accountId, "Drafts", FolderRole.DRAFTS)))
            h.send(draft.id)
            assertFalse(h.serverSync.request(draft.id))
            assertTrue(h.saves().isEmpty())
        }

    @Test
    fun `forgetting a draft drops its waiting saves and queues the delete of its synced copy`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            val copy = "<um-draft.${draft.key}.1@example.test>"
            h.db.draftDao().markUploaded(draft.id, copy, 0)
            h.db.messageDao().upsert(
                listOf(
                    message(h.accountId, 4, "Drafts").copy(messageId = copy),
                    // Same id in another folder is not a draft copy.
                    message(h.accountId, 9, "INBOX").copy(messageId = copy)
                )
            )
            h.serverSync.request(draft.id, force = true)
            h.scheduler.requests.clear()

            h.serverSync.forgetServerCopy(h.repository.get(draft.id)!!)

            val queued = h.db.pendingOperationDao().all(h.accountId).single()
            assertEquals(OperationType.DELETE, queued.type)
            assertEquals("Drafts" to 4L, queued.folderPath to queued.uid)
            assertEquals(listOf<Long?>(h.accountId), h.scheduler.requests)
        }

    @Test
    fun `forgetting a draft that never reached the server only drops its waiting saves`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.serverSync.request(draft.id)

            h.serverSync.forgetServerCopy(draft)

            assertTrue(h.db.pendingOperationDao().all(h.accountId).isEmpty())
            assertTrue(h.scheduler.requests.isEmpty())
            assertNull(h.repository.get(draft.id)!!.serverMessageId)
        }

    @Test
    fun `forgetting a draft whose copy has not been synced down queues nothing`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.db.draftDao().markUploaded(draft.id, "<um-draft.k.1@example.test>", 0)

        h.serverSync.forgetServerCopy(h.repository.get(draft.id)!!)

        assertTrue(h.db.pendingOperationDao().all(h.accountId).isEmpty())
    }
}
