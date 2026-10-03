// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.mail.GmailRawQuery
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerSearchCriteriaTest {
    private fun criteria(text: String) = ServerSearchCriteria.of(SearchQueryParser.parse(text))

    @Test
    fun `every part of the query reaches the criteria`() {
        val result = criteria(
            "report \"big news\" -spam from:ana to:bob subject:q3 in:inbox is:unread " +
                "is:starred has:attachment after:2026-03-01 before:2026-04-01 "
        )

        assertEquals(
            MailSearchCriteria(
                text = listOf("report", "big news"),
                excluded = listOf("spam"),
                from = listOf("ana"),
                to = listOf("bob"),
                subject = listOf("q3"),
                labels = listOf("inbox"),
                unseen = true,
                flagged = true,
                hasAttachment = true,
                since = LocalDate.of(2026, 3, 1),
                before = LocalDate.of(2026, 4, 1)
            ),
            result
        )
    }

    @Test
    fun `read mail is asked for as not unseen`() {
        assertEquals(false, criteria("is:read ").unseen)
        assertNull(criteria("x ").unseen)
    }

    @Test
    fun `line breaks and control characters cannot get into the text`() {
        val query = SearchQuery(
            terms = listOf(SearchTerm("a\r\nb\u0000c"), SearchTerm(" \r\n ")),
            from = listOf(SearchTerm("x\ty"))
        )

        val result = ServerSearchCriteria.of(query)

        assertEquals(listOf("a b c"), result.text)
        assertEquals(listOf("x y"), result.from)
    }

    @Test
    fun `an empty query has empty criteria`() {
        assertEquals(MailSearchCriteria(), ServerSearchCriteria.of(SearchQuery.EMPTY))
    }

    @Test
    fun `criteria never show what is searched for`() {
        assertEquals(
            "MailSearchCriteria(REDACTED)",
            MailSearchCriteria(text = listOf("secret")).toString()
        )
        assertFalse("secret" in criteria("secret ").toString())
    }

    @Test
    fun `Gmail syntax quotes every word so that nothing typed is an operator`() {
        val raw = GmailRawQuery.of(
            MailSearchCriteria(
                text = listOf("cats OR dogs", "-x", "label:spam"),
                excluded = listOf("pizza"),
                from = listOf("ana perez"),
                to = listOf("bob"),
                subject = listOf("q3"),
                labels = listOf("My Label"),
                unseen = false,
                flagged = true,
                hasAttachment = true,
                since = LocalDate.of(2026, 3, 1),
                before = LocalDate.of(2026, 4, 5)
            )
        )

        assertEquals(
            "\"cats OR dogs\" \"-x\" \"label:spam\" -\"pizza\" from:\"ana perez\" to:\"bob\" " +
                "subject:\"q3\" label:\"My Label\" is:read is:starred has:attachment " +
                "after:2026/03/01 before:2026/04/05",
            raw
        )
    }

    @Test
    fun `Gmail syntax drops quotes from the text and writes unread mail`() {
        assertEquals(
            "\"a b\" is:unread",
            GmailRawQuery.of(MailSearchCriteria(text = listOf("a\"b"), unseen = true))
        )
        assertEquals("", GmailRawQuery.of(MailSearchCriteria()))
    }

    @Test
    fun `labels find folders by role in English, by name or by path`() {
        assertEquals(FolderRole.INBOX, LabelMatch.role(" Inbox "))
        assertEquals(FolderRole.TRASH, LabelMatch.role("bin"))
        assertEquals(FolderRole.JUNK, LabelMatch.role("spam"))
        assertEquals(FolderRole.DRAFTS, LabelMatch.role("drafts"))
        assertNull(LabelMatch.role("invoices"))
        assertTrue(LabelMatch.matches("sent", FolderRole.SENT, "Enviados", "Enviados"))
        assertTrue(LabelMatch.matches("INVOICES", FolderRole.OTHER, "Invoices", "Work/Invoices"))
        assertTrue(
            LabelMatch.matches("work/invoices", FolderRole.OTHER, "Invoices", "Work/Invoices")
        )
        assertFalse(LabelMatch.matches("work", FolderRole.OTHER, "Invoices", "Work/Invoices"))
    }
}
