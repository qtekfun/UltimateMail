// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageSnippetTest {
    @Test
    fun `plain text is put on one line`() {
        val text = "Hi Ana,\n\n  lunch on   Friday?\n"
        assertEquals("Hi Ana, lunch on Friday?", MessageSnippet.of(text, null))
    }

    @Test
    fun `quoted lines are left out`() {
        val text = "Yes, see you.\n> On Monday Bob wrote:\n> hello\nThanks"
        assertEquals("Yes, see you. Thanks", MessageSnippet.of(text, null))
    }

    @Test
    fun `html is turned into text when there is no plain part`() {
        assertEquals("Hello Ana bye", MessageSnippet.of(null, "<p>Hello <b>Ana</b></p><p>bye</p>"))
    }

    @Test
    fun `the plain part wins over the html`() {
        assertEquals("plain", MessageSnippet.of("plain", "<p>html</p>"))
    }

    @Test
    fun `a message without text has an empty snippet`() {
        assertEquals("", MessageSnippet.of(null, null))
        assertEquals("", MessageSnippet.of("", null))
    }

    @Test
    fun `a long text is cut`() {
        assertEquals(MessageSnippet.MAX_LENGTH, MessageSnippet.of("word ".repeat(200), null).length)
    }
}
