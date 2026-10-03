// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchQueryParserTest {
    private fun parse(text: String) = SearchQueryParser.parse(text)

    private fun word(text: String, prefix: Boolean = false) = SearchTerm(text, false, prefix)

    private fun phrase(text: String) = SearchTerm(text, phrase = true)

    @Test
    fun `nothing typed asks for nothing`() {
        assertTrue(parse("").isEmpty)
        assertTrue(parse("   \t ").isEmpty)
    }

    @Test
    fun `free words are all required and the one being typed is a prefix`() {
        assertEquals(
            listOf(word("invoice"), word("mar", prefix = true)),
            parse("invoice mar").terms
        )
    }

    @Test
    fun `a space after the last word completes it`() {
        assertEquals(listOf(word("invoice"), word("mar")), parse("invoice mar ").terms)
    }

    @Test
    fun `quoted text is one phrase and is never a prefix`() {
        assertEquals(listOf(phrase("lunch plans")), parse("\"lunch plans\"").terms)
        assertEquals(listOf(word("a"), phrase("b c")), parse("a \"b c\"").terms)
    }

    @Test
    fun `an unclosed quote runs to the end while the user is still typing`() {
        assertEquals(listOf(phrase("lunch pl")), parse("\"lunch pl").terms)
    }

    @Test
    fun `a quoted single word is a plain word`() {
        assertEquals(listOf(word("lunch")), parse("\"lunch\"").terms)
    }

    @Test
    fun `a minus excludes a word or a phrase`() {
        val query = parse("lunch -pizza -\"ice cream\" ")

        assertEquals(listOf(word("lunch")), query.terms)
        assertEquals(listOf(word("pizza"), phrase("ice cream")), query.excluded)
    }

    @Test
    fun `a lone minus or one inside a word is just text`() {
        assertTrue(parse("- ").isEmpty)
        assertEquals(listOf(word("e-mail")), parse("e-mail ").terms)
    }

    @Test
    fun `from and to take a prefix, a quoted value is a phrase`() {
        val query = parse("from:ana to:bob@ex from:\"Ana Pérez\" ")

        assertEquals(listOf(word("ana", prefix = true), phrase("Ana Pérez")), query.from)
        assertEquals(listOf(word("bob@ex", prefix = true)), query.to)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `operators are case insensitive`() {
        val query = parse("FROM:ana Is:Unread HAS:Attachment ")

        assertEquals(listOf(word("ana", prefix = true)), query.from)
        assertEquals(true, query.unread)
        assertTrue(query.hasAttachment)
    }

    @Test
    fun `subject keeps a prefix only for the word being typed`() {
        assertEquals(listOf(word("inv", prefix = true)), parse("subject:inv").subject)
        assertEquals(listOf(word("inv")), parse("subject:inv ").subject)
        assertEquals(listOf(phrase("big news")), parse("subject:\"big news\" ").subject)
    }

    @Test
    fun `label and in both name a folder or label`() {
        assertEquals(listOf("inbox", "Work"), parse("in:inbox label:Work").labels)
        assertEquals(listOf("My Label"), parse("label:\"My Label\" ").labels)
    }

    @Test
    fun `flag operators`() {
        assertEquals(true, parse("is:unread ").unread)
        assertEquals(false, parse("is:read ").unread)
        assertNull(parse("lunch").unread)
        assertTrue(parse("is:starred ").starred)
        assertTrue(parse("is:flagged ").starred)
        assertTrue(parse("has:attachments ").hasAttachment)
        assertFalse(parse("has:attachment").isEmpty)
    }

    @Test
    fun `dates accept dashes and slashes`() {
        val query = parse("after:2026-03-01 before:2026/4/5 ")

        assertEquals(LocalDate.of(2026, 3, 1), query.after)
        assertEquals(LocalDate.of(2026, 4, 5), query.before)
    }

    @Test
    fun `a date that does not exist is searched as words`() {
        val query = parse("after:2026-13-45 ")

        assertNull(query.after)
        assertEquals(listOf(word("after:2026-13-45")), query.terms)
    }

    @Test
    fun `an operator with no value yet is ignored while typing`() {
        assertTrue(parse("from:").isEmpty)
        assertTrue(parse("subject:\"\" ").isEmpty)
        assertEquals(listOf(word("lunch")), parse("lunch to: ").terms)
    }

    @Test
    fun `an unknown operator or a value it does not take is plain words`() {
        assertEquals(listOf(word("color:red")), parse("color:red ").terms)
        assertEquals(listOf(word("is:banana")), parse("is:banana ").terms)
        assertEquals(listOf(word("has:drive")), parse("has:drive ").terms)
        assertFalse(parse("is:banana ").starred)
    }

    @Test
    fun `a negated operator is ignored instead of inverted`() {
        val query = parse("-from:ana lunch ")

        assertTrue(query.from.isEmpty())
        assertEquals(listOf(word("lunch")), query.terms)
        assertTrue(query.excluded.isEmpty())
    }

    @Test
    fun `a quoted operator is a phrase, not an operator`() {
        assertEquals(listOf(phrase("from: ana")), parse("\"from: ana\"").terms)
        assertTrue(parse("\"from: ana\"").from.isEmpty())
    }

    @Test
    fun `search engine syntax is only ever text`() {
        val query = parse("cats OR dogs NOT fish NEAR/3 (x) a* AND b ")

        assertEquals(
            listOf("cats", "OR", "dogs", "NOT", "fish", "NEAR/3", "(x)", "a*", "b"),
            query.terms.map { it.text }
        )
        assertTrue(query.terms.none { it.phrase })
        assertTrue(query.excluded.isEmpty())
    }

    @Test
    fun `text without a letter or a digit is dropped`() {
        assertTrue(parse("!!! ?? ** \"..\" ").isEmpty)
    }

    @Test
    fun `accents, other scripts and emoji survive`() {
        val query = parse("Ñandú 日本語 😀 ")

        assertEquals(listOf("Ñandú", "日本語"), query.terms.map { it.text })
    }

    @Test
    fun `words mixed with operators keep each in its place`() {
        val query = parse("report from:ana is:unread after:2026-01-01 pdf")

        assertEquals(listOf(word("report"), word("pdf", prefix = true)), query.terms)
        assertEquals(listOf(word("ana", prefix = true)), query.from)
        assertEquals(true, query.unread)
        assertEquals(LocalDate.of(2026, 1, 1), query.after)
    }

    @Test
    fun `a quote inside a word ends the word`() {
        assertEquals(
            listOf(word("abc"), word("def")),
            parse("abc\"def\" ").terms
        )
    }

    @Test
    fun `highlight terms depend on where the text is shown`() {
        val query = parse("report from:ana subject:q3 ")

        assertEquals(
            listOf("report", "ana"),
            query.highlightTerms(HighlightField.SENDER).map { it.text }
        )
        assertEquals(
            listOf("report", "q3"),
            query.highlightTerms(HighlightField.SUBJECT).map { it.text }
        )
        assertEquals(
            listOf("report"),
            query.highlightTerms(HighlightField.SNIPPET).map { it.text }
        )
    }

    @Test
    fun `date parsing needs year, month and day`() {
        assertNull(SearchQueryParser.date("2026-03"))
        assertNull(SearchQueryParser.date("26-03-01"))
        assertEquals(LocalDate.of(2026, 3, 1), SearchQueryParser.date(" 2026/03/01 "))
    }
}
