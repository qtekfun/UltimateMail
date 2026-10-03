// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMailUtil
import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.UidRange
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.eclipse.angus.mail.imap.IMAPFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Gmail-specific handling of the session, driven through the [ProviderExtensions] seam with
 * a mock, because GreenMail cannot speak X-GM-EXT-1. What the real [GmailExtensions] does on the
 * wire is not covered; see the PR notes.
 */
class ProviderExtensionsTest {
    private val server = GreenMailServer()
    private val extensions = mockk<ProviderExtensions>()
    private var session: MailSession? = null

    @BeforeEach
    fun start() {
        server.start()
        GreenMailUtil.sendTextEmail(
            TEST_USER,
            "bob@example.test",
            "one",
            "b",
            server.greenMail.smtps.serverSetup
        )
        GreenMailUtil.sendTextEmail(
            TEST_USER,
            "bob@example.test",
            "two",
            "b",
            server.greenMail.smtps.serverSetup
        )
        server.greenMail.waitForIncomingEmail(2)
        every { extensions.fetchItems() } returns emptyList()
    }

    @AfterEach
    fun stop() {
        runBlocking { session?.close() }
        server.stop()
    }

    private fun connect(): MailSession = runBlocking {
        val connector = AngusMailConnector(Dispatchers.IO, trustingTestConfig(), extensions)
        (connector.connect(server.imap, server.credentials) as MailResult.Success).value.also {
            session =
                it
        }
    }

    @Test
    fun `headers carry the Gmail metadata when the server has the extension`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.metadata(any()) } returns
            GmailMetadata(11, 22, listOf("\\Inbox", "Work"))
        val headers = (
            runBlocking {
                connect().fetchHeaders("INBOX", UidRange(1))
            } as MailResult.Success
            ).value
        assertEquals(2, headers.size)
        assertEquals(GmailMetadata(11, 22, listOf("\\Inbox", "Work")), headers.first().gmail)
    }

    @Test
    fun `headers have no Gmail metadata on other servers and the extension is not asked`() {
        every { extensions.isAvailable(any()) } returns false
        val headers = (
            runBlocking {
                connect().fetchHeaders("INBOX", UidRange(1))
            } as MailResult.Success
            ).value
        assertNull(headers.first().gmail)
        verify(exactly = 0) { extensions.metadata(any()) }
    }

    @Test
    fun `adding labels hands the right messages and labels to the extension`() {
        every { extensions.isAvailable(any()) } returns true
        val folder = slot<IMAPFolder>()
        val messages = slot<List<Message>>()
        val labels = slot<Set<String>>()
        every {
            extensions.changeLabels(capture(folder), capture(messages), capture(labels), true)
        } returns
            Unit
        val result =
            runBlocking { connect().addLabels("INBOX", setOf(2, 77), setOf("Work", "Home")) }
        val outcome = (result as MailResult.Success).value
        assertEquals(setOf(2L), outcome.applied)
        assertEquals(setOf(77L), outcome.missing)
        assertEquals(setOf("Work", "Home"), labels.captured)
        assertEquals(1, messages.captured.size)
        assertEquals(2L, folder.captured.getUID(messages.captured.single()))
    }

    @Test
    fun `removing labels asks the extension to remove`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.changeLabels(any(), any(), any(), false) } returns Unit
        val result = runBlocking { connect().removeLabels("INBOX", setOf(1), setOf("Work")) }
        assertEquals(setOf(1L), (result as MailResult.Success).value.applied)
        verify(exactly = 1) { extensions.changeLabels(any(), any(), setOf("Work"), false) }
    }

    @Test
    fun `a failure inside the extension is mapped, not thrown`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.changeLabels(any(), any(), any(), any()) } throws
            jakarta.mail.MessagingException("x", java.net.SocketException("reset"))
        val result = runBlocking { connect().addLabels("INBOX", setOf(1), setOf("Work")) }
        assertEquals(MailResult.NetworkUnavailable, result)
    }

    @Test
    fun `labels on a server without the extension are unsupported and the extension is not used`() {
        every { extensions.isAvailable(any()) } returns false
        val result = runBlocking { connect().addLabels("INBOX", setOf(1), setOf("Work")) }
        assertEquals(MailResult.Unsupported("gmail-labels"), result)
        verify(exactly = 0) { extensions.changeLabels(any(), any(), any(), any()) }
    }

    @Test
    fun `a search on Gmail goes through the extension in Gmail's own syntax`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.rawSearch(any(), any()) } answers
            { firstArg<IMAPFolder>().messages.toList() }
        val criteria = MailSearchCriteria(text = listOf("one"), unseen = true, hasAttachment = true)

        val result = runBlocking { connect().search("INBOX", criteria) }

        assertEquals(listOf(2L, 1L), (result as MailResult.Success).value)
        verify(exactly = 1) {
            extensions.rawSearch(any(), "\"one\" is:unread has:attachment")
        }
    }

    @Test
    fun `a Gmail search keeps only the newest hits up to the limit`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.rawSearch(any(), any()) } answers
            { firstArg<IMAPFolder>().messages.toList() }

        val result = runBlocking { connect().search("INBOX", MailSearchCriteria(), limit = 1) }

        assertEquals(listOf(2L), (result as MailResult.Success).value)
    }

    @Test
    fun `a search on another server does not touch the Gmail extension`() {
        every { extensions.isAvailable(any()) } returns false

        val result = runBlocking {
            connect().search("INBOX", MailSearchCriteria(subject = listOf("two")))
        }

        assertEquals(listOf(2L), (result as MailResult.Success).value)
        verify(exactly = 0) { extensions.rawSearch(any(), any()) }
    }

    @Test
    fun `a failure inside the Gmail search is mapped, not thrown`() {
        every { extensions.isAvailable(any()) } returns true
        every { extensions.rawSearch(any(), any()) } throws
            jakarta.mail.MessagingException("x", java.net.SocketException("reset"))

        val result = runBlocking { connect().search("INBOX", MailSearchCriteria()) }

        assertEquals(MailResult.NetworkUnavailable, result)
    }
}
