// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.signature

import com.qtekfun.ultimatemail.domain.signature.ComposeKind.FORWARD
import com.qtekfun.ultimatemail.domain.signature.ComposeKind.NEW
import com.qtekfun.ultimatemail.domain.signature.ComposeKind.REPLY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val ANA = SignatureSettings("Ana\nCEO")
private val WORK = SignatureSettings("Work Inc.")
private const val QUOTE = "On Mon, Bob wrote:\n> hi\n> there"

class SignatureEditorTest {
    @Test
    fun `a new message gets the signature at the end after a blank line`() {
        assertEquals("Hello\n\n-- \nAna\nCEO", SignatureEditor.apply("Hello", NEW, ANA))
        assertEquals("Hello\n\n-- \nAna\nCEO", SignatureEditor.apply("Hello\n", NEW, ANA))
        assertEquals("Hello\n\n-- \nAna\nCEO", SignatureEditor.apply("Hello\n\n", NEW, ANA))
    }

    @Test
    fun `an empty new message leaves a line for the user above the signature`() {
        assertEquals("\n-- \nAna\nCEO", SignatureEditor.apply("", NEW, ANA))
    }

    @Test
    fun `a reply gets the signature above the quote by default`() {
        assertEquals(
            "Thanks!\n\n-- \nAna\nCEO\n\n$QUOTE",
            SignatureEditor.apply("Thanks!\n\n$QUOTE", REPLY, ANA)
        )
    }

    @Test
    fun `a reply without text of its own starts with a line for the user`() {
        assertEquals("\n-- \nAna\nCEO\n\n$QUOTE", SignatureEditor.apply(QUOTE, REPLY, ANA))
    }

    @Test
    fun `a reply gets the signature below the quote when the account says so`() {
        val below = ANA.copy(beforeQuote = false)

        assertEquals(
            "Thanks!\n\n$QUOTE\n\n-- \nAna\nCEO",
            SignatureEditor.apply("Thanks!\n\n$QUOTE", REPLY, below)
        )
    }

    @Test
    fun `a forward puts the signature above the forwarded text`() {
        val body = "---------- Forwarded message ---------\nFrom: Bob\n\nHi"

        assertEquals("\n-- \nAna\nCEO\n\n$body", SignatureEditor.apply(body, FORWARD, ANA))
    }

    @Test
    fun `a forward puts the signature below when the account says so`() {
        val body = "FYI\n\n---------- Forwarded message ---------\nFrom: Bob\n\nHi"

        assertEquals(
            "$body\n\n-- \nAna\nCEO",
            SignatureEditor.apply(body, FORWARD, ANA.copy(beforeQuote = false))
        )
    }

    @Test
    fun `the quote header is not mistaken for an attribution line when there is none`() {
        assertEquals("Note\n\n-- \nAna\nCEO\n\n> q", SignatureEditor.apply("Note\n> q", REPLY, ANA))
    }

    @Test
    fun `a reply whose quote was deleted gets the signature at the end`() {
        assertEquals("Ok\n\n-- \nAna\nCEO", SignatureEditor.apply("Ok", REPLY, ANA))
    }

    @Test
    fun `a new message ignores quote-like text`() {
        assertEquals(
            "> not a quote\n\n-- \nAna\nCEO",
            SignatureEditor.apply("> not a quote", NEW, ANA)
        )
    }

    @Test
    fun `a disabled or empty signature adds nothing, not even a delimiter`() {
        val body = "Hello\n\n$QUOTE"

        for (settings in listOf(
            ANA.copy(enabled = false),
            SignatureSettings(""),
            SignatureSettings(" \n\r\n\t ")
        )) {
            assertEquals(body, SignatureEditor.apply(body, REPLY, settings))
            assertNull(settings.block)
        }
    }

