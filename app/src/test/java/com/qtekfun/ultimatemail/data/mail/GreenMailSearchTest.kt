// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMailUtil
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.UidRange
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * IMAP SEARCH against GreenMail, a real IMAP server: the criteria are turned into SEARCH keys by
 * the library and answered by the server, not by this code. Gmail's X-GM-RAW is not here (see
 * `ProviderExtensionsTest`: GreenMail cannot play Gmail).
 */
class GreenMailSearchTest {
    private val server = GreenMailServer()
    private val connector =
        AngusMailConnector(Dispatchers.IO, trustingTestConfig(), GmailExtensions())
    private var session: MailSession? = null
    private var delivered = 0

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

    private fun deliver(
        subject: String,
        body: String = "body of $subject",
        from: String = "bob@example.test"
    ) {
        GreenMailUtil.sendTextEmail(
            TEST_USER,
            from,
            subject,
            body,
            server.greenMail.smtps.serverSetup
        )
        server.greenMail.waitForIncomingEmail(++delivered)
    }

    private fun search(
        criteria: MailSearchCriteria,
        limit: Int = MailSearchCriteria.DEFAULT_LIMIT
    ): MailResult<List<Long>> = runBlocking { connect().search("INBOX", criteria, limit) }

    private fun hits(criteria: MailSearchCriteria, limit: Int = 200): List<Long> {
        val result = search(criteria, limit)
        assertTrue(result is MailResult.Success, "expected success, was $result")
        return (result as MailResult.Success).value
    }

    @Test
    fun `free text is found in the subject, the sender and the body, newest first`() {
        deliver("Quarterly invoice")
        deliver("Hello", body = "your invoice is attached")
        deliver("Hello again", from = "invoice@example.test")
        deliver("Unrelated")

        // GreenMail matches a sender only as a whole address; other servers match a part of it.
        assertEquals(listOf(2L, 1L), hits(MailSearchCriteria(text = listOf("invoice"))))
        assertEquals(listOf(3L), hits(MailSearchCriteria(text = listOf("invoice@example.test"))))
    }

    @Test
    fun `every word must be found and matching ignores case`() {
        deliver("Budget March")
        deliver("Budget April")

        assertEquals(listOf(1L), hits(MailSearchCriteria(text = listOf("BUDGET", "march"))))
        assertEquals(emptyList<Long>(), hits(MailSearchCriteria(text = listOf("budget", "may"))))
    }

    @Test
    fun `excluded text removes messages`() {
        deliver("lunch pizza")
        deliver("lunch salad")

        val criteria = MailSearchCriteria(text = listOf("lunch"), excluded = listOf("pizza"))

        assertEquals(listOf(2L), hits(criteria))
    }

    @Test
    fun `from, subject and to look at their own header`() {
        deliver("report", from = "ana@example.test")
        deliver("other", body = "report", from = "bob@example.test")

        // GreenMail compares the whole address; the substring rule of RFC 3501 is the server's.
        assertEquals(listOf(1L), hits(MailSearchCriteria(from = listOf("ana@example.test"))))
        assertEquals(listOf(1L), hits(MailSearchCriteria(subject = listOf("report"))))
        assertEquals(listOf(2L, 1L), hits(MailSearchCriteria(to = listOf(TEST_USER))))
        assertEquals(emptyList<Long>(), hits(MailSearchCriteria(to = listOf("nobody"))))
    }

    @Test
    fun `unread and starred use the flags of the server`() {
        deliver("one")
        deliver("two")
        deliver("three")
        val s = connect()
        runBlocking {
            s.setFlags("INBOX", setOf(1), setOf(MailFlag.SEEN), true)
            s.setFlags("INBOX", setOf(2), setOf(MailFlag.FLAGGED), true)
        }

        assertEquals(listOf(3L, 2L), hits(MailSearchCriteria(unseen = true)))
        assertEquals(listOf(1L), hits(MailSearchCriteria(unseen = false)))
        assertEquals(listOf(2L), hits(MailSearchCriteria(flagged = true)))
        assertEquals(listOf(2L), hits(MailSearchCriteria(unseen = true, flagged = true)))
    }

    @Test
    fun `dates compare the day the message was sent`() {
        deliver("today")
        val today = LocalDate.now()

        assertEquals(listOf(1L), hits(MailSearchCriteria(since = today.minusDays(1))))
        assertEquals(listOf(1L), hits(MailSearchCriteria(since = today)))
        assertEquals(emptyList<Long>(), hits(MailSearchCriteria(since = today.plusDays(1))))
        assertEquals(listOf(1L), hits(MailSearchCriteria(before = today.plusDays(1))))
        assertEquals(emptyList<Long>(), hits(MailSearchCriteria(before = today)))
        assertEquals(
            listOf(1L),
            hits(MailSearchCriteria(since = today.minusDays(3), before = today.plusDays(3)))
        )
    }

    @Test
    fun `non-ASCII text goes to the server as it is`() {
        deliver("Reunión del ñandú")
        deliver("Other")

        assertEquals(listOf(1L), hits(MailSearchCriteria(text = listOf("ñandú"))))
        assertEquals(listOf(1L), hits(MailSearchCriteria(subject = listOf("reunión"))))
    }

    @Test
    fun `only the newest hits up to the limit come back`() {
        repeat(5) { deliver("alpha $it") }

        assertEquals(listOf(5L, 4L), hits(MailSearchCriteria(text = listOf("alpha")), limit = 2))
    }

    @Test
    fun `criteria without anything to search for list the newest messages`() {
        deliver("one")
        deliver("two")
        deliver("three")

        assertEquals(listOf(3L, 2L), hits(MailSearchCriteria(hasAttachment = true), limit = 2))
    }

    @Test
    fun `no match is an empty list, not an error`() {
        deliver("one")

        assertEquals(emptyList<Long>(), hits(MailSearchCriteria(text = listOf("zebra"))))
    }

    @Test
    fun `searching does not mark anything as read`() {
        deliver("one")
        val s = connect()

        runBlocking { s.search("INBOX", MailSearchCriteria(text = listOf("one"))) }

        val header = (runBlocking { s.fetchHeaders("INBOX", UidRange(1)) } as MailResult.Success)
            .value.single()
        assertFalse(header.flags.seen)
    }

    @Test
    fun `a folder that does not exist is not found`() {
        val result = runBlocking {
            connect().search("Nope", MailSearchCriteria(text = listOf("x")))
        }

        assertEquals(MailResult.NotFound, result)
    }

    @Test
    fun `headers by uid return just those messages and skip missing ones`() {
        deliver("one")
        deliver("two")
        deliver("three")

        val result = runBlocking { connect().fetchHeadersByUid("INBOX", setOf(3, 1, 99)) }

        val headers = (result as MailResult.Success).value
        assertEquals(listOf(1L, 3L), headers.map { it.uid })
        assertEquals(listOf("one", "three"), headers.map { it.subject })
    }

    @Test
    fun `headers by uid of nothing are nothing`() {
        deliver("one")

        val result = runBlocking { connect().fetchHeadersByUid("INBOX", emptySet()) }

        assertEquals(emptyList<Any>(), (result as MailResult.Success).value)
    }
}
