// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtsQueryBuilderTest {
    private fun plan(text: String) = FtsQueryBuilder.plan(SearchQueryParser.parse(text))

    @Test
    fun `an empty query has nothing to match`() {
        assertTrue(plan("").isEmpty)
        assertTrue(plan("is:unread after:2026-01-01").isEmpty)
    }

    @Test
    fun `words are quoted phrases joined by AND and the word being typed is a prefix`() {
        assertEquals(listOf("\"budget\" \"mar*\""), plan("budget mar").matches)
        assertEquals(listOf("\"budget\" \"mar\""), plan("budget mar ").matches)
    }

    @Test
    fun `a phrase stays together`() {
        assertEquals(listOf("\"lunch plans\" \"x\""), plan("\"lunch plans\" x ").matches)
    }

    @Test
    fun `subject goes to its own column`() {
        assertEquals(listOf("\"q3\" subject:report"), plan("subject:report q3 ").matches)
    }

    @Test
    fun `from looks in the name or in the address, so there are two matches`() {
        assertEquals(
            listOf(
                "\"budget\" senderName:ana*",
                "\"budget\" senderAddress:ana*"
            ),
            plan("budget from:ana").matches
        )
    }

    @Test
    fun `a column filter is one bare word per word, since FTS4 filters bare words only`() {
        assertEquals(
            listOf("senderName:ana senderName:perez", "senderAddress:ana senderAddress:perez"),
            plan("from:\"ana perez\" ").matches
        )
        assertEquals(
            listOf("senderName:ana* senderName:perez*", "senderAddress:ana* senderAddress:perez*"),
            plan("from:ana from:perez").matches
        )
    }

    @Test
    fun `words of a column filter are lower case, accented ones included, and never operators`() {
        assertEquals(
            "subject:or subject:near subject:3",
            FtsQueryBuilder.phrase(SearchTerm("OR NEAR/3"), "subject")
        )
        assertEquals(
            "senderName:pérez*",
            FtsQueryBuilder.phrase(SearchTerm("PÉREZ", prefix = true), "senderName")
        )
    }

    @Test
    fun `punctuation splits the words of a column filter and quotes cannot get in`() {
        assertEquals(
            "senderAddress:ana senderAddress:lopez senderAddress:example senderAddress:test*",
            FtsQueryBuilder.phrase(
                SearchTerm("ana.lopez@example.\"test\"*", prefix = true),
                "senderAddress"
            )
        )
    }

    @Test
    fun `excluded text is a separate expression each`() {
        val plan = plan("lunch -pizza -\"ice cream\" ")

        assertEquals(listOf("\"lunch\""), plan.matches)
        assertEquals(listOf("\"pizza\"", "\"ice cream\""), plan.excludes)
    }

    @Test
    fun `only excluded text still needs the index`() {
        val plan = plan("-pizza ")

        assertTrue(plan.matches.isEmpty())
        assertEquals(listOf("\"pizza\""), plan.excludes)
        assertTrue(!plan.isEmpty)
    }

    @Test
    fun `engine operators lose their meaning`() {
        assertEquals(
            listOf("\"cats\" \"OR\" \"dogs\" \"NOT\" \"fish\" \"NEAR/3\" \"(x)\" \"a\" \"b*\""),
            plan("cats OR dogs NOT fish NEAR/3 (x) a* b").matches
        )
    }

    @Test
    fun `quotes, stars and control characters never reach the expression`() {
        val phrase = FtsQueryBuilder.phrase(
            SearchTerm("a\"b*c\u0000d\ne", prefix = true),
            null
        )

        assertEquals("\"a b c d e*\"", phrase)
    }

    @Test
    fun `a prefix star sits right after the last word`() {
        val phrase = FtsQueryBuilder.phrase(SearchTerm("foo-bar..", prefix = true), null)

        assertEquals("\"foo-bar*\"", phrase)
    }

    @Test
    fun `text with nothing searchable gives no expression`() {
        assertNull(FtsQueryBuilder.phrase(SearchTerm("\"*\""), null))
        assertNull(FtsQueryBuilder.phrase(SearchTerm("  "), "subject"))
    }

    @Test
    fun `accents and other scripts are kept for the tokenizer to fold`() {
        assertEquals(listOf("\"Ñandú\" \"日本語\""), plan("Ñandú 日本語 ").matches)
    }
}