    @Test
    fun `the signature text is trimmed and its line endings normalised`() {
        val settings = SignatureSettings("\n\n  Ana  \r\n\r\nCEO \n\n")

        assertEquals(listOf("-- ", "  Ana", "", "CEO"), settings.block)
    }

    @Test
    fun `applying twice, or to a draft that has the signature, adds nothing`() {
        val once = SignatureEditor.apply("Hello\n\n$QUOTE", REPLY, ANA)

        assertEquals(once, SignatureEditor.apply(once, REPLY, ANA))
        assertEquals(once, SignatureEditor.apply(once, REPLY, WORK))
        assertTrue(SignatureEditor.hasSignature(once))
    }

    @Test
    fun `a chained reply quoting an older signature still gets exactly one of its own`() {
        val body = "\n\nOn Tue, Ana wrote:\n> Sure.\n>\n> -- \n> Ana\n> CEO"

        assertFalse(SignatureEditor.hasSignature(body))
        val result = SignatureEditor.apply(body, REPLY, ANA)

        assertEquals(
            "\n\n-- \nAna\nCEO\n\nOn Tue, Ana wrote:\n> Sure.\n>\n> -- \n> Ana\n> CEO",
            result
        )
        assertEquals(1, unquotedDelimiters(result))
        assertEquals(result, SignatureEditor.apply(result, REPLY, ANA))
    }

    @Test
    fun `a delimiter inside the quoted part is not the signature of the draft`() {
        val body = "Hi\n\nOn x wrote:\n> bye\n> -- \n> Bob"

        assertFalse(SignatureEditor.hasSignature(body))
        assertEquals(
            "Hi\n\n-- \nAna\nCEO\n\nOn x wrote:\n> bye\n> -- \n> Bob",
            SignatureEditor.apply(body, REPLY, ANA)
        )
    }

    @Test
    fun `a signature below the quote is recognised, and so is one under a quote with its own`() {
        val body = "On x wrote:\n> bye\n> -- \n> Bob\n\n-- \nAna\nCEO"

        assertTrue(SignatureEditor.hasSignature(body))
        assertEquals(body, SignatureEditor.apply(body, REPLY, ANA.copy(beforeQuote = false)))
    }

    @Test
    fun `the delimiter is found with trailing whitespace, without its space, and with CRLF`() {
        for (delimiter in listOf("-- ", "--", "--  ", "-- \t", "-- \r")) {
            val body = "Hi\n\n$delimiter\nAna\nCEO"
            assertTrue(SignatureEditor.hasSignature(body), delimiter)
            assertEquals(body, SignatureEditor.apply(body, NEW, ANA), delimiter)
        }
        assertTrue(SignatureEditor.hasSignature("Hi\r\n\r\n-- \r\nAna"))
    }

    @Test
    fun `lines that only look like the delimiter are not signatures`() {
        for (line in listOf("---", "--x", "-- not", "- -", "—", "> --")) {
            assertFalse(SignatureEditor.hasSignature("Hi\n$line\nAna"), line)
        }
    }

    @Test
    fun `a delimiter between quoted lines is part of an inline reply, not a signature`() {
        assertFalse(SignatureEditor.hasSignature("On x wrote:\n> a\n-- \n> b"))
    }

    @Test
    fun `a delimiter followed only by blank lines is an empty signature block`() {
        val body = "Hi\n\n-- \n\n\n"

        assertTrue(SignatureEditor.hasSignature(body))
        assertEquals("Hi\n\n-- \nWork Inc.\n\n\n", SignatureEditor.replace(body, null, WORK, NEW))
    }

    @Test
    fun `a forwarded message with its own signature does not count as ours`() {
        val body = "---------- Forwarded message ---------\nHello\n-- \nBob"

        assertFalse(SignatureEditor.hasSignature(body))
        assertEquals("\n-- \nAna\nCEO\n\n$body", SignatureEditor.apply(body, FORWARD, ANA))
    }

