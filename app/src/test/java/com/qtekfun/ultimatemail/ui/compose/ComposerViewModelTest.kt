// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.compose.AttachmentLimits
import com.qtekfun.ultimatemail.domain.compose.ComposeHarness
import com.qtekfun.ultimatemail.domain.compose.ServerDraftOpen
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import com.qtekfun.ultimatemail.ui.conversation.noticeCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun tearDown() {
        harness?.close()
        Dispatchers.resetMain()
    }

    private class Fixture(
        val h: ComposeHarness,
        val vm: ComposerViewModel,
        val notices: NoticeCenter,
        val entry: ComposeEntry
    ) {
        val state get() = vm.state.value

        suspend fun sendOps(draftId: Long) = h.db.pendingOperationDao()
            .forDraft(h.accountId, draftId, OperationType.SEND)
    }

    private suspend fun TestScope.start(signature: String = ""): Fixture {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = ComposeHarness(this)
        harness = h
        h.addAccount(
            account().copy(signature = signature, signatureEnabled = signature.isNotEmpty())
        )
        val notices = noticeCenter(h.scheduler)
        val entry = ComposeEntry()
        val vm = ComposerViewModel(
            h.engine,
            h.attachments,
            h.suggestions,
            h.send,
            h.serverSync,
            h.files,
            AccountListing(h.db),
            notices,
            entry,
            backgroundScope,
            Dispatchers.Unconfined
        )
        return Fixture(h, vm, notices, entry)
    }

    private suspend fun Fixture.newDraft(): Long {
        val id = h.engine.newMessage(h.accountId)!!.id
        vm.load(id)
        return id
    }

    private fun Fixture.fillValidMessage() {
        vm.onInput(RecipientKind.TO, "bob@example.test,")
        vm.onSubject("Hello")
        vm.onBody("Hi Bob")
    }

    @Test
    fun `opening a new draft shows an empty editable message with the signature`() = runTest {
        val f = start(signature = "Ana")

        f.newDraft()

        assertEquals(ComposerPhase.EDITING, f.state.phase)
        assertEquals(DraftKind.NEW, f.state.kind)
        assertEquals("\n-- \nAna", f.state.body)
        assertEquals(listOf("ana@example.test"), f.state.senders.map { it.email })
        assertFalse(f.state.showCcBcc)
    }

    @Test
    fun `a draft that does not exist or is in the outbox is gone`() = runTest {
        val f = start()
        f.vm.load(999)
        assertEquals(ComposerPhase.GONE, f.state.phase)

        val draft = f.h.writeTo("bob@example.test")
        f.h.send(draft.id)
        f.vm.load(draft.id)
        assertEquals(ComposerPhase.GONE, f.state.phase)
    }

    @Test
    fun `reopening a draft with cc keeps the fields visible and never duplicates the signature`() =
        runTest {
            val f = start(signature = "Ana")
            val id = f.newDraft()
            f.vm.onInput(RecipientKind.CC, "cy@example.test,")
            f.vm.onBody("Text${f.state.body}")
            f.vm.close()
            advanceUntilIdle()

            f.vm.load(id)

            assertTrue(f.state.showCcBcc)
            assertEquals(1, Regex("-- ").findAll(f.state.body).count())
            assertEquals("Text\n-- \nAna", f.state.body)
        }

    @Test
    fun `typing recipients makes chips, suggestions come from local mail and picking adds one`() =
        runTest {
            val f = start()
            f.h.receive(subject = "x")
            f.h.db.messageDao().upsert(
                listOf(
                    com.qtekfun.ultimatemail.data.local.message(f.h.accountId, 50)
                        .copy(senderName = "Bea Gomez", senderAddress = "bea@example.test")
                )
            )
            f.newDraft()

            f.vm.onInput(RecipientKind.TO, "be")
            assertEquals(
                listOf("bea@example.test"),
                f.state.suggestions!!.items.map { it.address }
            )
            assertEquals(RecipientKind.TO, f.state.suggestions!!.field)

            f.vm.pickSuggestion(RecipientKind.TO, f.state.suggestions!!.items.first())
            assertNull(f.state.suggestions)
            assertEquals("", f.state.to.input)
            assertEquals(listOf("bea@example.test"), f.state.to.addresses.map { it.address })

            f.vm.onInput(RecipientKind.TO, "nobody")
            f.vm.commit(RecipientKind.TO)
            assertTrue(f.state.to.hasInvalid)
            f.vm.removeChip(RecipientKind.TO, 1)
            assertFalse(f.state.to.hasInvalid)
        }

    @Test
    fun `autosave stores the text after the debounce and not before`() = runTest {
        val f = start()
        val id = f.newDraft()

        f.vm.onSubject("Plan")
        advanceTimeBy(799)
        assertEquals("", f.h.repository.get(id)!!.subject)

        advanceTimeBy(2)
        assertEquals("Plan", f.h.repository.get(id)!!.subject)
    }

    @Test
    fun `sending without recipients says so and sends nothing`() = runTest {
        val f = start()
        f.newDraft()

        f.vm.send()

        assertEquals(ComposerMessage.NoRecipients, f.state.message)
        assertNull(f.notices.notice.value)
        assertEquals(ComposerPhase.EDITING, f.state.phase)
    }

    @Test
    fun `an invalid recipient blocks sending until it is removed`() = runTest {
        val f = start()
        f.newDraft()
        f.vm.onInput(RecipientKind.TO, "nobody,")

        f.vm.send()
        assertEquals(ComposerMessage.InvalidRecipient, f.state.message)

        f.vm.removeChip(RecipientKind.TO, 0)
        assertNull(f.state.message)
    }

    @Test
    fun `an address typed but not yet turned into a chip is still sent`() = runTest {
        val f = start()
        f.newDraft()
        f.vm.onInput(RecipientKind.TO, "bob@example.test")
        f.vm.onSubject("Hi")
        f.vm.onBody("Text")

        f.vm.send()

        assertEquals(NoticeKind.SENDING, f.notices.notice.value?.kind)
    }

    @Test
    fun `an empty subject and an empty body are each confirmed once`() = runTest {
        val f = start()
        f.newDraft()
        f.vm.onInput(RecipientKind.TO, "bob@example.test,")

        f.vm.send()
        assertEquals(ComposerDialog.EMPTY_SUBJECT, f.state.dialog)
        f.vm.confirm()
        assertEquals(ComposerDialog.EMPTY_BODY, f.state.dialog)
        f.vm.dismissDialog()
        assertEquals(ComposerPhase.EDITING, f.state.phase)
        assertNull(f.notices.notice.value)

        f.vm.send()
        assertEquals(ComposerDialog.EMPTY_BODY, f.state.dialog)
        f.vm.confirm()
        assertEquals(NoticeKind.SENDING, f.notices.notice.value?.kind)
    }

    @Test
    fun `a missing attachment file blocks sending`() = runTest {
        val f = start()
        val id = f.newDraft()
        f.fillValidMessage()
        f.h.attachmentSource.put("content://p/1", "a.txt", "text/plain", ByteArray(10))
        f.vm.attach(listOf("content://p/1"))
        f.h.files.delete(f.state.attachments.single().filePath)

        f.vm.send()

        assertEquals(ComposerMessage.AttachmentMissing, f.state.message)
        assertTrue(f.sendOps(id).isEmpty())
    }

    @Test
    fun `send closes the composer, queues nothing during the undo window and queues after it`() =
        runTest {
            val f = start()
            val id = f.newDraft()
            f.fillValidMessage()

            f.vm.send()

            assertEquals(ComposerPhase.FINISHED, f.state.phase)
            val notice = f.notices.notice.value!!
            assertEquals(NoticeKind.SENDING, notice.kind)
            assertTrue(notice.undoable && notice.holdUntilCleared)
            advanceTimeBy(ComposerViewModel.UNDO_SEND_MILLIS - 1)
            assertEquals(DraftState.EDITING, f.h.repository.get(id)!!.state)
            assertTrue(f.sendOps(id).isEmpty())

            advanceTimeBy(2)
            runCurrent()

            assertEquals(DraftState.OUTBOX, f.h.repository.get(id)!!.state)
            assertEquals(1, f.sendOps(id).size)
            assertNull(f.notices.notice.value)
            assertTrue(f.h.scheduler.requests.isNotEmpty())
        }

    @Test
    fun `undo reopens the same draft and nothing is ever queued`() = runTest {
        val f = start()
        val id = f.newDraft()
        f.fillValidMessage()
        f.vm.send()

        f.notices.takeUndo(f.notices.notice.value!!.id)!!.revert()

        f.entry.starts.test {
            assertEquals(ComposeStart.Draft(id), awaitItem())
        }
        advanceTimeBy(ComposerViewModel.UNDO_SEND_MILLIS * 3)
        runCurrent()
        assertEquals(DraftState.EDITING, f.h.repository.get(id)!!.state)
        assertTrue(f.sendOps(id).isEmpty())

        f.vm.acknowledgeFinished()
        f.vm.load(id)
        assertEquals(ComposerPhase.EDITING, f.state.phase)
        assertEquals("Hello", f.state.subject)
    }

    @Test
    fun `another notice ends the undo window at once and the message is queued`() = runTest {
        val f = start()
        val id = f.newDraft()
        f.fillValidMessage()
        f.vm.send()

        f.notices.post(NoticeKind.ARCHIVED)
        runCurrent()

        assertEquals(1, f.sendOps(id).size)
    }

    @Test
    fun `back with changes saves the draft and says so`() = runTest {
        val f = start()
        val id = f.newDraft()
        f.vm.onSubject("Plan")

        f.vm.close()
        runCurrent()

        assertEquals(ComposerPhase.FINISHED, f.state.phase)
        assertEquals(NoticeKind.DRAFT_SAVED, f.notices.notice.value?.kind)
        assertEquals("Plan", f.h.repository.get(id)!!.subject)
        assertEquals(DraftState.EDITING, f.h.repository.get(id)!!.state)
    }

    @Test
    fun `back from a draft nobody touched drops it quietly`() = runTest {
        val f = start()
        val id = f.newDraft()

        f.vm.close()
        runCurrent()

        assertNull(f.h.repository.get(id))
        assertNull(f.notices.notice.value)
        assertEquals(ComposerPhase.FINISHED, f.state.phase)
    }

    @Test
    fun `back from a server draft that was only looked at keeps the server copy untouched`() =
        runTest {
            val f = start()
            val uid = f.h.server.deliver("Drafts", messageId = "<abc@mail.example>")
            f.h.db.messageDao().upsert(
                listOf(
                    message(f.h.accountId, uid, folderPath = "Drafts", bodyText = "Hi")
                        .copy(messageId = "<abc@mail.example>")
                )
            )
            val row = checkNotNull(f.h.db.messageDao().get(f.h.accountId, "Drafts", uid)).id
            val draft = (f.h.serverDrafts.open(row) as ServerDraftOpen.Opened).draft
            f.vm.load(draft.id)

            f.vm.close()
            runCurrent()

            assertNotNull(f.h.repository.get(draft.id))
            assertTrue(f.h.db.pendingOperationDao().all(f.h.accountId).isEmpty())
            assertEquals(
                listOf("<abc@mail.example>"),
                f.h.server.folder("Drafts").messages.values.map { it.messageId }
            )
        }

    @Test
    fun `back from a saved draft that was only looked at keeps it and says nothing`() = runTest {
        val f = start()
        val draft = f.h.writeTo("bob@example.test")
        f.vm.load(draft.id)

        f.vm.close()
        runCurrent()

        assertNotNull(f.h.repository.get(draft.id))
        assertNull(f.notices.notice.value)
    }

    @Test
    fun `discard asks first and then deletes the draft`() = runTest {
        val f = start()
        val id = f.newDraft()
        f.vm.onSubject("Plan")

        f.vm.requestDiscard()
        assertEquals(ComposerDialog.DISCARD, f.state.dialog)
        f.vm.dismissDialog()
        assertNotNull(f.h.repository.get(id))

        f.vm.requestDiscard()
        f.vm.confirm()
        runCurrent()

        assertNull(f.h.repository.get(id))
        assertEquals(ComposerPhase.FINISHED, f.state.phase)
        assertNull(f.notices.notice.value)
    }

    @Test
    fun `changing the sender swaps only the signature and keeps what was typed`() = runTest {
        val f = start(signature = "Ana")
        val second = f.h.engineHarness.addAccount(
            account("bea@example.test").copy(signature = "Bea", signatureEnabled = true)
        )
        val id = f.newDraft()
        f.vm.onBody("Hello there${f.state.body}")

        f.vm.selectSender(second)
        runCurrent()

        assertEquals(second, f.state.senderId)
        assertEquals("Hello there\n-- \nBea", f.state.body)
        assertEquals(second, f.h.repository.get(id)!!.accountId)
        assertEquals("Hello there\n-- \nBea", f.h.repository.get(id)!!.body)
        assertEquals(2, f.state.senders.size)
    }

    @Test
    fun `a reply gets the signature above the quote and changing sender moves nothing else`() =
        runTest {
            val f = start(signature = "Ana")
            val second = f.h.engineHarness.addAccount(
                account("bea@example.test").copy(signature = "Bea", signatureEnabled = true)
            )
            val message = f.h.receive(account = f.h.accountId)
            val reply = f.h.engine.start(
                ComposeRequest(f.h.accountId, "INBOX", message, ComposeMode.REPLY)
            )!!
            f.vm.load(reply.id)
            val before = f.state.body
            assertTrue(before.indexOf("-- \nAna") < before.indexOf("> Hi Ana"))

            f.vm.selectSender(second)
            runCurrent()

            assertEquals(before.replace("-- \nAna", "-- \nBea"), f.state.body)
        }

    @Test
    fun `a forward without text is sent without asking about the body`() = runTest {
        val f = start()
        val message = f.h.receive()
        val forward = f.h.engine.start(
            ComposeRequest(f.h.accountId, "INBOX", message, ComposeMode.FORWARD)
        )!!
        f.vm.load(forward.id)
        f.vm.onInput(RecipientKind.TO, "bob@example.test,")

        f.vm.send()

        assertNull(f.state.dialog)
        assertEquals(NoticeKind.SENDING, f.notices.notice.value?.kind)
    }

    @Test
    fun `attachments are listed, can be removed and a big total warns`() = runTest {
        val f = start()
        f.newDraft()
        f.h.attachmentSource.put("content://p/1", "a.txt", "text/plain", ByteArray(10))
        f.h.attachmentSource.put(
            "content://p/2",
            "big.bin",
            null,
            ByteArray(AttachmentLimits.WARN_BYTES.toInt() + 1)
        )

        f.vm.attach(listOf("content://p/1"))
        assertEquals(listOf("a.txt"), f.state.attachments.map { it.displayName })
        assertFalse(f.state.attachmentWarning)

        f.vm.attach(listOf("content://p/2"))
        assertTrue(f.state.attachmentWarning)

        f.vm.removeAttachment(f.state.attachments.first { it.displayName == "big.bin" }.id)
        assertFalse(f.state.attachmentWarning)
        assertEquals(1, f.state.attachments.size)
    }

    @Test
    fun `a file that would pass the limit is refused with a message`() = runTest {
        val f = start()
        f.newDraft()
        f.h.attachmentSource.put(
            "content://p/1",
            "huge.bin",
            null,
            ByteArray(1),
            reportedSize = AttachmentLimits.MAX_BYTES + 1
        )
        f.h.attachmentSource.putUnopenable("content://p/2")

        f.vm.attach(listOf("content://p/1"))
        assertEquals(
            ComposerMessage.AttachmentTooLarge(AttachmentLimits.MAX_BYTES),
            f.state.message
        )
        assertTrue(f.state.attachments.isEmpty())

        f.vm.attach(listOf("content://p/2"))
        assertEquals(ComposerMessage.AttachmentUnreadable, f.state.message)
    }

    @Test
    fun `edits clear the message and the state never shows content in toString`() = runTest {
        val f = start()
        f.newDraft()
        f.vm.send()
        assertNotNull(f.state.message)

        f.vm.onInput(RecipientKind.TO, "secret@example.test,")

        assertNull(f.state.message)
        assertFalse("secret" in f.state.toString())
        assertEquals(
            listOf(MailAddress("secret@example.test")),
            f.state.to.addresses
        )
        assertEquals(1, f.vm.state.first().to.chips.size)
    }
}
