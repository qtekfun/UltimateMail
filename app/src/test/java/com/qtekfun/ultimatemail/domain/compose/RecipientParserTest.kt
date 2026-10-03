// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipientParserTest {
    private fun parse(text: String) = RecipientParser.parse(text)

    @Test
    fun `a bare address has no name`() {
        val parsed = parse(" bob@example.test ")!!

        assertEquals("bob@example.test", parsed.address)
        assertNull(parsed.name)
    }

    @Test
    fun `a name before angle brackets is the display name`() {
        val parsed = parse("Bob Builder <bob@example.test>")!!

        assertEquals("bob@example.test", parsed.address)
        assertEquals("Bob Builder", parsed.name)
    }

    @Test
    fun `a quoted name may hold commas, at signs and escaped quotes`() {
        val parsed = parse("\"Builder, Bob \\\"the\\\" @work\" <bob@example.test>")!!

        assertEquals("Builder, Bob \"the\" @work", parsed.name)
        assertEquals("bob@example.test", parsed.address)
    }

    @Test
    fun `angle brackets alone and a comment name are understood`() {
        assertEquals("bob@example.test", parse("<bob@example.test>")!!.address)
        assertEquals("Bob", parse("bob@example.test (Bob)")!!.name)
    }

    @Test
    fun `a blank display name is dropped`() {
        assertNull(parse("\"  \" <bob@example.test>")!!.name)
    }

    @Test
    fun `a list is split on commas and semicolons outside quotes and brackets`() {
        val parsed = RecipientParser.parseList(
            "\"Builder, Bob\" <bob@example.test>; cy@example.test,\n" +
                "Di (a, b) <di@example.test>, , "
        )

        assertEquals(
            listOf("bob@example.test", "cy@example.test", "di@example.test"),
            parsed.valid.map { it.address }
        )
        assertEquals("Builder, Bob", parsed.valid.first().name)
        assertTrue(parsed.invalid.isEmpty())
    }

    @Test
    fun `a comma inside angle brackets does not split, an escaped quote does not end a name`() {
        val parsed = RecipientParser.parseList("\"a \\\" , b\" <x@example.test>, y@example.test")

        assertEquals(listOf("x@example.test", "y@example.test"), parsed.valid.map { it.address })
    }

    @Test
    fun `entries that are not addresses are reported as typed`() {
        val parsed = RecipientParser.parseList("bob@example.test, not an address ,carol@")

        assertEquals(listOf("bob@example.test"), parsed.valid.map { it.address })
        assertEquals(listOf("not an address", "carol@"), parsed.invalid)
    }

    @Test
    fun `an empty text has no recipients`() {
        val parsed = RecipientParser.parseList("  ")

        assertTrue(parsed.valid.isEmpty())
        assertTrue(parsed.invalid.isEmpty())
    }

    @Test
    fun `valid addresses in all their usual shapes`() {
        listOf(
            "a@example.test",
            "first.last@example.test",
            "first+tag@sub.example.co.uk",
            "o'brien@example.test",
            "\"odd local\"@example.test",
            "\"with \\\" quote\"@example.test",
            "a@xn--bcher-kva.example",
            "a@bücher.example"
        ).forEach { assertTrue(RecipientParser.isValid(it), it) }
    }

    @Test
    fun `invalid addresses are refused`() {
        listOf(
            "",
            "plain",
            "@example.test",
            "a@",
            "a@example",
            "a@@example.test",
            ".a@example.test",
            "a.@example.test",
            "a..b@example.test",
            "a b@example.test",
            "ñ@example.test",
            "a@-example.test",
            "a@example-.test",
            "a@exa mple.test",
            "a@example.123",
            "a@[1.2.3.4]",
            "\"unterminated@example.test",
            "\"bad\\\"@example.test",
            "\"ctrl\u0001\"@example.test",
            "a@" + "x".repeat(64) + ".test",
            "a".repeat(65) + "@example.test",
            "a@" + "x.".repeat(130) + "test"
        ).forEach { assertFalse(RecipientParser.isValid(it), it) }
    }

    @Test
    fun `an address that is too long is refused`() {
        val local = "a".repeat(64)
        val domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".example"

        assertFalse(RecipientParser.isValid("$local@$domain"))
    }

    @Test
    fun `an internationalized domain goes out in punycode and the name stays`() {
        val ascii = RecipientParser.toAscii(MailAddress("ana@bücher.example", "Ana"))!!

        assertEquals("ana@xn--bcher-kva.example", ascii.address)
        assertEquals("Ana", ascii.name)
        assertEquals("ana@example.test", RecipientParser.toAscii("ana@example.test"))
    }

    @Test
    fun `an invalid address has no ascii form`() {
        assertNull(RecipientParser.toAscii("nope"))
        assertNull(RecipientParser.toAscii(MailAddress("nope")))
    }

    @Test
    fun `formatting quotes a name only when it needs to be`() {
        assertEquals("bob@example.test", RecipientParser.format(MailAddress("bob@example.test")))
        assertEquals(
            "Bob <bob@example.test>",
            RecipientParser.format(MailAddress("bob@example.test", "Bob"))
        )
        assertEquals(
            "\"Builder, \\\"Bob\\\"\" <bob@example.test>",
            RecipientParser.format(MailAddress("bob@example.test", "Builder, \"Bob\""))
        )
        assertEquals(
            "bob@example.test",
            RecipientParser.format(MailAddress("bob@example.test", "  "))
        )
    }

    @Test
    fun `what is formatted can be parsed back`() {
        val original = listOf(
            MailAddress("bob@example.test", "Builder, Bob \"B\" \\ Jr."),
            MailAddress("cy@example.test"),
            MailAddress("di@example.test", "Di")
        )

        val back = RecipientParser.parseList(RecipientParser.formatList(original)).valid

        assertEquals(original, back)
    }

    @Test
    fun `the toString of a parsed list says nothing about the people`() {
        assertEquals(
            "ParsedRecipients(valid=1, invalid=1)",
            RecipientParser.parseList("bob@example.test, x").toString()
        )
    }
}