    @Test
    fun `CRLF bodies stay CRLF`() {
        val body = "Hi\r\n\r\nOn x wrote:\r\n> q\r\n"

        val above = SignatureEditor.apply(body, REPLY, ANA)
        val below = SignatureEditor.apply(body, REPLY, ANA.copy(beforeQuote = false))

        assertEquals("Hi\r\n\r\n-- \r\nAna\r\nCEO\r\n\r\nOn x wrote:\r\n> q\r\n", above)
        assertEquals("Hi\r\n\r\nOn x wrote:\r\n> q\r\n\r\n-- \r\nAna\r\nCEO", below)
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(above + below))
    }

    @Test
    fun `mixed line endings in the user's text are kept as they were`() {
        val result = SignatureEditor.apply("a\r\nb\nc", NEW, ANA)

        assertEquals("a\r\nb\nc\r\n\r\n-- \r\nAna\r\nCEO", result)
    }

    // --- switching the sender -------------------------------------------------------------

    @Test
    fun `switching accounts swaps only the signature block`() {
        val draft = SignatureEditor.apply("Thanks!\n\n$QUOTE", REPLY, ANA)

        val switched = SignatureEditor.replace(draft, ANA, WORK, REPLY)

        assertEquals("Thanks!\n\n-- \nWork Inc.\n\n$QUOTE", switched)
        assertEquals(SignatureEditor.apply("Thanks!\n\n$QUOTE", REPLY, WORK), switched)
    }

    @Test
    fun `text typed above and below the signature survives the switch`() {
        val draft = "Top\n\n-- \nAna\nCEO\n\nBelow, typed later\n\n$QUOTE"

        assertEquals(
            "Top\n\n-- \nWork Inc.\n\nBelow, typed later\n\n$QUOTE",
            SignatureEditor.replace(draft, ANA, WORK, REPLY)
        )
    }

    @Test
    fun `a signature below the quote is swapped in place`() {
        val below = ANA.copy(beforeQuote = false)
        val draft = SignatureEditor.apply("Hi\n\n$QUOTE", REPLY, below)

        assertEquals(
            "Hi\n\n$QUOTE\n\n-- \nWork Inc.",
            SignatureEditor.replace(draft, below, WORK.copy(beforeQuote = false), REPLY)
        )
    }

    @Test
    fun `switching to an account and back restores the draft exactly`() {
        val draft = SignatureEditor.apply("Hi there\n\n$QUOTE", REPLY, ANA)

        val back = SignatureEditor.replace(
            SignatureEditor.replace(draft, ANA, WORK, REPLY),
            WORK,
            ANA,
            REPLY
        )

        assertEquals(draft, back)
    }

    @Test
    fun `when the previous account had no signature the new one is added`() {
        val body = "Hi\n\n$QUOTE"

        for (previous in listOf(null, SignatureSettings(""), ANA.copy(enabled = false))) {
            assertEquals(
                "Hi\n\n-- \nWork Inc.\n\n$QUOTE",
                SignatureEditor.replace(body, previous, WORK, REPLY)
            )
        }
    }

    @Test
    fun `when the previous signature was deleted by the user the new one is added`() {
        assertEquals("Hi\n\n-- \nWork Inc.", SignatureEditor.replace("Hi", ANA, WORK, NEW))
    }

    @Test
    fun `switching to an account without signature removes the block and its separator`() {
        val atEnd = SignatureEditor.apply("Hi", NEW, ANA)
        val aboveQuote = SignatureEditor.apply("Hi\n\n$QUOTE", REPLY, ANA)

        assertEquals("Hi", SignatureEditor.replace(atEnd, ANA, SignatureSettings(""), NEW))
        assertEquals(
            "Hi\n\n$QUOTE",
            SignatureEditor.replace(aboveQuote, ANA, ANA.copy(enabled = false), REPLY)
        )
    }

    @Test
    fun `removing a block with no blank line around it, or at the very start`() {
        val tight = "Hi\n-- \nAna\nbye"

        assertEquals(
            "Hi\nbye",
            SignatureEditor.replace(
                tight,
                SignatureSettings("Ana"),
                WORK.copy(enabled = false),
                NEW
            )
        )
        assertEquals(
            "rest",
            SignatureEditor.replace(
                "-- \nAna\n\nrest",
                SignatureSettings("Ana"),
                SignatureSettings(""),
                NEW
            )
        )
        assertEquals(
            "",
            SignatureEditor.replace(
                "-- \nAna",
                SignatureSettings("Ana"),
                SignatureSettings(""),
                NEW
            )
        )
    }

    @Test
    fun `switching with nothing to remove or add leaves the body alone`() {
        assertEquals("Hi", SignatureEditor.replace("Hi", null, SignatureSettings(""), NEW))
        assertEquals("Hi", SignatureEditor.replace("Hi", ANA, ANA.copy(enabled = false), NEW))
    }

    @Test
    fun `an edited signature is still replaced, up to the quote`() {
        val draft = "Hi\n\n-- \nAna (edited)\nmore\n\n$QUOTE"

        assertEquals(
            "Hi\n\n-- \nWork Inc.\n\n$QUOTE",
            SignatureEditor.replace(draft, ANA, WORK, REPLY)
        )
        assertEquals(
            "Hi\n\n-- \nWork Inc.\n\n$QUOTE",
            SignatureEditor.replace(draft, null, WORK, REPLY)
        )
    }

    @Test
    fun `trailing blank lines after an edited signature are kept`() {
        assertEquals(
            "Hi\n\n-- \nWork Inc.\n\n\n",
            SignatureEditor.replace("Hi\n\n-- \nAna x\n\n\n", ANA, WORK, NEW)
        )
    }

    @Test
    fun `CRLF drafts keep CRLF when switching`() {
        val draft = "Hi\r\n\r\n-- \r\nAna\r\nCEO"

        assertEquals("Hi\r\n\r\n-- \r\nWork Inc.", SignatureEditor.replace(draft, ANA, WORK, NEW))
        assertEquals("Hi", SignatureEditor.replace(draft, ANA, SignatureSettings(""), NEW))
    }

    @Test
    fun `the signature of a forward is swapped without touching the forwarded one`() {
        val below = ANA.copy(beforeQuote = false)
        val body = "---------- Forwarded message ---------\nHello\n-- \nBob\n\n-- \nAna\nCEO"

        assertEquals(body, SignatureEditor.apply(body, FORWARD, below))
        assertEquals(
            "---------- Forwarded message ---------\nHello\n-- \nBob\n\n-- \nWork Inc.",
            SignatureEditor.replace(body, below, WORK, FORWARD)
        )
    }

    @Test
    fun `a signature block longer than the body is simply not found`() {
        assertEquals(
            "Hi\n\n-- \nWork Inc.",
            SignatureEditor.replace("Hi", SignatureSettings("a\nb\nc\nd"), WORK, NEW)
        )
    }

    @Test
    fun `very long bodies are handled and keep a single signature`() {
        val text = (1..50_000).joinToString("\n") { "line $it of the user's text" }
        val quote = (1..50_000).joinToString("\n") { "> quoted line $it" }
        val body = "$text\n\nOn x wrote:\n$quote"

        val withSignature = SignatureEditor.apply(body, REPLY, ANA)
        val switched = SignatureEditor.replace(withSignature, ANA, WORK, REPLY)

        assertEquals(1, unquotedDelimiters(withSignature))
        assertEquals(1, unquotedDelimiters(switched))
        assertEquals(
            body,
            SignatureEditor.replace(withSignature, ANA, SignatureSettings(""), REPLY)
        )
    }
}

/** Delimiter lines that are not part of a quote. */
internal fun unquotedDelimiters(body: String): Int =
    body.split("\r\n", "\n").count { Regex("--[ \t\r]*").matches(it) }
