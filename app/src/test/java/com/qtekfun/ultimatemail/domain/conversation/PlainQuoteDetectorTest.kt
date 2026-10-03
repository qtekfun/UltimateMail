// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlainQuoteDetectorTest {
    private fun split(vararg lines: String) = PlainQuoteDetector.split(lines.joinToString("\n"))

    @Test
    fun `a message without any quote is left whole`() {
        val text = "Hi Ana,\n\nSee you on Friday.\n\nBob"

        val result = PlainQuoteDetector.split(text)

        assertEquals(QuoteSplit.whole(text), result)
        assertFalse(result.hasQuote)
    }

    @Test
    fun `top posting with an English attribution folds the attribution and the quote`() {
        val result = split(
            "Sounds good, let's do it.",
            "",
            "On Mon, Jan 1, 2024 at 10:00 AM Ana Garcia <ana@example.test> wrote:",
            "> Are you free on Friday?",
            "> Ana"
        )

        assertEquals("Sounds good, let's do it.", result.visible)
        assertEquals(
            "On Mon, Jan 1, 2024 at 10:00 AM Ana Garcia <ana@example.test> wrote:\n" +
                "> Are you free on Friday?\n> Ana",
            result.quoted
        )
    }

    @Test
    fun `an attribution that Gmail wraps over two lines is found`() {
        val result = split(
            "Yes.",
            "",
            "On Tue, Feb 6, 2024 at 3:15 PM Ana Garcia <ana.garcia@example.test>",
            "wrote:",
            "> Question?"
        )

        assertEquals("Yes.", result.visible)
        assertTrue(result.quoted!!.startsWith("On Tue, Feb 6"))
    }

    @Test
    fun `a Spanish attribution is found`() {
        val result = split(
            "Perfecto, gracias.",
            "",
            "El lun, 1 ene 2024 a las 10:00, Ana García (<ana@example.test>) escribió:",
            "",
            "> ¿Nos vemos el viernes?"
        )

        assertEquals("Perfecto, gracias.", result.visible)
        assertTrue(result.quoted!!.contains("escribió:"))
    }

    @Test
    fun `French German Italian and Portuguese attributions are found`() {
        val attributions = listOf(
            "Le 01/01/2024 à 10:00, Ana <ana@example.test> a écrit :",
            "Am 01.01.2024 um 10:00 schrieb Ana <ana@example.test>:",
            "Il giorno 01/01/2024 alle 10:00 Ana <ana@example.test> ha scritto:",
            "Em seg., 1 de jan. de 2024 às 10:00, Ana <ana@example.test> escreveu:"
        )
        attributions.forEach { attribution ->
            val result = split("Reply", "", attribution, "> original")

            assertEquals("Reply", result.visible, attribution)
            assertEquals(attribution + "\n> original", result.quoted, attribution)
        }
    }

    @Test
    fun `an attribution whose quote is not prefixed is folded to the end`() {
        val result = split(
            "Got it.",
            "",
            "On 3 Jan 2024, at 09:15, Ana <ana@example.test> wrote:",
            "Can you send me the file?",
            "Thanks"
        )

        assertEquals("Got it.", result.visible)
        assertEquals(
            "On 3 Jan 2024, at 09:15, Ana <ana@example.test> wrote:\n" +
                "Can you send me the file?\nThanks",
            result.quoted
        )
    }

    @Test
    fun `quote marks without an attribution still fold the quote`() {
        val result = split("Agreed.", "", "> We should meet.", ">", "> > Earlier text.")

        assertEquals("Agreed.", result.visible)
        assertEquals("> We should meet.\n>\n> > Earlier text.", result.quoted)
    }

    @Test
    fun `an Outlook original message separator folds everything below it`() {
        val result = split(
            "Please see my answer below.",
            "",
            "-----Original Message-----",
            "From: Ana Garcia",
            "Sent: Monday, January 1, 2024 10:00 AM",
            "To: Bob",
            "Subject: Friday",
            "",
            "Are you free?"
        )

        assertEquals("Please see my answer below.", result.visible)
        assertTrue(result.quoted!!.startsWith("-----Original Message-----"))
        assertTrue(result.quoted.endsWith("Are you free?"))
    }

    @Test
    fun `an Outlook header block with a rule above it is folded from the rule`() {
        val result = split(
            "Thanks!",
            "",
            "________________________________",
            "From: Ana Garcia <ana@example.test>",
            "Sent: Monday, January 1, 2024 10:00 AM",
            "To: Bob <bob@example.test>",
            "Subject: Friday",
            "",
            "Are you free?"
        )

        assertEquals("Thanks!", result.visible)
        assertTrue(result.quoted!!.startsWith("____"))
    }

    @Test
    fun `a Spanish Outlook header block is found`() {
        val result = split(
            "De acuerdo.",
            "",
            "De: Ana García <ana@example.test>",
            "Enviado: lunes, 1 de enero de 2024 10:00",
            "Para: Bob",
            "Asunto: Viernes",
            "",
            "¿Quedamos?"
        )

        assertEquals("De acuerdo.", result.visible)
        assertTrue(result.quoted!!.startsWith("De: Ana"))
    }

    @Test
    fun `a lone From line is not taken for a quote header`() {
        val text = "Hi,\n\nFrom: me, to you with love.\n\nBye"

        assertFalse(PlainQuoteDetector.split(text).hasQuote)
    }

    @Test
    fun `a forwarded message is content and is not folded`() {
        val text = listOf(
            "FYI, see below.",
            "",
            "---------- Forwarded message ---------",
            "From: Ana Garcia <ana@example.test>",
            "Date: Mon, Jan 1, 2024 at 10:00 AM",
            "Subject: Friday",
            "To: Bob <bob@example.test>",
            "",
            "Are you free?"
        ).joinToString("\n")

        assertEquals(QuoteSplit.whole(text), PlainQuoteDetector.split(text))
    }

    @Test
    fun `forward markers of other clients are content too`() {
        listOf(
            "Begin forwarded message:",
            "---------- Mensaje reenviado ----------",
            "Inicio del mensaje reenviado:"
        ).forEach { marker ->
            val text = "Look\n\n$marker\nFrom: Ana\nDate: today\nTo: you\nSubject: x\n\nText"

            assertFalse(PlainQuoteDetector.split(text).hasQuote, marker)
        }
    }

    @Test
    fun `a reply below a forwarded message folds only the quote after it`() {
        val result = split(
            "FYI",
            "",
            "---------- Forwarded message ---------",
            "From: Ana <ana@example.test>",
            "Date: today",
            "Subject: x",
            "",
            "Body of the forwarded mail",
            "",
            "On Mon, Jan 1, 2024 at 10:00 AM Bob <bob@example.test> wrote:",
            "> earlier"
        )

        assertTrue(result.visible.contains("Body of the forwarded mail"))
        assertTrue(result.quoted!!.startsWith("On Mon, Jan 1"))
    }

    @Test
    fun `interleaved replies are content and nothing is folded`() {
        val text = listOf(
            "On Mon, Jan 1, 2024 at 10:00 AM Ana <ana@example.test> wrote:",
            "> Are you free on Friday?",
            "Yes, all day.",
            "> And on Saturday?",
            "Only in the morning."
        ).joinToString("\n")

        assertEquals(QuoteSplit.whole(text), PlainQuoteDetector.split(text))
    }

    @Test
    fun `text after the quote means it is not folded`() {
        val text = "Hi\n> quoted\nmailing list footer"

        assertNull(PlainQuoteDetector.split(text).quoted)
    }

    @Test
    fun `a message that is only a quote is shown in full`() {
        val text = "> just a quote\n> and more"

        assertEquals(QuoteSplit.whole(text), PlainQuoteDetector.split(text))
    }

    @Test
    fun `an ordinary sentence that looks like an attribution is not one`() {
        val text = "Hello\n\nEl jefe escribió esto:\n* uno\n* dos"

        assertFalse(PlainQuoteDetector.split(text).hasQuote)
    }

    @Test
    fun `Windows line endings are understood`() {
        val result = PlainQuoteDetector.split("Ok\r\n\r\n> old\r\n> older")

        assertEquals("Ok", result.visible)
        assertEquals("> old\n> older", result.quoted)
    }

    @Test
    fun `trailing blank lines after the quote do not matter`() {
        val result = PlainQuoteDetector.split("Ok\n\n> old\n\n\n")

        assertEquals("Ok", result.visible)
        assertEquals("> old", result.quoted)
    }

    @Test
    fun `an empty message has no quote`() {
        assertEquals(QuoteSplit.whole(""), PlainQuoteDetector.split(""))
    }
}
