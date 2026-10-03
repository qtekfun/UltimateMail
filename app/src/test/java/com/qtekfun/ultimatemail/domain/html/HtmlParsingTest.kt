// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun tokens(html: String): List<HtmlToken> {
    val tokenizer = HtmlTokenizer(html)
    return generateSequence { tokenizer.next() }.toList()
}

class HtmlEntitiesTest {
    @Test
    fun `numeric references decode with or without semicolon, in decimal and hex`() {
        assertEquals("A", HtmlEntities.decode("&#65;"))
        assertEquals("A", HtmlEntities.decode("&#65"))
        assertEquals("A", HtmlEntities.decode("&#x41;"))
        assertEquals("A", HtmlEntities.decode("&#X41"))
        assertEquals("AB", HtmlEntities.decode("&#65&#x42"))
        assertEquals("🙂", HtmlEntities.decode("&#x1F642;"))
        assertEquals("A1", HtmlEntities.decode("&#65;1"))
    }

    @Test
    fun `invalid numeric references become the replacement character`() {
        assertEquals("�", HtmlEntities.decode("&#0;"))
        assertEquals("�", HtmlEntities.decode("&#xD800;"))
        assertEquals("�", HtmlEntities.decode("&#x110000;"))
        assertEquals("�", HtmlEntities.decode("&#" + "7".repeat(200_000) + ";"))
        assertEquals("&#;&#x;&#xZ", HtmlEntities.decode("&#;&#x;&#xZ"))
    }

    @Test
    fun `named references decode when closed, or when legacy`() {
        assertEquals("a&b", HtmlEntities.decode("a&amp;b"))
        assertEquals("a& b", HtmlEntities.decode("a&amp b"))
        assertEquals("<>\"'", HtmlEntities.decode("&lt;&gt;&quot;&apos;"))
        assertEquals("javascript:", HtmlEntities.decode("javascript&colon;"))
        assertEquals("\t\n", HtmlEntities.decode("&Tab;&NewLine;"))
        assertEquals("()/", HtmlEntities.decode("&lpar;&rpar;&sol;"))
    }

    @Test
    fun `things that are not references stay as they are`() {
        assertEquals("a & b", HtmlEntities.decode("a & b"))
        assertEquals("&colon", HtmlEntities.decode("&colon"))
        assertEquals("&unknown;", HtmlEntities.decode("&unknown;"))
        assertEquals("&toolongtobeanentityname;", HtmlEntities.decode("&toolongtobeanentityname;"))
        assertEquals("fish & chips&", HtmlEntities.decode("fish & chips&"))
        assertEquals("plain", HtmlEntities.decode("plain"))
    }
}

class UrlPolicyTest {
    @Test
    fun `cleaning removes blanks, controls and invisible characters`() {
        assertEquals("javascript:x", UrlPolicy.clean(" \tjava\nscr\u0000ipt\u200b:x\u2028"))
    }

    @Test
    fun `kinds`() {
        val expected = mapOf(
            "https://a.test" to UrlKind.HTTPS,
            "HTTPS://a.test" to UrlKind.HTTPS,
            "http://a.test" to UrlKind.HTTP,
            "mailto:a@b.test" to UrlKind.MAILTO,
            "tel:+1" to UrlKind.TEL,
            "cid:x@y" to UrlKind.CID,
            "data:image/png;base64,AAAA" to UrlKind.DATA_IMAGE,
            "DATA:IMAGE/GIF;BASE64,AAAA" to UrlKind.DATA_IMAGE,
            "data:image/jpeg;base64," to UrlKind.DATA_IMAGE,
            "data:image/svg+xml;base64,AAAA" to UrlKind.OTHER,
            "data:image/png;base64,AA AA" to UrlKind.OTHER,
            "data:image/png;AAAA" to UrlKind.OTHER,
            "data:image/;base64,AAAA" to UrlKind.OTHER,
            "data:text/plain,x" to UrlKind.OTHER,
            "//a.test/x" to UrlKind.PROTOCOL_RELATIVE,
            "#frag" to UrlKind.FRAGMENT,
            "javascript:x" to UrlKind.OTHER,
            "1http://a.test" to UrlKind.OTHER,
            "ht tp://a.test" to UrlKind.OTHER,
            "a/b:c" to UrlKind.OTHER,
            ":x" to UrlKind.OTHER,
            "x" to UrlKind.OTHER,
            "" to UrlKind.OTHER
        )
        expected.forEach { (url, kind) -> assertEquals(kind, UrlPolicy.kind(url), url) }
    }

    @Test
    fun `remote kinds`() {
        val remote = UrlKind.entries.filter(UrlPolicy::isRemote)
        assertEquals(listOf(UrlKind.HTTPS, UrlKind.HTTP, UrlKind.PROTOCOL_RELATIVE), remote)
    }
}

class HtmlTokenizerTest {
    @Test
    fun `text and tags`() {
        assertEquals(
            listOf(
                HtmlToken.Text("a "),
                HtmlToken.Start("b", emptyMap()),
                HtmlToken.Text("c"),
                HtmlToken.End("b")
            ),
            tokens("a <B>c</B >")
        )
    }

