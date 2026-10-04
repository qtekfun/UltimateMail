// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeEngineTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(signature: String = ""): ComposeHarness {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount(
            account().copy(
                signature = signature,
                signatureEnabled = signature.isNotEmpty()
            )
        )
        return h
    }

    private fun request(h: ComposeHarness, id: Long, mode: ComposeMode) =
        ComposeRequest(h.accountId, "INBOX", id, mode)

    @Test
    fun `a new message starts empty with the signature of the account`() = runTest {
        val h = start(signature = "Ana\nCEO")

        val draft = h.engine.newMessage(h.accountId)!!

        assertEquals(DraftKind.NEW, draft.kind)
        assertEquals(DraftState.EDITING, draft.state)
        assertEquals("\n-- \nAna\nCEO", draft.body)
        assertEquals("Ana\nCEO", draft.signatureText)
        assertTrue(draft.dirty && draft.to.isEmpty() && draft.references.isEmpty())
        assertNull(draft.source)
        assertEquals(draft, h.engine.open(draft.id))
    }

    @Test
    fun `a new message may start with recipients and gets no delimiter without a signature`() =
        runTest {
            val h = start()

            val draft = h.engine.newMessage(h.accountId, listOf(MailAddress("bob@example.test")))!!

            assertEquals("", draft.body)
            assertNull(draft.signatureText)
            assertEquals(listOf("bob@example.test"), draft.to.map { it.address })
        }

    @Test
    fun `a new message for an unknown account is not created`() = runTest {
        val h = start()

        assertNull(h.engine.newMessage(999))
    }

    @Test
    fun `every draft gets its own key`() = runTest {
        val h = start()

        assertTrue(h.engine.newMessage(h.accountId)!!.key != h.engine.newMessage(h.accountId)!!.key)
    }

    @Test
    fun `a reply goes to the sender, quotes the message and keeps the thread headers`() = runTest {
        val h = start(signature = "Ana")
        val id = h.receive()

        val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!

        assertEquals(DraftKind.REPLY, draft.kind)
        assertEquals(listOf("bob@example.test"), draft.to.map { it.address })
        assertEquals("Re: Hello", draft.subject)
        assertEquals("<1@example.test>", draft.inReplyTo)
        assertEquals(listOf("<r0@example.test>", "<1@example.test>"), draft.references)
        assertEquals(DraftSource(h.accountId, "INBOX", 1, "<1@example.test>"), draft.source)
        // The user's line, the signature, then the quote.
        assertTrue(draft.body.startsWith("\n\n-- \nAna\n\nOn "), draft.body)
        assertTrue(draft.body.endsWith("wrote:\n> Hi Ana,\n> see you."), draft.body)
        assertEquals(draft, h.repository.get(draft.id))
    }

    @Test
    fun `a signature below the quote goes there when the account says so`() = runTest {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount(account().copy(signature = "Ana", signatureBeforeQuote = false))
        val id = h.receive()

        val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!

        assertTrue(draft.body.endsWith("> see you.\n\n-- \nAna"), draft.body)
        assertFalse(draft.signatureBeforeQuote)
    }

    @Test
    fun `reply all takes the others from To and Cc without the user`() = runTest {
        val h = start()
        val id = h.receive(
            to = listOf("ana@example.test", "cy@example.test"),
            cc = listOf("CY@example.test", "di@example.test")
        )

        val draft = h.engine.start(request(h, id, ComposeMode.REPLY_ALL))!!

        assertEquals(DraftKind.REPLY_ALL, draft.kind)
        assertEquals(listOf("bob@example.test", "cy@example.test"), draft.to.map { it.address })
        assertEquals(listOf("di@example.test"), draft.cc.map { it.address })
    }

    @Test
    fun `a forward has no recipients and no thread headers and shows the original in full`() =
        runTest {
            val h = start(signature = "Ana")
            val id = h.receive(subject = "Fwd: Hello")

            val draft = h.engine.start(request(h, id, ComposeMode.FORWARD))!!

            assertEquals(DraftKind.FORWARD, draft.kind)
            assertEquals("Fwd: Hello", draft.subject)
            assertTrue(draft.to.isEmpty())
            assertNull(draft.inReplyTo)
            assertTrue(draft.references.isEmpty())
            assertTrue(
                draft.body.startsWith("\n\n-- \nAna\n\n---------- Forwarded message ---------")
            )
            assertTrue(draft.body.contains("Subject: Fwd: Hello\n"))
            assertTrue(draft.body.endsWith("\n\nHi Ana,\nsee you."))
        }

    @Test
    fun `a message that only has html is quoted as text`() = runTest {
        val h = start()
        val id = h.receive(body = null)
        h.db.messageDao().setBody(h.accountId, "INBOX", 1, null, "<p>Hi <b>Ana</b></p><p>bye</p>")

        val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!

        assertTrue(draft.body.endsWith("wrote:\n> Hi Ana\n> bye"), draft.body)
    }

    @Test
    fun `a message whose body was never downloaded is answered with just the attribution`() =
        runTest {
            val h = start()
            val id = h.receive(body = null)

            val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!

            assertTrue(draft.body.trim().endsWith("wrote:"), draft.body)
            assertTrue(draft.body.lines().none { it.startsWith(">") })
        }

    @Test
    fun `a Spanish reply is written in Spanish`() = runTest {
        val h = start()
        h.quotes = SPANISH_QUOTES
        val id = h.receive()

        val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!

        assertTrue(Regex("(?s).*El .* escribió:\n> Hi Ana.*").matches(draft.body), draft.body)
    }

    @Test
    fun `nothing starts from a message or an account that is gone`() = runTest {
        val h = start()
        val id = h.receive()

        assertNull(h.engine.start(ComposeRequest(h.accountId, "INBOX", 999, ComposeMode.REPLY)))
        assertNull(h.engine.start(ComposeRequest(999, "INBOX", id, ComposeMode.REPLY)))
    }

    @Test
    fun `changing the sender swaps only the signature block and moves the draft`() = runTest {
        val h = start(signature = "Ana\nCEO")
        val work = h.db.accountDao().insert(
            account("work@example.test").copy(signature = "Work Inc.", signatureBeforeQuote = false)
        )
        val id = h.receive()
        val draft = h.engine.start(request(h, id, ComposeMode.REPLY))!!
        h.engine.save(
            draft.id,
            DraftEdit(
                draft.to,
                emptyList(),
                emptyList(),
                "S",
                "Thanks!\n\n" + draft.body.trimStart()
            )
        )
        val edited = h.repository.get(draft.id)!!

        assertEquals(DraftChange.SAVED, h.engine.changeSender(draft.id, work))

        val moved = h.repository.get(draft.id)!!
        assertEquals(work, moved.accountId)
        assertEquals("Work Inc.", moved.signatureText)
        assertFalse(moved.signatureBeforeQuote)
        assertEquals(edited.body.replace("Ana\nCEO", "Work Inc."), moved.body)
        assertTrue(moved.body.startsWith("Thanks!"))
        assertEquals(edited.revision + 1, moved.revision)
        assertNull(moved.serverMessageId)
    }

    @Test
    fun `changing the sender to an account without a signature removes the block`() = runTest {
        val h = start(signature = "Ana")
        val plain = h.db.accountDao().insert(account("plain@example.test"))
        val draft = h.engine.newMessage(h.accountId)!!

        h.engine.changeSender(draft.id, plain)

        val moved = h.repository.get(draft.id)!!
        assertFalse("-- " in moved.body)
        assertNull(moved.signatureText)
    }

    @Test
    fun `changing the sender to the same account changes nothing`() = runTest {
        val h = start(signature = "Ana")
        val draft = h.engine.newMessage(h.accountId)!!

        assertEquals(DraftChange.SAVED, h.engine.changeSender(draft.id, h.accountId))

        assertEquals(draft, h.repository.get(draft.id))
    }

    @Test
    fun `the sender of a missing draft, to a missing account or of a queued draft is refused`() =
        runTest {
            val h = start()
            val other = h.db.accountDao().insert(account("other@example.test"))
            val draft = h.writeTo("bob@example.test")

            assertEquals(DraftChange.MISSING, h.engine.changeSender(999, other))
            assertEquals(DraftChange.MISSING, h.engine.changeSender(draft.id, 999))
            h.send(draft.id)
            assertEquals(DraftChange.NOT_EDITABLE, h.engine.changeSender(draft.id, other))
            assertEquals(h.accountId, h.repository.get(draft.id)!!.accountId)
        }

    @Test
    fun `changing the sender drops the server copy of the old account`() = runTest {
        val h = start()
        val other = h.db.accountDao().insert(account("other@example.test"))
        val draft = h.writeTo("bob@example.test")
        // The copy this device uploaded earlier has been synced down as a message of Drafts.
        h.db.draftDao().markUploaded(draft.id, "<um-draft.k.1@example.test>", 0)
        h.db.messageDao().upsert(
            listOf(
                message(h.accountId, 4, "Drafts").copy(messageId = "<um-draft.k.1@example.test>")
            )
        )

        h.engine.changeSender(draft.id, other)

        val queued = h.db.pendingOperationDao().all(h.accountId).single()
        assertEquals(OperationType.DELETE, queued.type)
        assertEquals("Drafts" to 4L, queued.folderPath to queued.uid)
    }

    @Test
    fun `discarding removes the draft, its files and what waits to be saved`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.files.files["/outbox/${draft.id}/1-a"] = byteArrayOf(1)
        h.serverSync.request(draft.id)

        h.engine.discard(draft.id)

        assertNull(h.repository.get(draft.id))
        assertEquals(listOf(draft.id), h.files.deletedDrafts)
        assertTrue(h.db.pendingOperationDao().all(h.accountId).isEmpty())
        // Discarding what is gone is harmless.
        h.engine.discard(draft.id)
    }

    @Test
    fun `discarding a draft being written also queues the delete of its server copy`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.db.draftDao().markUploaded(draft.id, "<um-draft.k.1@example.test>", 0)
        h.db.messageDao().upsert(
            listOf(
                message(h.accountId, 4, "Drafts")
                    .copy(messageId = "<um-draft.k.1@example.test>")
            )
        )

        assertTrue(h.engine.discard(draft.id, onlyEditing = true))

        assertNull(h.repository.get(draft.id))
        val queued = h.db.pendingOperationDao().all(h.accountId).single()
        assertEquals(OperationType.DELETE, queued.type)
        assertEquals("Drafts" to 4L, queued.folderPath to queued.uid)
    }

    @Test
    fun `a message already in the outbox or gone is not discarded as a draft`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)

        assertFalse(h.engine.discard(draft.id, onlyEditing = true))
        assertFalse(h.engine.discard(999, onlyEditing = true))

        assertNotNull(h.repository.get(draft.id))
    }

    @Test
    fun `observing a draft follows its edits`() = runTest {
        val h = start()
        val draft = h.engine.newMessage(h.accountId)!!

        h.engine.observe(draft.id).test {
            assertEquals("", awaitItem()!!.subject)
            h.engine.save(
                draft.id,
                DraftEdit(emptyList(), emptyList(), emptyList(), "New subject", "")
            )
            assertEquals("New subject", awaitItem()!!.subject)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `autosave stores a burst of edits once, after the pause`() = runTest {
        val h = start()
        val draft = h.engine.newMessage(h.accountId)!!
        val edits = Channel<DraftEdit>(Channel.UNLIMITED)
        val job = backgroundScope.launch {
            h.engine.autosave(draft.id, edits.receiveAsFlow())
        }

        fun edit(text: String) = DraftEdit(emptyList(), emptyList(), emptyList(), "s", text)
        edits.send(edit("H"))
        advanceTimeBy(300)
        edits.send(edit("He"))
        advanceTimeBy(300)
        edits.send(edit("Hel"))
        advanceTimeBy(ComposeEngine.AUTOSAVE_DEBOUNCE_MILLIS - 1)
        assertEquals("", h.repository.get(draft.id)!!.body)

        advanceTimeBy(2)

        val saved = h.repository.get(draft.id)!!
        assertEquals("Hel", saved.body)
        assertEquals(1, saved.revision)
        job.cancel()
    }

    @Test
    fun `autosave refreshes the single server save that is waiting`() = runTest {
        val h = start()
        val draft = h.engine.newMessage(h.accountId)!!
        val edits = Channel<DraftEdit>(Channel.UNLIMITED)
        backgroundScope.launch { h.engine.autosave(draft.id, edits.receiveAsFlow(), 100) }

        edits.send(DraftEdit(emptyList(), emptyList(), emptyList(), "s", "one"))
        advanceTimeBy(200)
        edits.send(DraftEdit(emptyList(), emptyList(), emptyList(), "s", "two"))
        advanceTimeBy(200)

        val operation = h.db.pendingOperationDao().all(h.accountId).single()
        assertEquals(OperationType.SAVE_DRAFT, operation.type)
        assertEquals("two", OutgoingPayload.decode(operation.payload)!!.text)
    }

    @Test
    fun `autosave of a draft that was queued for sending changes nothing`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        val edits = Channel<DraftEdit>(Channel.UNLIMITED)
        backgroundScope.launch { h.engine.autosave(draft.id, edits.receiveAsFlow(), 10) }

        edits.send(DraftEdit(emptyList(), emptyList(), emptyList(), "late", "late"))
        advanceUntilIdle()

        assertEquals("Subject", h.repository.get(draft.id)!!.subject)
        assertNotNull(h.engine.open(draft.id))
    }
}
