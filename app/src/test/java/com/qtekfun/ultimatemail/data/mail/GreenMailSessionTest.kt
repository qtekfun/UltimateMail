// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMailUtil
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.OutgoingAttachment
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The IMAP session against GreenMail, a real IMAP server, over TLS. */
class GreenMailSessionTest {
    private val server = GreenMailServer()
    private val connector =
        AngusMailConnector(Dispatchers.IO, trustingTestConfig(), GmailExtensions())
    private var session: MailSession? = null

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() {
        runBlocking { session?.close() }
        server.stop()
    }

    private fun connect(): MailSession = runBlocking {
        val result = connector.connect(server.imap, server.credentials)
        check(result is MailResult.Success) { "connect failed: $result" }
        result.value.also { session = it }
    }

    private fun deliver(subject: String, body: String = "body of $subject") {
        GreenMailUtil.sendTextEmail(
            TEST_USER,
            "bob@example.test",
            subject,
            body,
            server.greenMail.smtps.serverSetup
        )
    }

    private fun <T> ok(result: MailResult<T>): T {
        assertTrue(result is MailResult.Success, "expected success, was $result")
        return (result as MailResult.Success).value
    }

    @Test
    fun `lists folders with their roles`() {
        val s = connect()
        val folders = ok(runBlocking { s.listFolders() })
        val inbox = folders.single { it.path == "INBOX" }
        assertEquals(MailFolderRole.INBOX, inbox.role)
        assertTrue(inbox.selectable)
    }

    @Test
    fun `folder status gives validity and next uid, no modseq without CONDSTORE`() {
        deliver("one")
        deliver("two")
        server.greenMail.waitForIncomingEmail(2)
        val status = ok(runBlocking { connect().folderStatus("INBOX") })
        assertTrue(status.uidValidity > 0)
        assertEquals(2, status.messageCount)
        assertEquals(3L, status.uidNext)
        assertNull(status.highestModSeq)
    }

