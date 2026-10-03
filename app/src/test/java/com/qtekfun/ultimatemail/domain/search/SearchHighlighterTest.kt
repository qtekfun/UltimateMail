// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchHighlighterTest {
    private fun highlighter(query: String) = SearchHighlighter(SearchQueryParser.parse(query).terms)

    /** The highlighted pieces of [text], to compare with what the user sees in bold. */
    private fun marked(query: String, text: String): List<String> =
        highlighter(query).ranges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `finds words ignoring case`() {
        assertEquals(listOf("Invoice", "invoice"), marked("INVOICE ", "Invoice for the invoice"))
    }

    @Test
    fun `finds words ignoring accents in the text and in the query`() {
        assertEquals(listOf("Ñandú"), marked("nandu ", "Un Ñandú corre"))
        assertEquals(listOf("nandu"), marked("Ñandú ", "un nandu corre"))
        assertEquals(listOf("café"), marked("cafe ", "un café solo"))
    }

    @Test
    fun `decomposed accents are one character`() {
        val decomposed = "café solo"

        assertEquals(listOf("café"), marked("café ", decomposed))
    }

    @Test
    fun `matches at the start of a word only, so a prefix works`() {
        assertEquals(listOf("mar"), marked("mar", "march and summary"))
        assertTrue(marked("ary ", "summary").isEmpty())
    }

    @Test
    fun `a phrase is highlighted as a whole`() {
        assertEquals(
            listOf("Lunch plans"),
            marked("\"lunch plans\" ", "Lunch plans or plans lunch")
        )
    }

    @Test
    fun `several words and overlapping matches merge`() {
        assertEquals(listOf("big", "news"), marked("big news ", "big news"))
        assertEquals(listOf("news"), marked("new news ", "news"))
    }

    @Test
    fun `emoji and other scripts keep their ranges whole`() {
        val text = "😀 日本語のメール 😀"

        assertEquals(listOf("日本語"), marked("日本語 ", text))
        assertEquals(listOf("日本語のメ"), marked("日本語のメ", text))
    }

    @Test
    fun `a match right after an emoji is found`() {
        assertEquals(listOf("hello"), marked("hello ", "😀 hello"))
    }

    @Test
    fun `characters that fold to several letters map back to the original`() {
        // The ligature fi becomes "fi" when folded.
        assertEquals(listOf("ﬁnal"), marked("final ", "the ﬁnal word"))
    }

    @Test
    fun `nothing to find or nothing to search in gives no ranges`() {
        assertTrue(highlighter("").ranges("anything").isEmpty())
        assertTrue(highlighter("x ").ranges("").isEmpty())
        assertTrue(highlighter("zzz ").ranges("nothing here").isEmpty())
        assertTrue(highlighter("").isEmpty)
    }

    @Test
    fun `excerpt keeps the stored snippet when it already shows a match`() {
        val terms = SearchQueryParser.parse("budget ").terms

        assertEquals(
            "The budget for March",
            SnippetExcerpt.of("The budget for March", "long body with budget", terms)
        )
    }

    @Test
    fun `excerpt shows the body around a match the snippet does not have`() {
        val terms = SearchQueryParser.parse("xylophone ").terms
        val body = "a ".repeat(100) + "the xylophone lesson " + "b ".repeat(100)

        val excerpt = SnippetExcerpt.of("a a a a", body, terms)

        assertTrue(excerpt.startsWith("…"))
        assertTrue(excerpt.endsWith("…"))
        assertTrue("xylophone" in excerpt)
        assertTrue(excerpt.length <= 142)
    }

    @Test
    fun `excerpt of a match near the start has no leading ellipsis`() {
        val terms = SearchQueryParser.parse("xylophone ").terms

        val excerpt = SnippetExcerpt.of("", "xylophone " + "b ".repeat(200), terms)

        assertTrue(excerpt.startsWith("xylophone"))
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun `excerpt does not cut an emoji in two`() {
        val terms = SearchQueryParser.parse("target ").terms
        val body = "😀".repeat(60) + " target " + "😀".repeat(120)

        val excerpt = SnippetExcerpt.of("", body, terms)

        assertTrue("target" in excerpt)
        var index = 0
        while (index < excerpt.length) {
            if (excerpt[index].isHighSurrogate()) {
                assertTrue(index + 1 < excerpt.length && excerpt[index + 1].isLowSurrogate())
                index++
            } else {
                assertTrue(!excerpt[index].isLowSurrogate())
            }
            index++
        }
    }

    @Test
    fun `excerpt falls back to the snippet when nothing matches or there is no body`() {
        val terms = SearchQueryParser.parse("absent ").terms

        assertEquals("snippet", SnippetExcerpt.of("snippet", "some body text", terms))
        assertEquals("snippet", SnippetExcerpt.of("snippet", null, terms))
        assertEquals("snippet", SnippetExcerpt.of("snippet", "  ", terms))
        assertEquals("snippet", SnippetExcerpt.of("snippet", "body", emptyList()))
    }

    @Test
    fun `excerpt only looks at the start of a huge body`() {
        val terms = SearchQueryParser.parse("needle ").terms
        val body = "x ".repeat(40_000) + "needle"

        assertEquals("snippet", SnippetExcerpt.of("snippet", body, terms))
    }
}
