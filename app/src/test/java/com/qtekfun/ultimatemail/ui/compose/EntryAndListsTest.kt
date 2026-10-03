// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.compose.ComposeHarness
import com.qtekfun.ultimatemail.domain.compose.DraftListItem
import com.qtekfun.ultimatemail.domain.compose.IncomingCompose
import com.qtekfun.ultimatemail.domain.compose.OutboxReason
import com.qtekfun.ultimatemail.domain.compose.OutboxState
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryAndListsTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun tearDown() {
        harness?.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.setUp(signature: String = ""): ComposeHarness {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = ComposeHarness(this)
        harness = h
        h.addAccount(
            account().copy(signature = signature, signatureEnabled = signature.isNotEmpty())
        )
        return h
    }

    private class Entry(
        val vm: ComposeEntryViewModel,
        val notices: NoticeCenter,
        val entry: ComposeEntry
    )

    private fun entryOf(h: ComposeHarness): Entry {
        val notices = NoticeCenter(h.scheduler)
        val entry = ComposeEntry()
        val vm = ComposeEntryViewModel(
            entry,
            h.engine,
            h.attachments,
            AccountListing(h.db),
            h.state,
            notices
        )
        return Entry(vm, notices, entry)
    }

    @Test
    fun `a reply from the reader makes the draft and publishes its id`() = runTest {
        val h = setUp(signature = "Ana")
        val e = entryOf(h)
        val message = h.receive()

        e.vm.opened.test {
            QueuedComposeLauncher(e.entry)
                .start(ComposeRequest(h.accountId, "INBOX", message, ComposeMode.REPLY))
            val draft = h.repository.get(awaitItem())!!
            assertEquals("Re: Hello", draft.subject)
            assertTrue("-- \nAna" in draft.body)
        }
    }

    @Test
    fun `a reply to a message that is gone says so`() = runTest {
        val h = setUp()
        val e = entryOf(h)

        e.vm.start(
            ComposeStart.Message(ComposeRequest(h.accountId, "INBOX", 12345, ComposeMode.REPLY))
        )

        assertEquals(NoticeKind.COMPOSE_FAILED, e.notices.notice.value?.kind)
    }

    @Test
    fun `a new message and an existing draft open by id`() = runTest {
        val h = setUp()
        val e = entryOf(h)
        val existing = h.writeTo("bob@example.test")

        e.vm.opened.test {
            e.vm.start(ComposeStart.New(h.accountId))
            assertNotNull(h.repository.get(awaitItem()))
            e.vm.start(ComposeStart.Draft(existing.id))
            assertEquals(existing.id, awaitItem())
        }
    }

    @Test
    fun `a mailto fills recipients subject body and keeps the text above the signature`() =
        runTest {
            val h = setUp(signature = "Ana")
            val e = entryOf(h)
            h.attachmentSource.put("content://p/1", "a.txt", "text/plain", ByteArray(5))

            e.vm.opened.test {
                e.vm.start(
                    ComposeStart.Incoming(
                        IncomingCompose(
                            to = listOf(MailAddress("bob@example.test")),
                            cc = listOf(MailAddress("cy@example.test")),
                            subject = "Hi",
                            body = "Hello",
                            attachments = listOf("content://p/1", "content://gone/2")
                        )
                    )
                )
                val draft = h.repository.get(awaitItem())!!
                assertEquals(listOf("bob@example.test"), draft.to.map { it.address })
                assertEquals(listOf("cy@example.test"), draft.cc.map { it.address })
                assertEquals("Hi", draft.subject)
                assertEquals("Hello\n-- \nAna", draft.body)
                assertEquals(1, h.attachments.list(draft.id).size)
            }
            assertEquals(NoticeKind.ATTACHMENTS_SKIPPED, e.notices.notice.value?.kind)
        }

    @Test
    fun `with several accounts and none shown the user is asked which one`() = runTest {
        val h = setUp()
        val second = h.engineHarness.addAccount(account("bea@example.test"))
        val e = entryOf(h)

        e.vm.opened.test {
            e.vm.start(ComposeStart.Incoming(IncomingCompose(subject = "Hi")))
            val waiting = e.vm.choosing.value!!
            assertEquals(2, waiting.accounts.size)
            expectNoEvents()

            e.vm.chooseAccount(second)
            val draft = h.repository.get(awaitItem())!!
            assertEquals(second, draft.accountId)
            assertEquals("Hi", draft.subject)
        }
        assertNull(e.vm.choosing.value)
    }

    @Test
    fun `with several accounts the one on screen is used without asking`() = runTest {
        val h = setUp()
        val second = h.engineHarness.addAccount(account("bea@example.test"))
        val e = entryOf(h)
        e.vm.setShownAccount(second)

        e.vm.opened.test {
            e.vm.start(ComposeStart.Incoming(IncomingCompose()))
            assertEquals(second, h.repository.get(awaitItem())!!.accountId)
        }
        assertNull(e.vm.choosing.value)
    }

    @Test
    fun `dismissing the account question starts nothing`() = runTest {
        val h = setUp()
        h.engineHarness.addAccount(account("bea@example.test"))
        val e = entryOf(h)
        e.vm.start(ComposeStart.Incoming(IncomingCompose()))

        e.vm.dismissChoice()

        assertNull(e.vm.choosing.value)
        assertTrue(h.db.draftDao().observeCountAll(DraftState.EDITING).first() == 0)
    }

    @Test
    fun `a send that fails for good raises a notice, and one that is only queued does not`() =
        runTest {
            val h = setUp()
            val e = entryOf(h)
            val draft = h.writeTo("bob@example.test")
            h.send(draft.id)
            val op = h.db.pendingOperationDao()
                .forDraft(h.accountId, draft.id, OperationType.SEND).single()
            assertNull(e.notices.notice.value)
            assertEquals(1, e.vm.outboxCount.value)

            h.db.pendingOperationDao().markFailed(op.id, "server_rejected")

            assertEquals(NoticeKind.SEND_FAILED, e.notices.notice.value?.kind)
        }

    @Test
    fun `the outbox lists messages with their state and offers the engine actions`() = runTest {
        val h = setUp()
        val draft = h.writeTo("bob@example.test", "cy@example.test")
        h.send(draft.id)
        val op = h.db.pendingOperationDao()
            .forDraft(h.accountId, draft.id, OperationType.SEND).single()
        val entry = ComposeEntry()
        val vm = OutboxViewModel(h.state, h.actions, entry)

        vm.state.test {
            val waiting = expectMostRecentItem().rows.single()
            assertEquals("Subject", waiting.subject)
            assertEquals("bob@example.test", waiting.recipient)
            assertEquals(2, waiting.recipientCount)
            assertTrue(waiting.status is OutboxRowStatus.Waiting)

            h.db.pendingOperationDao().markFailed(op.id, "server_rejected")
            val failed = expectMostRecentItem().rows.single()
            assertEquals(OutboxRowStatus.Failed(OutboxReason.SERVER_REJECTED), failed.status)

            vm.edit(draft.id)
            assertEquals(ComposeStart.Draft(draft.id), entry.starts.first())
            assertEquals(DraftState.EDITING, h.repository.get(draft.id)!!.state)
            assertTrue(expectMostRecentItem().rows.isEmpty())
        }
    }

    @Test
    fun `a message that may have been sent cannot be edited or discarded and says why`() = runTest {
        val h = setUp()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        val op = h.db.pendingOperationDao()
            .forDraft(h.accountId, draft.id, OperationType.SEND).single()
        h.db.pendingOperationDao().markStarted(op.id, h.clock.instant())
        val vm = OutboxViewModel(h.state, h.actions, ComposeEntry())

        vm.state.test {
            skipItems(1)
            vm.edit(draft.id)
            assertEquals(OutboxPrompt.MaybeSent, expectMostRecentItem().prompt)
            vm.dismissPrompt()
            assertNull(expectMostRecentItem().prompt)

            vm.requestDiscard(draft.id)
            assertEquals(OutboxPrompt.ConfirmDiscard(draft.id), expectMostRecentItem().prompt)
            vm.confirmDiscard()
            assertEquals(OutboxPrompt.MaybeSent, expectMostRecentItem().prompt)
        }
        assertNotNull(h.repository.get(draft.id))
    }

    @Test
    fun `discarding a failed message after confirming removes it`() = runTest {
        val h = setUp()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        val op = h.db.pendingOperationDao()
            .forDraft(h.accountId, draft.id, OperationType.SEND).single()
        h.db.pendingOperationDao().markFailed(op.id, "server_rejected")
        val vm = OutboxViewModel(h.state, h.actions, ComposeEntry())

        vm.state.test {
            skipItems(1)
            vm.requestDiscard(draft.id)
            vm.confirmDiscard()
            var last = expectMostRecentItem()
            while (last.rows.isNotEmpty()) last = awaitItem()
            assertNull(last.prompt)
        }
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `reason codes map to reasons and unknown ones still have a text`() {
        assertEquals(OutboxReason.NETWORK, OutboxReason.of("network"))
        assertEquals(OutboxReason.TIMEOUT, OutboxReason.of("timeout"))
        assertEquals(OutboxReason.AUTH_REQUIRED, OutboxReason.of("auth_required"))
        assertEquals(OutboxReason.CERTIFICATE, OutboxReason.of("certificate"))
        assertEquals(OutboxReason.SERVER_REJECTED, OutboxReason.of("server_rejected"))
        assertEquals(OutboxReason.SERVER_BUSY, OutboxReason.of("server_busy"))
        assertEquals(OutboxReason.CONFIRM_SENT, OutboxReason.of("confirm_sent"))
        assertEquals(OutboxReason.ATTACHMENT_MISSING, OutboxReason.of("attachment_missing"))
        assertEquals(OutboxReason.INTERNAL, OutboxReason.of("bad_payload"))
        assertEquals(OutboxReason.INTERNAL, OutboxReason.of("no_account"))
        assertEquals(OutboxReason.INTERNAL, OutboxReason.of("no_operation"))
        assertEquals(OutboxReason.OTHER, OutboxReason.of("something_new"))
        assertEquals(OutboxReason.OTHER, OutboxReason.of(null))
    }

    @Test
    fun `queue states become row states`() {
        assertEquals(
            OutboxRowStatus.Waiting(OutboxReason.NETWORK, null),
            OutboxState.Queued(1, null, "network").toStatus()
        )
        assertEquals(
            OutboxRowStatus.Waiting(null, null),
            OutboxState.Queued(0, null, null).toStatus()
        )
        assertEquals(OutboxRowStatus.Sending, OutboxState.Sending.toStatus())
        assertEquals(
            OutboxRowStatus.Failed(OutboxReason.OTHER),
            OutboxState.Failed("unknown").toStatus()
        )
    }

    @Test
    fun `the drafts list shows local drafts, opens them and deletes after confirming`() = runTest {
        val h = setUp()
        val a = h.writeTo("bob@example.test")
        val entry = ComposeEntry()
        val vm =
            DraftsViewModel(h.state, h.engine, h.db.messageDao(), entry, Dispatchers.Unconfined)

        vm.state.test {
            vm.show(h.accountId)
            val items = expectMostRecentItem().items
            assertEquals(a.id, (items.single() as DraftListItem.Local).draft.id)

            vm.open(a.id)
            assertEquals(ComposeStart.Draft(a.id), entry.starts.first())

            vm.requestDelete(a.id)
            assertEquals(a.id, expectMostRecentItem().confirmingDelete)
            vm.dismissDelete()
            assertNotNull(h.repository.get(a.id))

            vm.requestDelete(a.id)
            vm.confirmDelete()
            assertTrue(expectMostRecentItem().items.isEmpty())
        }
        assertNull(h.repository.get(a.id))
    }
}
