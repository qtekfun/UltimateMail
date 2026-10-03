// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoadMessageBodyTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): Pair<EngineHarness, LoadMessageBody> {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.server.deliver("INBOX")
        h.server.folder("INBOX").bodies[1] = MessageBody(
            text = "plain",
            html = "<p>rich</p>",
            attachments = listOf(AttachmentInfo("2", "a.pdf", "application/pdf", 10, null, false))
        )
        h.addAccount()
        h.engine.sync(h.accountId)
        h.server.log.clear()
        return h to
            LoadMessageBody(h.messages, h.sessions, BodyStore(h.messages, h.db.attachmentDao()))
    }

    private suspend fun EngineHarness.messageId() = messages.get(accountId, "INBOX", 1)!!.id

    @Test
    fun `the body is fetched once, cached in Room and its attachments are listed`() = runTest {
        val (h, load) = start()

        val first = load(h.messageId())
        val second = load(h.messageId())

        assertEquals(BodyResult.Loaded("plain", "<p>rich</p>"), first)
        assertEquals(first, second)
        assertEquals(1, h.server.logged("fetchBody").size, "the second read comes from Room")
        val stored = h.messages.get(h.accountId, "INBOX", 1)!!
        assertEquals("plain", stored.bodyText)
        val attachments = h.db.attachmentDao().observe(stored.id).first()
        assertEquals(listOf("a.pdf"), attachments.map { it.fileName })
    }

    @Test
    fun `a message with only HTML is cached without inventing text`() = runTest {
        val (h, load) = start()
        h.server.folder("INBOX").bodies[1] = MessageBody(null, "<p>x</p>", emptyList())

        val result = load(h.messageId())

        assertEquals(BodyResult.Loaded(null, "<p>x</p>"), result)
        assertNull(h.messages.get(h.accountId, "INBOX", 1)!!.bodyText)
    }

    @Test
    fun `an empty message still counts as fetched`() = runTest {
        val (h, load) = start()
        h.server.folder("INBOX").bodies[1] = MessageBody(null, null, emptyList())

        assertEquals(BodyResult.Loaded("", null), load(h.messageId()))
        load(h.messageId())

        assertEquals(1, h.server.logged("fetchBody").size)
    }

    @Test
    fun `unknown and local-only messages are not found`() = runTest {
        val (h, load) = start()
        val local = h.messages.get(
            h.accountId,
            "INBOX",
            1
        )!!.copy(id = 0, uid = 0, messageId = "<d@x>")
        h.messages.insertNew(listOf(local))
        val localId = h.messages.get(h.accountId, "INBOX", 0)!!.id

        assertEquals(BodyResult.NotFound, load(12345))
        assertEquals(BodyResult.NotFound, load(localId))
    }

    @Test
    fun `a message gone from the server is not found`() = runTest {
        val (h, load) = start()
        h.server.folder("INBOX").bodies.clear()

        assertEquals(BodyResult.NotFound, load(h.messageId()))
    }

    @Test
    fun `failures are reported without touching the cache`() = runTest {
        val (h, load) = start()

        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("fetchBody") } }
        assertEquals(BodyResult.Failed(SyncProblem.TIMEOUT), load(h.messageId()))

        h.server.failure =
            { name -> MailResult.AuthenticationFailed.takeIf { name.startsWith("fetchBody") } }
        assertEquals(BodyResult.AuthenticationRequired, load(h.messageId()))

        h.server.failure = { null }
        h.connector.failure = MailResult.AuthenticationFailed
        assertEquals(BodyResult.AuthenticationRequired, load(h.messageId()))

        h.connector.failure = MailResult.NetworkUnavailable
        assertEquals(BodyResult.Failed(SyncProblem.NETWORK), load(h.messageId()))

        assertNull(h.messages.get(h.accountId, "INBOX", 1)!!.bodyText)
    }

    @Test
    fun `a message whose account vanished meanwhile is not found`() = runTest {
        val (h, _) = start()
        val orphan = h.messages.get(h.accountId, "INBOX", 1)!!.copy(accountId = 999)
        val messages = mockk<MessageDao>()
        coEvery { messages.getById(1) } returns orphan
        val load = LoadMessageBody(messages, h.sessions, BodyStore(messages, h.db.attachmentDao()))

        assertEquals(BodyResult.NotFound, load(1))
    }
}
