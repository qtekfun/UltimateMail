// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.FakeAttachmentStorage
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.domain.conversation.BodyFailure
import com.qtekfun.ultimatemail.domain.conversation.BodyView
import com.qtekfun.ultimatemail.domain.conversation.ComposeLauncher
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.conversation.ConversationReader
import com.qtekfun.ultimatemail.domain.conversation.ConversationRef
import com.qtekfun.ultimatemail.domain.conversation.MessageView
import com.qtekfun.ultimatemail.domain.conversation.RecordingScheduler
import com.qtekfun.ultimatemail.domain.conversation.RenderedBody
import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.engine.BodyStore
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.LoadMessageBody
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val scheduler = RecordingScheduler()
    private val storage = FakeAttachmentStorage()
    private var harness: EngineHarness? = null
    private val composed = mutableListOf<ComposeRequest>()
    private var composeAvailable = false
    private val launcher = ComposeLauncher { request ->
        composed += request
        composeAvailable
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        harness?.close()
        Dispatchers.resetMain()
    }

    private class Fixture(
        val h: EngineHarness,
        val vm: ConversationViewModel,
        val ref: ConversationRef
    )

    /**
     * A conversation of three messages (uid 1 and 2 read, 3 unread unless [allRead]) in INBOX,
     * with an Archive and a Trash folder on the server.
     */
    private suspend fun TestScope.start(
        allRead: Boolean = false,
        withArchive: Boolean = true
    ): Fixture {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        if (withArchive) h.server.folder("Archive", MailFolderRole.ARCHIVE)
        h.server.folder("Trash", MailFolderRole.TRASH)
        repeat(3) { index ->
            val seen = index < 2 || allRead
            h.server.deliver(
                "INBOX",
                subject = "Trip",
                sentAt = Instant.ofEpochSecond(1_700_000_000L + index),
                messageId = "<m${index + 1}@x>",
                flags = MessageFlags(seen = seen),
                inReplyTo = if (index == 0) null else "<m$index@x>",
                references = (1..index).map { "<m$it@x>" }
            )
            h.server.folder("INBOX").bodies[index + 1L] =
                MessageBody("Body ${index + 1}", null, emptyList())
        }
        h.addAccount()
        h.engine.sync(h.accountId)
        val first = h.messages.get(h.accountId, "INBOX", 1)!!
        val ref = ConversationRef(h.accountId, "INBOX", first.threadId)
        val actions = ConversationActions(h.messages, h.queue, h.marker, scheduler)
        val vm = ConversationViewModel(
            ConversationReader(h.db),
            actions,
            LoadMessageBody(h.messages, h.sessions, BodyStore(h.messages, h.db.attachmentDao())),
            DownloadAttachment(h.db.attachmentDao(), h.messages, h.sessions, storage),
            launcher,
            SettingsRepository(FakePreferenceStore()),
            NoticeCenter(scheduler),
            Dispatchers.Unconfined
        )
        backgroundScope.launch { vm.state.collect {} }
        return Fixture(h, vm, ref)
    }

    /** Polls on real time: Room answers on its own threads. */
    private suspend fun <T : Any> eventually(block: suspend () -> T?): T =
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MILLIS) {
                var result = block()
                while (result == null) {
                    delay(POLL_MILLIS)
                    result = block()
                }
                result
            }
        }

    private suspend fun Fixture.messages(): List<MessageView> =
        eventually { vm.state.value.view?.messages?.takeIf { it.isNotEmpty() } }

    private suspend fun Fixture.bodyReady(position: Int): BodyView.Ready = eventually {
        vm.state.value.view?.messages?.getOrNull(position)?.body as? BodyView.Ready
    }

    private suspend fun Fixture.open() {
        vm.open(ref)
        bodyReady(2)
    }

    @Test
    fun `opening shows the thread oldest first with the first unread message open and read`() =
        runTest {
            val f = start()

            f.open()

            val messages = f.messages()
            assertEquals(3, messages.size)
            assertEquals(listOf(false, false, true), messages.map { it.expanded })
            assertEquals("Trip", f.vm.state.value.view?.subject)
            assertEquals("Body 3", textOf(f.bodyReady(2)))
            eventually { f.h.messages.get(f.h.accountId, "INBOX", 3)!!.takeIf { it.seen } }
            val queued = f.h.operations.all(f.h.accountId).single()
            assertEquals(OperationType.SET_FLAGS, queued.type)
            assertEquals(3L, queued.uid)
            assertTrue(f.h.messages.get(f.h.accountId, "INBOX", 3)!!.pendingSync)
            assertTrue(f.h.accountId in scheduler.requests.filterNotNull())
        }

    @Test
    fun `when everything is read the newest message is the open one and nothing is queued`() =
        runTest {
            val f = start(allRead = true)

            f.open()

            assertEquals(listOf(false, false, true), f.messages().map { it.expanded })
            assertTrue(f.h.operations.all(f.h.accountId).isEmpty())
        }

    @Test
    fun `opening a collapsed message loads its body and collapsing hides it`() = runTest {
        val f = start()
        f.open()

        f.vm.toggle(f.messages()[0].id)

        assertEquals("Body 1", textOf(f.bodyReady(0)))
        f.vm.toggle(f.messages()[0].id)
        eventually { f.vm.state.value.view?.messages?.get(0)?.takeIf { !it.expanded } }
        assertNull(f.messages()[0].body)
    }

    @Test
    fun `a body that cannot be fetched offline fails and can be retried`() = runTest {
        val f = start()
        f.h.server.failure =
            { if (it.startsWith("fetchBody")) MailResult.NetworkUnavailable else null }

        f.vm.open(f.ref)
        val failed = eventually {
            f.vm.state.value.view?.messages?.lastOrNull()?.body as? BodyView.Failed
        }
        assertEquals(BodyFailure.OFFLINE, failed.reason)

        f.h.server.failure = { null }
        f.vm.ensureBody(f.messages().last().id)

        assertEquals("Body 3", textOf(f.bodyReady(2)))
    }

    @Test
    fun `a message gone from the server and a rejected login are told apart`() = runTest {
        val f = start()
        f.h.server.folder("INBOX").bodies.remove(3L)

        f.vm.open(f.ref)

        val gone = eventually {
            f.vm.state.value.view?.messages?.lastOrNull()?.body as? BodyView.Failed
        }
        assertEquals(BodyFailure.GONE, gone.reason)

        f.h.server.folder("INBOX").bodies[3L] = MessageBody("x", null, emptyList())
        f.h.server.failure =
            { if (it.startsWith("fetchBody")) MailResult.AuthenticationFailed else null }
        f.vm.ensureBody(f.messages().last().id)
        val auth = eventually {
            (f.vm.state.value.view?.messages?.lastOrNull()?.body as? BodyView.Failed)
                ?.takeIf { it.reason == BodyFailure.SIGN_IN }
        }
        assertEquals(BodyFailure.SIGN_IN, auth.reason)

        f.h.server.failure = { if (it.startsWith("fetchBody")) MailResult.Protocol else null }
        f.vm.ensureBody(f.messages().last().id)
        val other = eventually {
            (f.vm.state.value.view?.messages?.lastOrNull()?.body as? BodyView.Failed)
                ?.takeIf { it.reason == BodyFailure.OTHER }
        }
        assertEquals(BodyFailure.OTHER, other.reason)
    }

    @Test
    fun `starring and unstarring act on the newest message`() = runTest {
        val f = start()
        f.open()

        f.vm.toggleStar()
        eventually { f.h.messages.get(f.h.accountId, "INBOX", 3)!!.takeIf { it.flagged } }
        eventually { f.vm.state.value.view?.newest?.takeIf { it.flagged } }

        f.vm.toggleStar()
        eventually { f.h.messages.get(f.h.accountId, "INBOX", 3)!!.takeIf { !it.flagged } }
        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 1)!!.flagged)
    }

    @Test
    fun `marking unread marks the newest message and leaves the screen`() = runTest {
        val f = start(allRead = true)
        f.open()

        f.vm.events.test {
            f.vm.markUnread()
            assertEquals(ConversationEvent.Close, awaitItem())
        }

        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 3)!!.seen)
    }

    @Test
    fun `archiving queues a move, leaves, offers undo and sends only after the window`() = runTest {
        val f = start(allRead = true)
        f.open()
        scheduler.requests.clear()

        f.vm.events.test {
            f.vm.archive()
            assertEquals(ConversationEvent.Close, awaitItem())
        }

        val notice = f.vm.notice.value!!
        assertEquals(NoticeKind.ARCHIVED, notice.kind)
        assertTrue(notice.undoable)
        val moves = f.h.operations.all(f.h.accountId)
        assertEquals(listOf(1L, 2L, 3L), moves.map { it.uid })
        assertTrue(moves.all { it.type == OperationType.MOVE && it.payload == "Archive" })
        assertTrue(scheduler.requests.isEmpty())

        f.vm.commit(notice.id)

        assertEquals(listOf<Long?>(f.h.accountId), scheduler.requests)
        assertNull(f.vm.notice.value)
    }

    @Test
    fun `undo takes the move back before it is sent`() = runTest {
        val f = start(allRead = true)
        f.open()
        f.vm.archive()
        val notice = eventually { f.vm.notice.value }

        f.vm.undo(notice.id)

        eventually { f.h.operations.all(f.h.accountId).takeIf { it.isEmpty() } }
        assertNull(f.vm.notice.value)
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `deleting moves to the trash folder`() = runTest {
        val f = start(allRead = true)
        f.open()

        f.vm.delete()

        val notice = eventually { f.vm.notice.value }
        assertEquals(NoticeKind.DELETED, notice.kind)
        val moves = eventually { f.h.operations.all(f.h.accountId).takeIf { it.size == 3 } }
        assertTrue(moves.all { it.payload == "Trash" })
    }

    @Test
    fun `an account without an archive folder says so and queues nothing`() = runTest {
        val f = start(allRead = true, withArchive = false)
        f.open()

        f.vm.archive()

        assertEquals(NoticeKind.NO_ARCHIVE_FOLDER, f.vm.notice.value?.kind)
        assertFalse(f.vm.notice.value!!.undoable)
        assertTrue(f.h.operations.all(f.h.accountId).isEmpty())
    }

    @Test
    fun `a new notice sends the move of the one it replaces`() = runTest {
        val f = start(allRead = true)
        f.open()
        f.vm.archive()
        eventually { f.vm.notice.value }
        scheduler.requests.clear()

        f.vm.report(NoticeKind.ATTACHMENT_SAVED)

        assertEquals(listOf<Long?>(f.h.accountId), scheduler.requests)
        assertEquals(NoticeKind.ATTACHMENT_SAVED, f.vm.notice.value?.kind)
        f.vm.noticeShown(f.vm.notice.value!!.id)
        assertNull(f.vm.notice.value)
    }

    @Test
    fun `answering a stale notice id changes nothing`() = runTest {
        val f = start(allRead = true)
        f.open()
        f.vm.report(NoticeKind.NO_APP_FOR_ATTACHMENT)
        val current = f.vm.notice.value!!

        f.vm.undo(current.id + 1)
        f.vm.commit(current.id + 1)
        f.vm.noticeShown(current.id + 1)

        assertEquals(current, f.vm.notice.value)
    }

    @Test
    fun `reply is a placeholder that says coming soon until the composer exists`() = runTest {
        val f = start(allRead = true)
        f.open()

        f.vm.compose(ComposeMode.REPLY_ALL)

        val newest = f.messages().last()
        assertEquals(
            ComposeRequest(f.h.accountId, "INBOX", newest.id, ComposeMode.REPLY_ALL),
            composed.single()
        )
        assertEquals(NoticeKind.COMPOSE_SOON, f.vm.notice.value?.kind)

        composeAvailable = true
        f.vm.noticeShown(f.vm.notice.value!!.id)
        f.vm.compose(ComposeMode.FORWARD)
        assertNull(f.vm.notice.value)
        assertEquals(2, composed.size)
    }

    @Test
    fun `details, quoted text and remote images are remembered per message`() = runTest {
        val f = start()
        f.h.server.folder("INBOX").bodies[3L] = MessageBody(
            null,
            "<p>Hi</p><img src=\"https://x.example.test/a.png\"><blockquote>old</blockquote>",
            emptyList()
        )
        f.open()
        val newest = f.messages().last().id

        f.vm.toggleDetails(newest)
        f.vm.toggleQuoted(newest)
        f.vm.allowRemoteContent(newest)

        val shown = eventually {
            f.vm.state.value.view?.messages?.last()?.takeIf { it.detailsShown }
        }
        val body = shown.body as BodyView.Ready
        assertTrue(body.quotedShown)
        assertTrue(body.remoteAllowed)
        assertFalse(f.messages().first().detailsShown)
        f.vm.toggleDetails(newest)
        assertNotNull(
            eventually {
                f.vm.state.value.view?.messages?.last()?.takeIf { !it.detailsShown }
            }
        )
    }

    @Test
    fun `an attachment is downloaded only when asked and then handed to the screen`() = runTest {
        val f = start()
        f.h.server.folder("INBOX").bodies[3L] = MessageBody(
            "Body 3",
            null,
            listOf(AttachmentInfo("2", "a.pdf", "application/pdf", 3, null, false))
        )
        f.h.server.folder("INBOX").attachments[3L to "2"] = byteArrayOf(1, 2, 3)
        f.open()
        val file = eventually {
            f.vm.state.value.view?.messages?.last()?.attachments?.singleOrNull()
        }
        assertEquals(AttachmentState.REMOTE, file.state)
        assertTrue(f.h.server.logged("fetchAttachment").isEmpty())

        f.vm.events.test {
            f.vm.attachment(file.id, AttachmentAction.SHARE)
            val ready = awaitItem() as ConversationEvent.AttachmentReady
            assertEquals(AttachmentAction.SHARE, ready.action)
            assertEquals("application/pdf", ready.mimeType)
            assertEquals("a.pdf", ready.name)
            assertEquals(listOf<Byte>(1, 2, 3), storage.files.getValue(ready.path).toList())
        }
        val downloaded = eventually {
            f.vm.state.value.view?.messages?.last()?.attachments?.single()
                ?.takeIf { it.state == AttachmentState.DOWNLOADED }
        }
        assertEquals(file.id, downloaded.id)
    }

    @Test
    fun `a failed attachment download says so and an attachment gone says that`() = runTest {
        val f = start()
        f.h.server.folder("INBOX").bodies[3L] = MessageBody(
            "Body 3",
            null,
            listOf(AttachmentInfo("2", "a.pdf", "application/pdf", 3, null, false))
        )
        f.open()
        val file = eventually {
            f.vm.state.value.view?.messages?.last()?.attachments?.singleOrNull()
        }

        f.h.server.failure = { if (it.startsWith("fetchAttachment")) MailResult.Timeout else null }
        f.vm.attachment(file.id, AttachmentAction.OPEN)
        val failed = eventually {
            f.vm.notice.value?.takeIf {
                it.kind ==
                    NoticeKind.ATTACHMENT_FAILED
            }
        }
        f.vm.noticeShown(failed.id)

        f.h.server.failure = { null }
        f.vm.attachment(file.id, AttachmentAction.OPEN)
        val gone =
            eventually { f.vm.notice.value?.takeIf { it.kind == NoticeKind.ATTACHMENT_GONE } }
        assertTrue(gone.id > failed.id)
    }

    @Test
    fun `an attachment that is not in the conversation is ignored`() = runTest {
        val f = start()
        f.open()

        f.vm.attachment(9_999, AttachmentAction.OPEN)

        assertNull(f.vm.notice.value)
    }

    @Test
    fun `inline images are fetched with the body and served by content id`() = runTest {
        val f = start()
        f.h.server.folder("INBOX").bodies[3L] = MessageBody(
            null,
            "<img src=\"cid:logo@x\">",
            listOf(AttachmentInfo("2", "logo.png", "image/png", 3, "<logo@x>", true))
        )
        f.h.server.folder("INBOX").attachments[3L to "2"] = byteArrayOf(7)

        f.vm.open(f.ref)

        val message = eventually {
            f.vm.state.value.view?.messages?.last()?.takeIf { it.cidFiles.isNotEmpty() }
        }
        assertEquals(setOf("logo@x"), message.cidFiles.keys)
        assertTrue(message.attachments.isEmpty(), "shown inside the message, not listed")
    }

    @Test
    fun `a conversation that does not exist is reported as missing`() = runTest {
        val f = start()

        f.vm.open(ConversationRef(f.h.accountId, "INBOX", "nope"))

        val state = eventually { f.vm.state.value.takeIf { it.loaded } }
        assertTrue(state.missing)
        assertNull(state.view)
    }

    @Test
    fun `opening the same conversation again keeps what was open`() = runTest {
        val f = start()
        f.open()
        f.vm.toggle(f.messages()[0].id)
        f.bodyReady(0)

        f.vm.open(f.ref)

        assertEquals(listOf(true, false, true), f.messages().map { it.expanded })
    }

    @Test
    fun `nothing is shown before a conversation is opened`() = runTest {
        val f = start()

        assertFalse(f.vm.state.value.loaded)
        assertNull(f.vm.state.value.view)
        assertFalse(f.vm.state.value.missing)
        assertEquals(null, f.vm.state.first().ref)
        f.vm.toggleStar()
        f.vm.archive()
        f.vm.compose(ComposeMode.REPLY)
        assertTrue(composed.isEmpty())
    }

    private fun textOf(body: BodyView.Ready): String = (body.rendered as RenderedBody.Text)
        .runs.joinToString("") { it.text }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L
    }
}
