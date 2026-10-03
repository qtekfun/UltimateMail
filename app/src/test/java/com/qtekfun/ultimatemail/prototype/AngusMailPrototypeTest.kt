// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.prototype

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import org.eclipse.angus.mail.imap.IMAPFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T03 prototype: the operations UltimateMail needs from a mail library, run against a real
 * IMAP/SMTP server (GreenMail) with Angus Mail. The outcome is recorded in SPEC.md section 9.
 */
class AngusMailPrototypeTest {
    private val server = GreenMail(ServerSetupTest.SMTP_IMAP)
    private val user = "alice@example.test"
    private val password = "secret"

    @BeforeEach
    fun start() {
        server.start()
        server.setUser(user, user, password)
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    private fun connect(): Store = Session.getInstance(
        Properties().apply {
            put("mail.imap.connectiontimeout", "5000")
            put("mail.imap.timeout", "5000")
        }
    ).getStore("imap").also {
        it.connect("127.0.0.1", ServerSetupTest.IMAP.port, user, password)
    }

    private fun deliver(subject: String) {
        GreenMailUtil.sendTextEmail(
            user,
            "bob@example.test",
            subject,
            "body of $subject",
            ServerSetupTest.SMTP
        )
    }

    @Test
    fun `lists folders and fetches headers with stable UIDs`() {
        deliver("one")
        deliver("two")
        server.waitForIncomingEmail(2)
        connect().use { store ->
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            assertTrue(store.defaultFolder.list("*").any { it.fullName == "INBOX" })
            val uids = inbox.messages.map { inbox.getUID(it) }
            assertEquals(2, uids.size)
            assertTrue(inbox.uidValidity > 0)
            assertTrue(inbox.uidNext > uids.max())
            assertEquals(listOf("one", "two"), inbox.messages.map { it.subject })
        }
    }

    @Test
    fun `moves a message to another folder and flags it`() {
        deliver("to archive")
        server.waitForIncomingEmail(1)
        connect().use { store ->
            val inbox = store.getFolder("INBOX") as IMAPFolder
            val archive = store.getFolder("Archive") as IMAPFolder
            archive.create(Folder.HOLDS_MESSAGES)
            inbox.open(Folder.READ_WRITE)
            val message = inbox.getMessage(1)
            message.setFlag(Flags.Flag.FLAGGED, true)
            inbox.copyMessages(arrayOf(message), archive)
            message.setFlag(Flags.Flag.DELETED, true)
            inbox.expunge()
            assertEquals(0, inbox.messageCount)
            archive.open(Folder.READ_ONLY)
            assertEquals(1, archive.messageCount)
            assertTrue(archive.getMessage(1).flags.contains(Flags.Flag.FLAGGED))
        }
    }

    @Test
    fun `looks a message up by UID through UIDFolder`() {
        deliver("by uid")
        server.waitForIncomingEmail(1)
        connect().use { store ->
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            val uid = inbox.getUID(inbox.getMessage(1))
            assertEquals("by uid", (inbox as UIDFolder).getMessageByUID(uid).subject)
        }
    }

    @Test
    fun `sends through SMTP with authentication`() {
        val session = Session.getInstance(
            Properties().apply {
                put("mail.smtp.host", "127.0.0.1")
                put("mail.smtp.port", ServerSetupTest.SMTP.port.toString())
                put("mail.smtp.auth", "true")
            }
        )
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(user))
            setRecipient(Message.RecipientType.TO, InternetAddress(user))
            subject = "sent by us"
            setText("hello")
        }
        session.getTransport("smtp").use {
            it.connect(user, password)
            it.sendMessage(message, message.allRecipients)
        }
        server.waitForIncomingEmail(1)
        connect().use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            assertEquals("sent by us", inbox.getMessage(1).subject)
        }
    }

    private inline fun <T> Store.use(block: (Store) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
