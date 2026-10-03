// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncomingParserTest {
    private fun mailto(uri: String?, extras: IncomingIntent? = null): IncomingCompose? =
        IncomingParser.parse(
            (extras ?: IncomingIntent(IncomingParser.ACTION_SENDTO)).copy(
                action = IncomingParser.ACTION_SENDTO,
                data = uri
            )
        )

    private fun IncomingCompose.toAddresses() = to.map { it.address }

    @Test
    fun `a plain mailto gives its recipient`() {
        val result = mailto("mailto:ana@example.test")!!

        assertEquals(listOf("ana@example.test"), result.toAddresses())
        assertEquals("", result.subject)
        assertEquals("", result.body)
    }

    @Test
    fun `mailto takes to cc bcc subject and body from the query`() {
        val result = mailto(
            "mailto:ana@example.test?cc=bob@example.test&bcc=eve@example.test" +
                "&subject=Hello&body=Line%20one%0D%0ALine%20two"
        )!!

        assertEquals(listOf("bob@example.test"), result.cc.map { it.address })
        assertEquals(listOf("eve@example.test"), result.bcc.map { it.address })
        assertEquals("Hello", result.subject)
        assertEquals("Line one\nLine two", result.body)
    }

    @Test
    fun `several recipients in the path and in a to parameter are joined without repeats`() {
        val result = mailto(
            "mailto:ana@example.test,bob@example.test?to=BOB@example.test,cy@example.test"
        )!!

        assertEquals(
            listOf("ana@example.test", "bob@example.test", "cy@example.test"),
            result.toAddresses()
        )
    }

    @Test
    fun `percent encoded characters are decoded as UTF-8 and a plus stays a plus`() {
        val result = mailto(
            "mailto:j%C3%BCrgen@example.test?subject=Caf%C3%A9%20%2B%20a+b&body=%E2%82%AC5"
        )

        // A non-ASCII local part is refused by the address rules, so no recipient is made.
        assertTrue(result!!.to.isEmpty())
        assertEquals("Café + a+b", result.subject)
        assertEquals("€5", result.body)
    }

    @Test
    fun `an encoded at sign in the address is decoded`() {
        assertEquals(
            listOf("ana@example.test"),
            mailto("mailto:ana%40example.test")!!.toAddresses()
        )
    }

    @Test
    fun `a bad percent sequence is kept as typed instead of failing the link`() {
        val result = mailto("mailto:ana@example.test?subject=100%25%20sure%zz%4")!!

        assertEquals("100% sure%zz%4", result.subject)
    }

    @Test
    fun `invalid addresses are dropped and the valid ones kept`() {
        val result = mailto("mailto:not-an-address,ana@example.test,@x")!!

        assertEquals(listOf("ana@example.test"), result.toAddresses())
    }

    @Test
    fun `line breaks in the subject cannot inject headers`() {
        val result = mailto("mailto:ana@example.test?subject=Hi%0D%0ABcc:%20eve@example.test")!!

        assertEquals("Hi  Bcc: eve@example.test", result.subject)
        assertTrue(result.bcc.isEmpty())
    }

    @Test
    fun `an empty mailto still opens a composer and the scheme is case insensitive`() {
        val result = mailto("MAILTO:")

        assertEquals(IncomingCompose(), result)
    }

    @Test
    fun `data that is not a mailto or is missing is ignored`() {
        assertNull(mailto("https://example.test/?to=ana@example.test"))
        assertNull(mailto("sms:12345"))
        assertNull(mailto(null))
        assertNull(mailto(""))
    }

    @Test
    fun `unknown actions and parameters are ignored`() {
        assertNull(IncomingParser.parse(IncomingIntent("android.intent.action.VIEW", "mailto:a@b.test")))
        assertNull(IncomingParser.parse(IncomingIntent(null)))
        val result = mailto("mailto:ana@example.test?x-evil=1&in-reply-to=%3Cx%3E&&=&subject")!!
        assertEquals("", result.subject)
        assertEquals(listOf("ana@example.test"), result.toAddresses())
    }

    @Test
    fun `the fragment is not part of the message`() {
        val result = mailto("mailto:ana@example.test?subject=Hi#ignored")!!

        assertEquals("Hi", result.subject)
    }

    @Test
    fun `extras of a sendto fill what the uri left out but never replace it`() {
        val extras = IncomingIntent(
            IncomingParser.ACTION_SENDTO,
            subject = "From extra",
            text = "Body from extra",
            cc = listOf("bob@example.test")
        )

        val withSubject = mailto("mailto:ana@example.test?subject=From%20uri", extras)!!
        val without = mailto("mailto:ana@example.test", extras)!!

        assertEquals("From uri", withSubject.subject)
        assertEquals("Body from extra", withSubject.body)
        assertEquals("From extra", without.subject)
        assertEquals(listOf("bob@example.test"), without.cc.map { it.address })
    }

    @Test
    fun `send takes the text as body and the extras as the rest`() {
        val result = IncomingParser.parse(
            IncomingIntent(
                IncomingParser.ACTION_SEND,
                text = "Look at this\r\nplease",
                subject = "Link",
                to = listOf("Ana <ana@example.test>")
            )
        )!!

        assertEquals("Look at this\nplease", result.body)
        assertEquals("Link", result.subject)
        assertEquals(listOf("ana@example.test"), result.toAddresses())
    }

    @Test
    fun `a send with nothing usable opens nothing`() {
        assertNull(IncomingParser.parse(IncomingIntent(IncomingParser.ACTION_SEND)))
        assertNull(
            IncomingParser.parse(
                IncomingIntent(
                    IncomingParser.ACTION_SEND,
                    streams = listOf("file:///data/data/x/secret", "javascript:alert(1)")
                )
            )
        )
    }

    @Test
    fun `send multiple keeps the content streams only`() {
        val result = IncomingParser.parse(
            IncomingIntent(
                IncomingParser.ACTION_SEND_MULTIPLE,
                streams = listOf(
                    "content://media/external/images/1",
                    "file:///data/data/com.qtekfun.ultimatemail/secret",
                    "CONTENT://docs/doc/2",
                    "content://media/external/images/1",
                    "content://user@evil/x",
                    "content://",
                    "content:///nopath"
                )
            )
        )!!

        assertEquals(
            listOf("content://media/external/images/1", "CONTENT://docs/doc/2"),
            result.attachments
        )
    }

    @Test
    fun `the apps own provider is never read as an attachment`() {
        val result = IncomingParser.parse(
            IncomingIntent(
                IncomingParser.ACTION_SEND,
                text = "x",
                streams = listOf(
                    "content://com.qtekfun.ultimatemail.files/downloads/a.pdf",
                    "content://COM.QTEKFUN.ULTIMATEMAIL.FILES/b",
                    "content://other.provider/c"
                )
            ),
            ownAuthorities = setOf("com.qtekfun.ultimatemail.files")
        )!!

        assertEquals(listOf("content://other.provider/c"), result.attachments)
    }

    @Test
    fun `everything is capped`() {
        val many = (1..300).map { "u$it@example.test" }
        val streams = (1..50).map { "content://p/$it" } + "content://p/" + "x".repeat(3000)
        val result = IncomingParser.parse(
            IncomingIntent(
                IncomingParser.ACTION_SEND,
                text = "b".repeat(IncomingParser.MAX_BODY + 10),
                subject = "s".repeat(5000),
                to = many,
                cc = many,
                streams = streams
            )
        )!!

        assertEquals(IncomingParser.MAX_RECIPIENTS, result.to.size)
        assertEquals(IncomingParser.MAX_RECIPIENTS, result.cc.size)
        assertEquals(IncomingParser.MAX_ATTACHMENTS, result.attachments.size)
        assertEquals(IncomingParser.MAX_BODY, result.body.length)
        assertEquals(IncomingParser.MAX_SUBJECT, result.subject.length)
    }

    @Test
    fun `control characters in the body are removed but tabs and new lines stay`() {
        val result = IncomingParser.parse(
            IncomingIntent(IncomingParser.ACTION_SEND, text = "a\u0000b\tc\rd\u0007e")
        )!!

        assertEquals("ab\tc\nde", result.body)
    }

    @Test
    fun `nothing shows the content in toString`() {
        val result = mailto("mailto:ana@example.test?subject=Secret")!!

        assertTrue("ana" !in result.toString() && "Secret" !in result.toString())
        assertTrue("ana" !in IncomingIntent("x", data = "mailto:ana@example.test").toString())
    }
}
