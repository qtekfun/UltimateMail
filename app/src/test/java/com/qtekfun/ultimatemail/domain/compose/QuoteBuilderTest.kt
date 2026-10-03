// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.conversation.AttributionLine
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.signature.ComposeKind
import com.qtekfun.ultimatemail.domain.signature.SignatureEditor
import com.qtekfun.ultimatemail.domain.signature.SignatureSettings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuoteBuilderTest {
    private val english = QuoteBuilder { ENGLISH_QUOTES }
    private val spanish = QuoteBuilder { SPANISH_QUOTES }

    @Test
    fun `an English reply has an attribution line and the text quoted`() {
        val lines = english.reply(source()).lines()

        assertTrue(Regex("On .*2024.*, Bob <bob@example.test> wrote:").matches(lines[0]), lines[0])
        assertEquals(listOf("> Hi Ana,", "> see you."), lines.drop(1))
    }

    @Test
    fun `a Spanish reply says El and escribio`() {
        val first = spanish.reply(source()).lines().first()

        assertTrue(Regex("El .*2024.*, Bob <bob@example.test> escribió:").matches(first), first)
    }

    @Test
    fun `the attribution is one the conversation view recognises as a quote header`() {
        assertTrue(AttributionLine.matches(english.reply(source()).lines().first()))
        assertTrue(AttributionLine.matches(spanish.reply(source()).lines().first()))
    }

    @Test
    fun `a sender without a name shows the address alone and one without anything shows nothing`() {
        val bare = english.reply(source(from = MailAddress("bob@example.test"))).lines().first()
        val unknown = english.reply(source(from = null)).lines().first()

        assertTrue(bare.endsWith(", bob@example.test wrote:"), bare)
        assertTrue(unknown.endsWith(",  wrote:"), unknown)
    }

    @Test
    fun `quoting prefixes every line, keeps blank lines quoted and normalises line ends`() {
        assertEquals("> a\n>\n> b\n> > deeper", QuoteBuilder.quote("a\r\n\r\nb\r> deeper\n\n"))
    }

    @Test
    fun `nothing to quote gives only the attribution`() {
        assertEquals("", QuoteBuilder.quote("  \n "))
        assertEquals(1, english.reply(source(body = null)).lines().size)
    }

    @Test
    fun `a forward has the marker, the header lines and the original text as it is`() {
        val forwarded = english.forward(
            source(
                to = listOf(MailAddress("ana@example.test"), MailAddress("cy@example.test", "Cy")),
                cc = listOf(MailAddress("di@example.test")),
                body = "Hi Ana,\r\nsee you.\n"
            )
        ).lines()

        assertEquals("---------- Forwarded message ---------", forwarded[0])
        assertEquals("From: Bob <bob@example.test>", forwarded[1])
        assertTrue(forwarded[2].startsWith("Date: ") && "2024" in forwarded[2])
        assertEquals("Subject: Hello", forwarded[3])
        assertEquals("To: ana@example.test, Cy <cy@example.test>", forwarded[4])
        assertEquals("Cc: di@example.test", forwarded[5])
        assertEquals(listOf("", "Hi Ana,", "see you.", ""), forwarded.drop(6))
    }

    @Test
    fun `a forward leaves out the recipient lines that are empty`() {
        val forwarded = english.forward(source(to = emptyList())).lines()

        assertEquals(listOf("---------- Forwarded message ---------"), forwarded.take(1))
        assertTrue(forwarded.none { it.startsWith("To:") || it.startsWith("Cc:") })
    }

    @Test
    fun `a Spanish forward uses the Spanish labels`() {
        val forwarded = spanish.forward(source()).lines()

        assertEquals("---------- Mensaje reenviado ---------", forwarded[0])
        assertTrue(forwarded[1].startsWith("De: "))
        assertTrue(forwarded[3].startsWith("Asunto: "))
        assertTrue(forwarded[4].startsWith("Para: "))
    }

    @Test
    fun `the signature goes above a reply quote and above the forwarded block`() {
        val settings = SignatureSettings("Ana")
        val reply = "\n\n" + english.reply(source())
        val forward = "\n\n" + english.forward(source())

        val signedReply = SignatureEditor.apply(reply, ComposeKind.REPLY, settings)
        val signedForward = SignatureEditor.apply(forward, ComposeKind.FORWARD, settings)

        assertTrue(signedReply.startsWith("\n\n-- \nAna\n\nOn "), signedReply)
        assertTrue(signedForward.startsWith("\n\n-- \nAna\n\n---------- Forwarded"), signedForward)
    }

    @Test
    fun `the strings of the app have the same words and placeholders in both languages`() {
        fun strings(folder: String): Map<String, String> {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            return Regex("<string name=\"(compose_[a-z_]+)\">(.*?)</string>").findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }
                .filterKeys {
                    it == "compose_attribution" || it.startsWith("compose_header_") ||
                        it == "compose_forward_marker"
                }
        }
        val en = strings("values")
        val es = strings("values-es")

        assertEquals(en.keys, es.keys)
        assertEquals(7, en.size)
        assertEquals(ENGLISH_QUOTES.attribution, en.getValue("compose_attribution"))
        assertEquals(SPANISH_QUOTES.attribution, es.getValue("compose_attribution"))
        assertEquals(ENGLISH_QUOTES.forwardMarker, en.getValue("compose_forward_marker"))
        assertEquals(SPANISH_QUOTES.forwardMarker, es.getValue("compose_forward_marker"))
        assertEquals(ENGLISH_QUOTES.subjectLabel, en.getValue("compose_header_subject"))
        assertEquals(SPANISH_QUOTES.subjectLabel, es.getValue("compose_header_subject"))
        assertEquals(SPANISH_QUOTES.toLabel, es.getValue("compose_header_to"))
        assertEquals(SPANISH_QUOTES.fromLabel, es.getValue("compose_header_from"))
        assertEquals(SPANISH_QUOTES.dateLabel, es.getValue("compose_header_date"))
        assertEquals(ENGLISH_QUOTES.ccLabel, en.getValue("compose_header_cc"))
        // Both languages fill the same two placeholders, in the order the builder passes them.
        listOf(en, es).forEach {
            val attribution = it.getValue("compose_attribution")
            assertTrue(attribution.indexOf("%1\$s") in 0 until attribution.indexOf("%2\$s"))
        }
    }
}

class HtmlTextTest {
    @Test
    fun `tags vanish, blocks become lines and entities are decoded`() {
        val text = HtmlText.toPlain(
            "<html><head><title>x</title><style>p{}</style></head>" +
                "<body><p>Hello &amp; welcome</p>" +
                "<div>a&nbsp;b<br>c</div><script>alert(1)</script>" +
                "&lt;ok&gt; &quot;q&quot; &#39;s&#39;" +
                "<ul><li>one</li><li>two</li></ul></body></html>"
        )

        assertEquals("Hello & welcome\na b\nc\n<ok> \"q\" 's'one\ntwo", text)
    }

    @Test
    fun `an escaped entity is not decoded twice and blank runs collapse`() {
        assertEquals(
            "&lt; x\n\ny",
            HtmlText.toPlain("<p>&amp;lt; x</p><p></p><p></p><p></p><p>y</p>")
        )
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("just text", HtmlText.toPlain("  just text \r\n"))
    }
}
