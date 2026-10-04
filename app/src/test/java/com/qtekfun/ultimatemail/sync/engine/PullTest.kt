// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import java.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(setup: EngineHarness.() -> Unit = {}): EngineHarness {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.setup()
        h.addAccount()
        return h
    }

    private suspend fun EngineHarness.sync() = engine.sync(accountId)

    private suspend fun EngineHarness.stored(folder: String = "INBOX"): List<MessageEntity> =
        messages.serverUids(accountId, folder).map { messages.get(accountId, folder, it)!! }

    @Test
    fun `the first sync stores folders with their roles and the headers of each`() = runTest {
        val h = start {
            server.folder("Sent", MailFolderRole.SENT)
            server.folder("[Gmail]", selectable = false)
            server.folder("[Gmail]/All Mail", MailFolderRole.ALL_MAIL)
            server.deliver("INBOX", subject = "Hello")
            server.deliver("Sent", subject = "Sent one")
        }

        val result = h.sync()

        assertEquals(AccountSyncResult.Synced(SyncCounts(added = 2)), result)
        val folders = h.folders.all(h.accountId).associateBy { it.path }
        assertEquals(FolderRole.INBOX, folders.getValue("INBOX").role)
        assertEquals(FolderRole.SENT, folders.getValue("Sent").role)
        assertFalse(folders.getValue("[Gmail]").syncEnabled)
        assertFalse(folders.getValue("[Gmail]/All Mail").syncEnabled)
        assertEquals("Hello", h.stored().single().subject)
        assertEquals("Sent one", h.stored("Sent").single().subject)
        assertEquals(2L, folders.getValue("INBOX").uidNext)
    }

    @Test
    fun `an incremental sync fetches only the new UIDs`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.server.log.clear()
        h.server.deliver("INBOX", subject = "Second")

        val result = h.sync()

        assertEquals(AccountSyncResult.Synced(SyncCounts(added = 1)), result)
        assertEquals(
            listOf("fetchHeaders INBOX 2-2", "fetchHeaders INBOX 1-1"),
            h.server.logged("fetchHeaders")
        )
        assertEquals(listOf("Subject", "Second"), h.stored().map { it.subject })
    }

    @Test
    fun `with CONDSTORE and nothing changed no header is fetched`() = runTest {
        val h = start {
            server.condstore = true
            server.deliver("INBOX")
        }
        h.sync()
        h.server.log.clear()

        val result = h.sync()

        assertEquals(AccountSyncResult.Synced(SyncCounts()), result)
        assertTrue(h.server.logged("fetchHeaders").isEmpty())
    }

    @Test
    fun `with CONDSTORE a changed mod-seq reconciles the flags`() = runTest {
        val h = start {
            server.condstore = true
            server.deliver("INBOX")
        }
        h.sync()
        h.server.changeFlags("INBOX", 1, MessageFlags(seen = true))

        h.sync()

        assertTrue(h.stored().single().seen)
    }

    @Test
    fun `flags and deletions of known messages are reconciled`() = runTest {
        val h = start {
            server.deliver("INBOX")
            server.deliver("INBOX")
            server.deliver("INBOX")
        }
        h.sync()
        h.server.changeFlags("INBOX", 1, MessageFlags(seen = true, flagged = true, answered = true))
        h.server.expunge("INBOX", 2)
        h.server.changeFlags("INBOX", 3, MessageFlags(deleted = true))

        val result = h.sync()

        assertEquals(AccountSyncResult.Synced(SyncCounts(updated = 1, removed = 2)), result)
        val left = h.stored().single()
        assertEquals(1L, left.uid)
        assertTrue(left.seen && left.flagged && left.answered)
    }

    @Test
    fun `headers flagged as deleted on the server are not stored`() = runTest {
        val h = start { server.deliver("INBOX", flags = MessageFlags(deleted = true)) }

        h.sync()

        assertTrue(h.stored().isEmpty())
    }

    @Test
    fun `only headers inside the offline window are kept and older ones are dropped`() = runTest {
        val h = start {
            val now = clock.now
            server.deliver("INBOX", subject = "ancient", sentAt = now.minus(Duration.ofDays(400)))
            server.deliver("INBOX", subject = "recent", sentAt = now.minus(Duration.ofDays(10)))
        }
        h.sync()
        assertEquals(listOf("recent"), h.stored().map { it.subject })

        h.clock.now = h.clock.now.plus(Duration.ofDays(85))
        h.sync()

        assertTrue(h.stored().isEmpty())
    }

    @Test
    fun `a null window keeps the whole mailbox`() = runTest {
        val h = start {
            server.deliver("INBOX", sentAt = clock.now.minus(Duration.ofDays(4000)))
        }
        h.db.accountDao().update(
            h.db.accountDao().get(h.accountId)!!.copy(offlineWindowDays = null)
        )

        h.sync()

        assertEquals(1, h.stored().size)
    }

    @Test
    fun `big folders are fetched in batches newest first and stop at the window`() = runTest {
        val h = start {
            repeat(300) { server.deliver("INBOX", sentAt = clock.now.minus(Duration.ofDays(500))) }
            repeat(450) { server.deliver("INBOX", sentAt = clock.now.minus(Duration.ofDays(1))) }
        }
        // A 30-day window is a single phase, so only the first pass reads the folder.
        h.db.accountDao().update(h.db.accountDao().get(h.accountId)!!.copy(offlineWindowDays = 30))

        h.sync()

        // 750 UIDs: 300 old (1-300) and 450 new; the batch 1-150 is all old: stops there.
        assertEquals(
            listOf(
                "fetchHeaders INBOX 551-750",
                "fetchHeaders INBOX 351-550",
                "fetchHeaders INBOX 151-350",
                "fetchHeaders INBOX 1-150"
            ),
            h.server.logged("fetchHeaders")
        )
        assertEquals(450, h.stored().size)
    }

    @Test
    fun `a UIDVALIDITY change resyncs the folder and keeps queued operations and local drafts`() =
        runTest {
            val h = start {
                server.deliver("INBOX", subject = "one", messageId = "<one@x>")
                server.deliver("INBOX", subject = "two", messageId = "<two@x>")
                server.deliver("INBOX", subject = "three", messageId = "<three@x>")
            }
            h.sync()
            // A local draft: a row no server UID refers to.
            h.messages.insertNew(
                listOf(
                    h.stored().first().copy(
                        id = 0,
                        uid = 0,
                        messageId = "<draft@x>",
                        subject = "my draft",
                        draft = true
                    )
                )
            )
            // Operations wait on message two (which survives) and on three (which is lost).
            h.queue.enqueue(
                NewOperation(
                    h.accountId,
                    OperationType.SET_FLAGS,
                    "INBOX",
                    2,
                    FlagChange(seen = true).encode()
                )
            )
            h.queue.enqueue(NewOperation(h.accountId, OperationType.MOVE, "INBOX", 3, "Archive"))
            h.executorOffline()
            h.server.expunge("INBOX", 1)
            h.server.expunge("INBOX", 3)
            h.server.renumber("INBOX")

            h.sync()

            val byId = h.stored().associateBy { it.messageId }
            assertEquals(setOf("<two@x>"), byId.keys)
            val newUid = byId.getValue("<two@x>").uid
            val queued = h.operations.all(h.accountId).single()
            assertEquals(OperationType.SET_FLAGS, queued.type)
            assertEquals(newUid, queued.uid)
            assertNotNull(h.messages.get(h.accountId, "INBOX", 0), "the local draft survives")
            assertEquals(2L, h.folders.get(h.accountId, "INBOX")!!.uidValidity)
            assertTrue(byId.getValue("<two@x>").pendingSync)
            assertTrue(
                byId.getValue("<two@x>").seen,
                "the pending change shows over the fresh state"
            )
            // The user is told about the reset and about the operation that lost its message.
            val notices = h.collectNotices()
            assertTrue(notices.any { it is SyncNotice.FolderReset })
            assertEquals(1, notices.count { it is SyncNotice.MessageVanished })
        }

    @Test
    fun `a moved message found at its destination after a reset completes the move`() = runTest {
        val h = start {
            server.folder("Archive")
            server.deliver("INBOX", messageId = "<one@x>")
        }
        h.sync()
        h.queue.enqueue(NewOperation(h.accountId, OperationType.MOVE, "INBOX", 1, "Archive"))
        h.executorOffline()
        // The server moved it (the answer was lost) and renumbered the folder.
        h.server.deliver("Archive", messageId = "<one@x>")
        h.server.expunge("INBOX", 1)
        h.server.renumber("INBOX")

        h.sync()

        assertTrue(h.operations.all(h.accountId).isEmpty(), "nothing left to send")
        assertTrue(h.collectNotices().none { it is SyncNotice.MessageVanished })
    }

    @Test
    fun `an operation waiting for a message stays inert until its new UID is known`() = runTest {
        val h = start {
            server.deliver("INBOX", messageId = "<one@x>")
        }
        h.sync()
        h.queue.enqueue(
            NewOperation(
                h.accountId,
                OperationType.SET_FLAGS,
                "INBOX",
                1,
                FlagChange(seen = true).encode()
            )
        )
        h.executorOffline()
        h.server.renumber("INBOX")
        h.server.failure =
            { name -> MailResult.NetworkUnavailable.takeIf { name.startsWith("fetchHeaders") } }

        h.sync()

        val operation = h.operations.all(h.accountId).single()
        assertTrue(operation.uid < 0, "detached from the old UID")
        assertTrue(h.server.logged("setFlags").isEmpty(), "nothing was sent to a stale UID")
    }

    @Test
    fun `a folder that vanished from the server is deleted with its messages`() = runTest {
        val h = start {
            server.folder("Old")
            server.deliver("Old")
        }
        h.sync()
        assertEquals(1, h.stored("Old").size)
        h.server.folders.remove("Old")

        h.sync()

        assertNull(h.folders.get(h.accountId, "Old"))
        assertTrue(h.stored("Old").isEmpty())
    }

    @Test
    fun `an empty folder list never wipes the account`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.server.folders.clear()

        val result = h.sync()

        assertEquals(AccountSyncResult.Failed(SyncProblem.SERVER), result)
        assertNotNull(h.folders.get(h.accountId, "INBOX"))
        assertEquals(1, h.stored().size)
    }

    @Test
    fun `a renamed or re-roled folder keeps its sync choice and state`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.server.folders["INBOX"] = FakeFolder("INBOX", MailFolderRole.ARCHIVE).also {
            val old = h.server.folder("INBOX")
            it.messages.putAll(old.messages)
            it.nextUid = old.nextUid
        }

        h.sync()

        val folder = h.folders.get(h.accountId, "INBOX")!!
        assertEquals(FolderRole.ARCHIVE, folder.role)
        assertNotNull(folder.uidNext)
        assertEquals(1, h.stored().size)
    }

    @Test
    fun `a network drop mid-pull leaves a consistent state and the next run finishes`() = runTest {
        val h = start {
            repeat(450) {
                server.deliver("INBOX", sentAt = clock.now.minus(Duration.ofDays(1)))
            }
        }
        var fetches = 0
        h.server.failure = { name ->
            if (name.startsWith("fetchHeaders") &&
                ++fetches == 2
            ) {
                MailResult.NetworkUnavailable
            } else {
                null
            }
        }

        val first = h.sync()

        assertEquals(AccountSyncResult.Failed(SyncProblem.NETWORK), first)
        assertEquals(AccountSyncState.Error(SyncProblem.NETWORK), h.status.get(h.accountId))
        assertEquals(200, h.stored().size, "the first batch is stored whole")
        assertNull(
            h.folders.get(h.accountId, "INBOX")!!.uidNext,
            "the sync state is saved only when done"
        )

        h.server.failure = { null }
        val second = h.sync()

        assertEquals(AccountSyncResult.Synced(SyncCounts(added = 250)), second)
        assertEquals(450, h.stored().size)
        assertEquals(451L, h.folders.get(h.accountId, "INBOX")!!.uidNext)
        assertEquals(450, h.stored().map { it.uid }.toSet().size)
    }

    @Test
    fun `a failing folder does not stop the others and is reported`() = runTest {
        val h = start {
            server.folder("Broken")
            server.deliver("Broken")
            server.deliver("INBOX")
        }
        h.server.failure = { name ->
            MailResult.ServerRejected(com.qtekfun.ultimatemail.domain.mail.RejectionKind.NO, true)
                .takeIf { name.startsWith("folderStatus Broken") }
        }

        val result = h.sync()

        assertEquals(AccountSyncResult.Failed(SyncProblem.SERVER), result)
        assertEquals(1, h.stored().size)
    }

    @Test
    fun `a failing folder list is reported and leaves Room alone`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.server.failure = { name -> MailResult.Timeout.takeIf { name == "listFolders" } }

        val result = h.sync()

        assertEquals(AccountSyncResult.Failed(SyncProblem.TIMEOUT), result)
        assertEquals(1, h.stored().size)
    }

    @Test
    fun `an authentication failure in the middle of a sync asks to sign in again`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.failure =
            { name -> MailResult.AuthenticationFailed.takeIf { name.startsWith("folderStatus") } }

        val result = h.sync()

        assertEquals(AccountSyncResult.ReauthenticationNeeded, result)
        assertEquals(AccountSyncState.ReauthenticationNeeded, h.status.get(h.accountId))
    }

    @Test
    fun `replies are put in the conversation of the message they answer, across runs`() = runTest {
        val h = start {
            server.deliver("INBOX", subject = "Plans", messageId = "<a@x>")
            server.deliver("INBOX", subject = "Unrelated", messageId = "<z@x>")
        }
        h.sync()
        h.server.deliver(
            "INBOX",
            subject = "Re: Plans",
            messageId = "<b@x>",
            inReplyTo = "<a@x>",
            references = listOf("<a@x>")
        )

        h.sync()

        val byId = h.stored().associateBy { it.messageId }
        assertEquals(byId.getValue("<a@x>").threadId, byId.getValue("<b@x>").threadId)
        assertTrue(byId.getValue("<z@x>").threadId != byId.getValue("<a@x>").threadId)
        assertEquals(listOf("<a@x>"), byId.getValue("<b@x>").referenceIds)
        assertEquals("<a@x>", byId.getValue("<b@x>").inReplyTo)
    }

    @Test
    fun `a late message that joins two conversations merges them in Room`() = runTest {
        val h = start {
            server.deliver("INBOX", subject = "Left", messageId = "<l@x>")
            server.deliver("INBOX", subject = "Right", messageId = "<r@x>")
        }
        h.sync()
        val before = h.stored().associateBy { it.messageId }
        assertTrue(before.getValue("<l@x>").threadId != before.getValue("<r@x>").threadId)
        h.server.deliver(
            "INBOX",
            subject = "Both",
            messageId = "<j@x>",
            references = listOf("<l@x>", "<r@x>")
        )

        h.sync()

        assertEquals(1, h.stored().map { it.threadId }.toSet().size)
    }

    @Test
    fun `a stored thread id that disagrees with the headers is corrected`() = runTest {
        val h = start {
            server.deliver("INBOX", subject = "Plans", messageId = "<a@x>")
        }
        h.sync()
        val stored = h.stored().single()
        h.messages.setThreadId(stored.id, "something-else")
        h.server.deliver("INBOX", messageId = "<b@x>")

        h.sync()

        assertTrue(h.messages.getById(stored.id)!!.threadId.startsWith("local:"))
    }

    @Test
    fun `gmail thread ids and labels are stored and label changes reconciled`() = runTest {
        val h = start {
            server.deliver(
                "INBOX",
                gmail = GmailMetadata(threadId = 77, messageId = 9, labels = listOf("Work"))
            )
        }
        h.sync()
        val first = h.stored().single()
        assertEquals("gmail:${h.accountId}:77", first.threadId)
        assertEquals(9L, first.gmailMessageId)
        assertEquals(listOf("Work"), first.labels)
        h.server.folder("INBOX").messages[1] = h.server.folder("INBOX").messages.getValue(1)
            .copy(gmail = GmailMetadata(77, 9, listOf("Work", "Later")))

        h.sync()
        val again = h.stored().single()
        assertEquals(listOf("Work", "Later"), again.labels)

        // The stored Gmail id also carries the thread across runs.
        h.server.deliver("INBOX", gmail = GmailMetadata(77, 10, listOf("Work")))
        h.sync()
        assertEquals(setOf("gmail:${h.accountId}:77"), h.stored().map { it.threadId }.toSet())
    }

    @Test
    fun `a message without a Date counts as received now`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.folder("INBOX").messages[1] =
            h.server.folder("INBOX").messages.getValue(1).copy(date = null)

        h.sync()

        assertEquals(h.clock.now, h.stored().single().sentAt)
    }

    @Test
    fun `pending flag changes are applied again over what the server says`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.queue.enqueue(
            NewOperation(
                h.accountId,
                OperationType.SET_FLAGS,
                "INBOX",
                1,
                FlagChange(seen = true).encode()
            )
        )
        h.executorOffline()

        h.sync()

        val message = h.stored().single()
        assertTrue(message.seen, "the user's change still shows")
        assertTrue(message.pendingSync)
        assertFalse(h.server.folder("INBOX").messages.getValue(1).flags.seen)
    }

    @Test
    fun `the pending indicator is cleared when nothing is queued for the message`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.sync()
        h.marker.mark(h.accountId, "INBOX", 1)

        h.sync()

        assertFalse(h.stored().single().pendingSync)
    }

    @Test
    fun `the queue is pushed before each folder is pulled`() = runTest {
        val h = start {
            server.folder("Archive")
            server.deliver("INBOX")
            server.deliver("Archive")
        }
        h.sync()
        h.queue.enqueue(
            NewOperation(
                h.accountId,
                OperationType.SET_FLAGS,
                "INBOX",
                1,
                FlagChange(seen = true).encode()
            )
        )
        h.server.log.clear()

        h.sync()

        val log = h.server.log
        val push = log.indexOfFirst { it.startsWith("setFlags INBOX") }
        val firstPull = log.indexOf("listFolders")
        assertTrue(push in 0 until firstPull, "push first: $log")
        assertTrue(h.server.folder("INBOX").messages.getValue(1).flags.seen)
        assertTrue(h.stored().single().seen)
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `one session serves the whole account sync`() = runTest {
        val h = start {
            server.folder("Archive")
            server.deliver("INBOX")
        }
        h.sync()
        h.queue.enqueue(
            NewOperation(
                h.accountId,
                OperationType.SET_FLAGS,
                "INBOX",
                1,
                FlagChange(seen = true).encode()
            )
        )
        h.connector.connects.clear()

        h.sync()

        assertEquals(1, h.connector.connects.size)
        assertTrue(h.connector.sessions.last().closed)
    }

    private suspend fun EngineHarness.collectNotices(): List<SyncNotice> {
        val seen = mutableListOf<SyncNotice>()
        withTimeoutOrNull(1) { notices.notices.collect { seen += it } }
        return seen
    }

    private suspend fun EngineHarness.executorOffline() {
        // Make the push fail with a network error so queued operations stay queued.
        server.failure = { name ->
            MailResult.NetworkUnavailable.takeIf {
                name.startsWith("setFlags") ||
                    name.startsWith("move")
            }
        }
    }
}