    @Test
    fun `fetches headers by uid range`() {
        deliver("one")
        deliver("two")
        server.greenMail.waitForIncomingEmail(2)
        val s = connect()
        val all = ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) })
        assertEquals(listOf("one", "two"), all.map { it.subject })
        assertEquals(listOf(1L, 2L), all.map { it.uid })
        val first = all.first()
        assertEquals("alice@example.test", first.to.single().address)
        assertEquals("bob@example.test", first.from?.address)
        assertTrue(first.messageId!!.startsWith("<"))
        assertFalse(first.flags.seen)
        assertFalse(first.hasAttachments)
        assertTrue(first.size > 0)
        assertNull(first.gmail)
        val last = ok(runBlocking { s.fetchHeaders("INBOX", UidRange(2, 2)) })
        assertEquals(listOf(2L), last.map { it.uid })
    }

    @Test
    fun `a range past the last uid is empty even though IMAP returns the last message`() {
        deliver("one")
        server.greenMail.waitForIncomingEmail(1)
        val result = ok(runBlocking { connect().fetchHeaders("INBOX", UidRange(5)) })
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetches the body without marking the message seen`() {
        deliver("hello", "the text")
        server.greenMail.waitForIncomingEmail(1)
        val s = connect()
        val body = ok(runBlocking { s.fetchBody("INBOX", 1) })
        assertEquals("the text", body.text?.trim())
        assertNull(body.html)
        assertTrue(body.attachments.isEmpty())
        assertFalse(ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) }).single().flags.seen)
    }

    @Test
    fun `body of a missing message is NotFound`() {
        assertEquals(MailResult.NotFound, runBlocking { connect().fetchBody("INBOX", 42) })
    }

    @Test
    fun `sets and clears flags and reports missing uids`() {
        deliver("one")
        server.greenMail.waitForIncomingEmail(1)
        val s = connect()
        val set =
            ok(
                runBlocking {
                    s.setFlags("INBOX", setOf(1, 99), setOf(MailFlag.SEEN, MailFlag.FLAGGED), true)
                }
            )
        assertEquals(setOf(1L), set.applied)
        assertEquals(setOf(99L), set.missing)
        val flags = ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) }).single().flags
        assertTrue(flags.seen && flags.flagged)
        ok(runBlocking { s.setFlags("INBOX", setOf(1), setOf(MailFlag.FLAGGED), false) })
        val after = ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) }).single().flags
        assertTrue(after.seen)
        assertFalse(after.flagged)
    }

    @Test
    fun `moves only the given messages`() {
        deliver("keep")
        deliver("move me")
        server.greenMail.waitForIncomingEmail(2)
        val s = connect()
        createFolder("Archive")
        val moved = ok(runBlocking { s.move("INBOX", setOf(2), "Archive") })
        assertEquals(setOf(2L), moved.applied)
        assertEquals(
            listOf("keep"),
            ok(
                runBlocking {
                    s.fetchHeaders("INBOX", UidRange(1))
                }
            ).map { it.subject }
        )
        assertEquals(
            listOf("move me"),
            ok(
                runBlocking {
                    s.fetchHeaders("Archive", UidRange(1))
                }
            ).map { it.subject }
        )
    }

    @Test
    fun `copies and keeps the original`() {
        deliver("one")
        server.greenMail.waitForIncomingEmail(1)
        val s = connect()
        createFolder("Copies")
        ok(runBlocking { s.copy("INBOX", setOf(1), "Copies") })
        assertEquals(1, ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) }).size)
        assertEquals(1, ok(runBlocking { s.fetchHeaders("Copies", UidRange(1)) }).size)
    }

    @Test
    fun `deletes only the given message, not others already flagged deleted`() {
        deliver("one")
        deliver("two")
        deliver("three")
        server.greenMail.waitForIncomingEmail(3)
        val s = connect()
        ok(runBlocking { s.setFlags("INBOX", setOf(1), setOf(MailFlag.DELETED), true) })
        val result = ok(runBlocking { s.delete("INBOX", setOf(2)) })
        assertEquals(setOf(2L), result.applied)
        val left = ok(runBlocking { s.fetchHeaders("INBOX", UidRange(1)) })
        assertEquals(listOf("one", "three"), left.map { it.subject })
        assertTrue(left.first().flags.deleted)
    }

    @Test
    fun `appends a draft with the draft flag`() {
        val s = connect()
        createFolder("Drafts")
        val message = OutgoingMessage(
            from = MailAddress(TEST_USER),
            to = listOf(MailAddress("bob@example.test", "Bob")),
            subject = "draft subject",
            text = "draft text"
        )
        ok(runBlocking { s.appendDraft("Drafts", message) })
        val header = ok(runBlocking { s.fetchHeaders("Drafts", UidRange(1)) }).single()
        assertEquals("draft subject", header.subject)
        assertTrue(header.flags.draft)
        assertEquals("Bob", header.to.single().name)
    }

    @Test
    fun `reads html and attachments of a message appended with them`() {
        val s = connect()
        createFolder("Drafts")
        val message = OutgoingMessage(
            from = MailAddress(TEST_USER),
            to = listOf(MailAddress("bob@example.test")),
            subject = "with file",
            text = "plain part",
            html = "<p>html part</p>",
            attachments = listOf(
                OutgoingAttachment("data.bin", "application/octet-stream", byteArrayOf(1, 2, 3, 4))
            )
        )
        ok(runBlocking { s.appendDraft("Drafts", message) })
        assertTrue(
            ok(
                runBlocking {
                    s.fetchHeaders("Drafts", UidRange(1))
                }
            ).single().hasAttachments
        )
        val body = ok(runBlocking { s.fetchBody("Drafts", 1) })
        assertEquals("plain part", body.text?.trim())
        assertEquals("<p>html part</p>", body.html?.trim())
        val attachment = body.attachments.single()
        assertEquals("data.bin", attachment.fileName)
        assertEquals("application/octet-stream", attachment.mimeType)
        assertFalse(attachment.inline)
        val bytes = ok(runBlocking { s.fetchAttachment("Drafts", 1, attachment.partId) })
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.toList())
        assertEquals(MailResult.NotFound, runBlocking { s.fetchAttachment("Drafts", 1, "9.9") })
    }

    @Test
    fun `labels are unsupported on a server that is not Gmail`() {
        deliver("one")
        server.greenMail.waitForIncomingEmail(1)
        val s = connect()
        assertEquals(
            MailResult.Unsupported("gmail-labels"),
            runBlocking {
                s.addLabels("INBOX", setOf(1), setOf("x"))
            }
        )
        assertEquals(
            MailResult.Unsupported("gmail-labels"),
            runBlocking {
                s.removeLabels("INBOX", setOf(1), setOf("x"))
            }
        )
    }

    @Test
    fun `a folder that does not exist is NotFound or rejected, never a throw`() {
        val result = runBlocking { connect().folderStatus("Nope") }
        assertTrue(result is MailResult.Failure, "was $result")
    }

    @Test
    fun `wrong password is AuthenticationFailed`() {
        val result = runBlocking {
            connector.connect(server.imap, MailCredentials.Password(TEST_USER, "wrong"))
        }
        assertEquals(MailResult.AuthenticationFailed, result)
    }

    private fun createFolder(name: String) {
        val user = server.greenMail.managers.userManager.getUserByEmail(TEST_USER)
        server.greenMail.managers.imapHostManager.createMailbox(user, name)
    }
}
