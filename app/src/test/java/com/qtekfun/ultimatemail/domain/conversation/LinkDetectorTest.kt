// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkDetectorTest {
    private fun links(text: String) = LinkDetector.runs(text).filter { it.target != null }

    @Test
    fun `text without links is one run`() {
        assertEquals(listOf(TextRun("Nothing to see")), LinkDetector.runs("Nothing to see"))
    }

    @Test
    fun `the runs always add up to the original text`() {
        val text =
            "Go to https://example.test/a?b=1, or write ana@example.test. Bye (www.example.test)."

        assertEquals(text, LinkDetector.runs(text).joinToString("") { it.text })
    }

    @Test
    fun `https and http addresses become links to themselves`() {
        assertEquals(
            listOf(
                TextRun("https://example.test/path", "https://example.test/path"),
                TextRun("http://example.test", "http://example.test")
            ),
            links("see https://example.test/path and http://example.test now")
        )
    }

    @Test
    fun `an address starting with www gets https`() {
        assertEquals(
            listOf(TextRun("www.example.test/x", "https://www.example.test/x")),
            links("visit www.example.test/x")
        )
    }

    @Test
    fun `an email address becomes a mailto link`() {
        assertEquals(
            listOf(TextRun("ana.garcia+news@example.test", "mailto:ana.garcia+news@example.test")),
            links("write to ana.garcia+news@example.test please")
        )
    }

    @Test
    fun `punctuation that ends the sentence is not part of the link`() {
        assertEquals(
            listOf("https://example.test/a", "https://example.test/b", "https://example.test/c"),
            links("a https://example.test/a. b https://example.test/b, c https://example.test/c!")
                .map { it.text }
        )
    }

    @Test
    fun `a closing bracket is cut only when the link did not open it`() {
        assertEquals(
            listOf("https://example.test/a"),
            links("(see https://example.test/a)").map { it.text }
        )
        assertEquals(
            listOf("https://en.example.test/wiki/Foo_(bar)"),
            links("see https://en.example.test/wiki/Foo_(bar)").map { it.text }
        )
    }

    @Test
    fun `an address inside angle brackets is found without the brackets`() {
        assertEquals(
            listOf("https://example.test/x"),
            links("<https://example.test/x>").map { it.text }
        )
    }

    @Test
    fun `dangerous schemes are never links`() {
        assertEquals(emptyList<TextRun>(), links("javascript:alert(1) and file:///etc/passwd"))
    }

    @Test
    fun `a link keeps its own case and uppercase scheme works`() {
        assertEquals(
            listOf("HTTPS://Example.test/Path"),
            links("HTTPS://Example.test/Path").map { it.text }
        )
    }
}