    @Test
    fun `attribute syntax variants`() {
        val start = tokens(
            "<a href=x/y title='q \"1\"' CLASS=\"c\" disabled data-x= y href=other =odd/>"
        ).single() as HtmlToken.Start
        assertEquals(
            mapOf(
                "href" to "x/y",
                "title" to "q \"1\"",
                "class" to "c",
                "disabled" to "",
                "data-x" to "y",
                "=odd" to ""
            ),
            start.attributes
        )
    }

    @Test
    fun `comments, doctype, cdata and processing instructions produce nothing`() {
        listOf(
            "<!-- c -->", "<!---->", "<!-->", "<!--->", "<!-- a -- b -->", "<!-- x --!>",
            "<!DOCTYPE html>", "<![CDATA[x]]>", "<?xml version=\"1.0\"?>", "<!-- unterminated",
            "<!x", "<?x", "</>", "</ x>", "</", "</3>"
        ).forEach { assertEquals(emptyList<HtmlToken>(), tokens(it), it) }
        assertEquals(listOf(HtmlToken.Text("a"), HtmlToken.Text("b")), tokens("a<!-- c -->b"))
        assertEquals(listOf(HtmlToken.Text("z")), tokens("<!-- a -- b -->z"))
        assertEquals(listOf(HtmlToken.Text("z")), tokens("<!-- x --!>z"))
    }

    @Test
    fun `a lone angle bracket is text`() {
        assertEquals(
            listOf(HtmlToken.Text("a "), HtmlToken.Text("<"), HtmlToken.Text(" b")),
            tokens("a < b")
        )
        assertEquals(listOf(HtmlToken.Text("<")), tokens("<"))
    }

    @Test
    fun `a tag cut off by the end of the input produces nothing`() {
        listOf("<a", "<a href", "<a href=", "<a href=\"x", "<a href='x'", "<a b=c").forEach {
            assertEquals(emptyList<HtmlToken>(), tokens(it), it)
        }
    }

    @Test
    fun `raw text runs to the matching end tag, case-insensitively`() {
        val tokenizer = HtmlTokenizer("<script>a</b></scripts>c</SCRIPT >d")
        tokenizer.next()
        assertEquals("a</b></scripts>c", tokenizer.rawText("script"))
        assertEquals(HtmlToken.Text("d"), tokenizer.next())
        assertNull(tokenizer.next())
    }

    @Test
    fun `raw text without an end tag takes the rest`() {
        val tokenizer = HtmlTokenizer("<style>a{}")
        tokenizer.next()
        assertEquals("a{}", tokenizer.rawText("style"))
        assertNull(tokenizer.next())
        val unclosed = HtmlTokenizer("<style>a</style")
        unclosed.next()
        assertEquals("a", unclosed.rawText("style"))
        assertNull(unclosed.next())
    }

    @Test
    fun `skipping an element handles nesting and the end of input`() {
        val tokenizer = HtmlTokenizer("<svg><svg>x</svg>y</svg>after<svg>never closed")
        tokenizer.next()
        tokenizer.skipElement("svg")
        assertEquals(HtmlToken.Text("after"), tokenizer.next())
        tokenizer.next()
        tokenizer.skipElement("svg")
        assertNull(tokenizer.next())
    }

    @Test
    fun `skipping everything`() {
        val tokenizer = HtmlTokenizer("<a>b")
        tokenizer.next()
        tokenizer.skipAll()
        assertNull(tokenizer.next())
    }
}

class LinkHostTest {
    @Test
    fun `host of an address`() {
        assertEquals("a.test", LinkAnalysis.hostOf("https://A.test/x?y#z"))
        assertEquals("a.test", LinkAnalysis.hostOf("http://user:pw@a.test:8080/x"))
        assertEquals("a.test", LinkAnalysis.hostOf("https://a.test?x"))
        assertEquals("[::1]", LinkAnalysis.hostOf("http://[::1]:8080/x"))
        assertNull(LinkAnalysis.hostOf("mailto:a@b.test"))
        assertNull(LinkAnalysis.hostOf("ftp://a.test"))
        assertNull(LinkAnalysis.hostOf("a.test"))
        assertNull(LinkAnalysis.hostOf("https:///x"))
    }

    @Test
    fun `address detection in link text`() {
        val real = "https://bank.example/x"
        assertTrue(LinkAnalysis.looksLikeDifferentUrl("HTTP://WWW.OTHER.EXAMPLE/x", real))
        assertTrue(LinkAnalysis.looksLikeDifferentUrl("see WWW.OTHER.EXAMPLE", real))
        assertTrue(LinkAnalysis.looksLikeDifferentUrl("https://?x", real))
        assertFalse(LinkAnalysis.looksLikeDifferentUrl("https://bank.example#top", real))
        assertFalse(LinkAnalysis.looksLikeDifferentUrl("https://bank.example?x=1", real))
        assertFalse(LinkAnalysis.looksLikeDifferentUrl("x..com a_b.com bank.c0m 3.5", real))
        assertFalse(LinkAnalysis.looksLikeDifferentUrl("https://other.example", "mailto:a@b.test"))
    }
}
