// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.domain.search.SearchQueryParser
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure
import com.qtekfun.ultimatemail.domain.search.ServerSearchResult
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchOnServerTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    /** An account whose INBOX was synced with messages "one", "two" and "three". */
    private suspend fun TestScope.start(): Pair<EngineHarness, SearchOnServer> {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        listOf("one", "two", "three").forEach { h.server.deliver("INBOX", it) }
        h.addAccount()
        h.engine.sync(h.accountId)
        h.server.log.clear()
        return h to SearchOnServer(h.db.accountDao(), h.folders, h.messages, h.sessions, h.clock)
    }

    private fun EngineHarness.inbox() = SearchScope.Folder(accountId, "INBOX")

    private fun query(text: String) = SearchQueryParser.parse(text)

    private suspend fun EngineHarness.subjectsOf(result: ServerSearchResult): List<String> =
        (result as ServerSearchResult.Found).messageIds.map { messages.getById(it)!!.subject }

    @Test
    fun `hits the device does not have are stored as messages of their folder`() = runTest {
        val (h, search) = start()
        val uid = h.server.deliver("INBOX", "missing invoice", sentAt = Instant.ofEpochSecond(5))

        val result = search.search(query("invoice"), h.inbox())

        assertEquals(listOf("missing invoice"), h.subjectsOf(result))
        assertEquals(1, (result as ServerSearchResult.Found).added)
        val stored = h.messages.get(h.accountId, "INBOX", uid)!!
        assertEquals("bob@example.test", stored.senderAddress)
        assertEquals(listOf("ana@example.test"), stored.toAddresses)
        assertNull(stored.bodyText)
        assertEquals(1, h.server.logged("fetchHeadersByUid").size)
    }

    @Test
    fun `hits already stored are returned without fetching them again`() = runTest {
        val (h, search) = start()

        val result = search.search(query("two"), h.inbox())

        assertEquals(listOf("two"), h.subjectsOf(result))
        assertEquals(0, (result as ServerSearchResult.Found).added)
        assertTrue(h.server.logged("fetchHeadersByUid").isEmpty())
        assertEquals(3, h.messages.serverUids(h.accountId, "INBOX").size)
    }

    @Test
    fun `only the missing headers are fetched, in one call`() = runTest {
        val (h, search) = start()
        val a = h.server.deliver("INBOX", "report a")
        val b = h.server.deliver("INBOX", "report b")
        val c = h.server.deliver("INBOX", "report c")

        search.search(query("report"), h.inbox())

        assertEquals(
            listOf("fetchHeadersByUid INBOX [$a, $b, $c]"),
            h.server.logged("fetchHeadersByUid")
        )
    }

    @Test
    fun `a stored hit opens like any message and its body is loaded on demand`() = runTest {
        val (h, search) = start()
        val uid = h.server.deliver("INBOX", "remote only")
        h.server.folder("INBOX").bodies[uid] = MessageBody("the text", null, emptyList())
        val id = (search.search(query("remote"), h.inbox()) as ServerSearchResult.Found)
            .messageIds.single()

        val body =
            LoadMessageBody(h.messages, h.sessions, BodyStore(h.messages, h.db.attachmentDao()))(id)

        assertEquals(BodyResult.Loaded("the text", null), body)
    }

    @Test
    fun `the sync state of the folder is left alone`() = runTest {
        val (h, search) = start()
        h.server.deliver("INBOX", "late hit")
        val before = h.folders.get(h.accountId, "INBOX")!!

        search.search(query("late"), h.inbox())

        assertEquals(before, h.folders.get(h.accountId, "INBOX"))
    }

    @Test
    fun `the next sync does not duplicate a stored hit and still brings what is newer`() = runTest {
        val (h, search) = start()
        val hit = h.server.deliver("INBOX", "late hit")
        search.search(query("late"), h.inbox())
        val newer = h.server.deliver("INBOX", "newer than the hit")

        h.engine.sync(h.accountId)

        assertEquals(listOf(1L, 2L, 3L, hit, newer), h.messages.serverUids(h.accountId, "INBOX"))
        assertEquals(newer + 1, h.folders.get(h.accountId, "INBOX")!!.uidNext)
    }

    @Test
    fun `a stored hit that left the server is removed by the next sync`() = runTest {
        val (h, search) = start()
        val hit = h.server.deliver("INBOX", "late hit")
        search.search(query("late"), h.inbox())
        h.server.deliver("INBOX", "newer")
        h.server.expunge("INBOX", hit)

        // The first sync fetches what is newer; the one after it reconciles the older rows.
        h.engine.sync(h.accountId)
        h.engine.sync(h.accountId)

        assertFalse(hit in h.messages.serverUids(h.accountId, "INBOX"))
    }

    @Test
    fun `a hit joins the conversation it replies to`() = runTest {
        val (h, search) = start()
        val original = h.messages.get(h.accountId, "INBOX", 1)!!
        val reply = h.server.deliver("INBOX", "Re: one", inReplyTo = original.messageId)

        search.search(query("one"), h.inbox())

        assertEquals(original.threadId, h.messages.get(h.accountId, "INBOX", reply)!!.threadId)
    }

    @Test
    fun `a folder never synced gets only its validity recorded`() = runTest {
        val (h, search) = start()
        h.server.deliver("Archive", "old contract")
        h.folders.insertNew(
            listOf(FolderEntity(h.accountId, "Archive", "Archive", syncEnabled = false))
        )

        search.search(query("contract"), SearchScope.Folder(h.accountId, "Archive"))

        val folder = h.folders.get(h.accountId, "Archive")!!
        assertEquals(h.server.folder("Archive").uidValidity, folder.uidValidity)
        assertNull(folder.uidNext)
        assertNull(folder.highestModSeq)
        assertEquals(listOf(1L), h.messages.serverUids(h.accountId, "Archive"))
    }

    @Test
    fun `a folder renumbered on the server is skipped, not filled with the wrong messages`() =
        runTest {
            val (h, search) = start()
            h.server.renumber("INBOX")

            val result = search.search(query("one"), h.inbox())

            assertEquals(
                ServerSearchResult.Found(emptyList(), added = 0, incomplete = true),
                result
            )
            assertTrue(h.server.logged("search").isEmpty())
        }

    @Test
    fun `a folder scope searches that folder only`() = runTest {
        val (h, search) = start()
        h.server.folder("Sent", MailFolderRole.SENT)
        h.server.deliver("Sent", "one sent")
        h.engine.sync(h.accountId)
        h.server.log.clear()

        search.search(query("one"), h.inbox())

        assertEquals(listOf("search INBOX"), h.server.logged("search"))
    }

    @Test
    fun `an account scope searches the synced folders but not Trash or Spam`() = runTest {
        val (h, search) = start()
        h.server.folder("Sent", MailFolderRole.SENT)
        h.server.folder("Trash", MailFolderRole.TRASH)
        h.server.folder("Spam", MailFolderRole.JUNK)
        h.server.folder("Archive")
        h.folders.insertNew(
            listOf(FolderEntity(h.accountId, "Archive", "Archive", syncEnabled = false))
        )
        h.engine.sync(h.accountId)
        h.server.log.clear()

        search.search(query("one"), SearchScope.Account(h.accountId))

        assertEquals(
            setOf("search INBOX", "search Sent"),
            h.server.logged("search").toSet()
        )
    }

    @Test
    fun `with an All Mail folder that is the only one searched`() = runTest {
        val (h, search) = start()
        h.server.folder("[Gmail]/All Mail", MailFolderRole.ALL_MAIL)
        h.server.deliver("[Gmail]/All Mail", "one in all mail")
        h.engine.sync(h.accountId)
        h.server.log.clear()

        val result = search.search(query("one"), SearchScope.Account(h.accountId))

        assertEquals(listOf("search [Gmail]/All Mail"), h.server.logged("search"))
        assertEquals(listOf("one in all mail"), h.subjectsOf(result))
    }

    @Test
    fun `in and label search the folders they name, trash included`() = runTest {
        val (h, search) = start()
        h.server.folder("Trash", MailFolderRole.TRASH)
        h.server.deliver("Trash", "one trashed")
        h.server.folder("Work/Invoices")
        h.engine.sync(h.accountId)
        h.server.log.clear()

        val trash = search.search(query("one in:trash "), SearchScope.AllAccounts)
        val invoices = search.search(query("label:invoices "), SearchScope.AllAccounts)

        assertEquals(listOf("one trashed"), h.subjectsOf(trash))
        assertEquals(listOf("search Trash", "search Work/Invoices"), h.server.logged("search"))
        assertEquals(emptyList<String>(), h.subjectsOf(invoices))
    }

    @Test
    fun `unread and excluded text reach the server as criteria`() = runTest {
        val (h, search) = start()
        h.server.deliver("INBOX", "alpha beta")
        h.server.deliver("INBOX", "alpha gamma", flags = MessageFlags(seen = true))

        val result = search.search(query("alpha -gamma is:unread "), h.inbox())

        assertEquals(listOf("alpha beta"), h.subjectsOf(result))
    }

    @Test
    fun `hits marked deleted on the server are not stored`() = runTest {
        val (h, search) = start()
        val gone = h.server.deliver("INBOX", "doomed", flags = MessageFlags(deleted = true))

        val result = search.search(query("doomed"), h.inbox())

        assertEquals(emptyList<String>(), h.subjectsOf(result))
        assertNull(h.messages.get(h.accountId, "INBOX", gone))
    }

    @Test
    fun `results are the newest first and cover stored and new hits together`() = runTest {
        val (h, search) = start()
        h.server.deliver("INBOX", "hit old", sentAt = Instant.ofEpochSecond(1))
        h.server.deliver("INBOX", "hit new", sentAt = Instant.ofEpochSecond(9_000_000_000))

        val result = search.search(query("hit"), h.inbox())

        assertEquals(listOf("hit new", "hit old"), h.subjectsOf(result))
    }

    @Test
    fun `an offline device gets a clear failure and nothing changes`() = runTest {
        val (h, search) = start()
        h.connector.failure = MailResult.NetworkUnavailable

        val result = search.search(query("one"), h.inbox())

        assertEquals(ServerSearchResult.Failed(ServerSearchFailure.OFFLINE), result)
        assertEquals(3, h.messages.serverUids(h.accountId, "INBOX").size)
    }

    @Test
    fun `a timeout, a refused search and a failed sign in each have their own reason`() = runTest {
        val (h, search) = start()
        h.connector.failure = MailResult.Timeout
        assertEquals(
            ServerSearchResult.Failed(ServerSearchFailure.TIMEOUT),
            search.search(query("one"), h.inbox())
        )

        h.connector.failure = MailResult.AuthenticationFailed
        assertEquals(
            ServerSearchResult.Failed(ServerSearchFailure.AUTHENTICATION),
            search.search(query("one"), h.inbox())
        )

        h.connector.failure = null
        h.server.failure = { call ->
            MailResult.ServerRejected(RejectionKind.BAD, permanent = true).takeIf {
                call.startsWith("search")
            }
        }
        assertEquals(
            ServerSearchResult.Failed(ServerSearchFailure.SERVER),
            search.search(query("one"), h.inbox())
        )
    }

    @Test
    fun `a failure while searching ends the account's run and reports it`() = runTest {
        val (h, search) = start()
        h.server.folder("Sent", MailFolderRole.SENT)
        h.engine.sync(h.accountId)
        h.server.log.clear()
        h.server.failure = { call ->
            MailResult.Timeout.takeIf { call.startsWith("search INBOX") }
        }

        val result = search.search(query("one"), SearchScope.Account(h.accountId))

        assertEquals(ServerSearchResult.Failed(ServerSearchFailure.TIMEOUT), result)
        assertTrue(h.server.logged("search Sent").isEmpty(), "the next folder is not tried")
    }

    @Test
    fun `when one folder fails the others answer and the result is incomplete`() = runTest {
        val (h, search) = start()
        h.server.folder("Sent", MailFolderRole.SENT)
        h.server.deliver("Sent", "one sent")
        h.engine.sync(h.accountId)
        h.server.failure = { call ->
            MailResult.ServerRejected(RejectionKind.NO, permanent = true).takeIf {
                call == "search Sent"
            }
        }

        val result = search.search(query("one"), SearchScope.Account(h.accountId))

        assertEquals(listOf("one"), h.subjectsOf(result))
        assertTrue((result as ServerSearchResult.Found).incomplete)
    }

    @Test
    fun `a scope with nothing to search says so`() = runTest {
        val (h, search) = start()

        assertEquals(
            ServerSearchResult.Failed(ServerSearchFailure.NOTHING_TO_SEARCH),
            search.search(query("one"), SearchScope.Folder(h.accountId, "Nope"))
        )
        assertEquals(
            ServerSearchResult.Failed(ServerSearchFailure.NOTHING_TO_SEARCH),
            search.search(query("one"), SearchScope.Account(9_999))
        )
    }

    @Test
    fun `cancelling stops the search, closes the session and stores nothing`() = runTest {
        val (h, search) = start()
        h.server.deliver("INBOX", "late hit")
        val gate = CompletableDeferred<Unit>()
        h.server.searchGate = gate
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            search.search(query("late"), h.inbox())
        }
        advanceUntilIdle()
        assertTrue(job.isActive, "the search is waiting on the server")

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(h.connector.sessions.isNotEmpty() && h.connector.sessions.all { it.closed })
        assertEquals(3, h.messages.serverUids(h.accountId, "INBOX").size)
    }
}
