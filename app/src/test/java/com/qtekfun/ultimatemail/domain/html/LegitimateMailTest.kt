// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegitimateMailTest {
    private val newsletter = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml" lang="es">
        <head>
          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
          <title>Boletín de octubre</title>
          <style type="text/css">
            body { margin: 0; padding: 0; background-color: #f4f4f4; }
            .btn { background-color: #0a66c2; color: #ffffff; padding: 12px 24px; }
            @media only screen and (max-width: 600px) { .container { width: 100% !important; } }
          </style>
          <!--[if mso]><style>td { font-family: Arial; }</style><![endif]-->
        </head>
        <body bgcolor="#f4f4f4" style="margin:0">
          <table class="container" width="600" cellpadding="0" cellspacing="0" border="0" align="center" bgcolor="#ffffff">
            <tr><td align="center" style="padding:20px;font-family:Arial,sans-serif;font-size:14px;color:#333333">
              <img src="https://cdn.news.example/logo.png" alt="Logo" width="120" height="40" style="display:block">
              <h1 style="margin:0">Año nuevo &mdash; ¡Hola &amp; bienvenidos! 日本語 🙂</h1>
              <p>Lee el <a href="https://news.example/post?id=1&amp;utm=mail" style="color:#0a66c2">artículo completo</a>
                o escribe a <a href="mailto:hola@news.example?subject=Hola">hola@news.example</a>
                o llama al <a href="tel:+34600000000">600 000 000</a>.</p>
              <a class="btn" href="https://news.example/subscribe">Suscribirse</a>
              <font face="Arial" size="2" color="#999999">Darse de baja</font>
            </td></tr>
          </table>
          <img src="https://track.news.example/open.gif?u=42" width="1" height="1" alt="">
        </body></html>
    """.trimIndent()

    @Test
    fun `a newsletter keeps its structure, text and styling`() {
        val result = clean(newsletter)
        val html = result.html
        listOf(
            "<table class=\"container\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" " +
                "border=\"0\" align=\"center\" bgcolor=\"#ffffff\">",
            "<td align=\"center\" style=\"padding:20px;font-family:Arial,sans-serif;" +
                "font-size:14px;color:#333333\">",
            "<h1 style=\"margin:0\">Año nuevo &mdash; ¡Hola &amp; bienvenidos! 日本語 \uD83D\uDE42</h1>",
            "<font face=\"Arial\" size=\"2\" color=\"#999999\">Darse de baja</font>",
            "<a class=\"btn\" href=\"https://news.example/subscribe\" " +
                "rel=\"noopener noreferrer nofollow\">" +
                "Suscribirse</a>",
            "href=\"https://news.example/post?id=1&amp;utm=mail\"",
            "href=\"mailto:hola@news.example?subject=Hola\"",
            "href=\"tel:+34600000000\"",
            ".btn{background-color:#0a66c2;color:#ffffff;padding:12px 24px}",
            "@media only screen and (max-width: 600px){.container{width:100% !important}}"
        ).forEach { assertTrue(it in html, "missing: $it\nin: $html") }
        assertFalse("Boletín de octubre" in html, "the document title is not content")
        assertFalse("mso" in html)
        assertFalse("<meta" in html || "<html" in html || "<head" in html)
    }

    @Test
    fun `a newsletter reports its two remote images as blocked and keeps them parked`() {
        val result = clean(newsletter)
        assertEquals(2, result.blockedRemoteCount)
        assertTrue(result.hadBlockedRemoteContent)
        assertTrue("data-blocked-src=\"https://cdn.news.example/logo.png\"" in result.html)
        assertTrue("data-blocked-src=\"https://track.news.example/open.gif?u=42\"" in result.html)
        assertFalse(Regex("\\ssrc=\"https?:").containsMatchIn(result.html))
    }

    @Test
    fun `allowing remote content makes the images loadable and the report empty`() {
        val result = clean(newsletter, allowRemote = true)
        assertEquals(0, result.blockedRemoteCount)
        assertFalse(result.hadBlockedRemoteContent)
        assertTrue("src=\"https://cdn.news.example/logo.png\"" in result.html)
        assertFalse("data-blocked-src" in result.html)
    }

    @Test
    fun `a message without remote content reports none`() {
        val result = clean("<p>Hi <img src=\"cid:a@b\"> <b>there</b></p>")
        assertEquals("<p>Hi <img src=\"cid:a@b\"> <b>there</b></p>", result.html)
        assertEquals(0, result.blockedRemoteCount)
        assertFalse(result.hadBlockedRemoteContent)
    }

    @Test
    fun `a tracking pixel is blocked and reported`() {
        val result =
            clean("<p>x</p><img src=\"https://t.example/p.gif?id=7\" width=\"1\" height=\"1\">")
        assertEquals(
            "<p>x</p><img data-blocked-src=\"https://t.example/p.gif?id=7\" width=\"1\" height=\"1\">",
            result.html
        )
        assertEquals(1, result.blockedRemoteCount)
    }

    @Test
    fun `http images stay blocked even when remote content is allowed`() {
        val html = "<img src=\"http://t.example/a.gif\"><img src=\"//t.example/b.gif\">"
        val blocked = clean(html)
        assertEquals(2, blocked.blockedRemoteCount)
        assertTrue("data-blocked-src=\"https://t.example/b.gif\"" in blocked.html)
        val allowed = clean(html, allowRemote = true)
        assertEquals(1, allowed.blockedRemoteCount)
        assertTrue("data-blocked-src=\"http://t.example/a.gif\"" in allowed.html)
        assertTrue("src=\"https://t.example/b.gif\"" in allowed.html)
    }

    @Test
    fun `remote table backgrounds are dropped and counted`() {
        val result =
            clean(
                "<table background=\"https://t.example/bg.png\"><tr><td background=\"cid:bg\">x</td></tr></table>"
            )
        assertEquals("<table><tr><td background=\"cid:bg\">x</td></tr></table>", result.html)
        assertEquals(1, result.blockedRemoteCount)
        val allowed =
            clean("<table background=\"https://t.example/bg.png\"></table>", allowRemote = true)
        assertTrue("background=\"https://t.example/bg.png\"" in allowed.html)
        assertEquals(0, allowed.blockedRemoteCount)
    }

    @Test
    fun `a body background is not carried over`() {
        assertEquals(
            "x",
            clean("<body background=\"https://t.example/bg.png\" bgcolor=red>x</body>").html
        )
    }

    @Test
    fun `fragment links stay but are not reported as links`() {
        val result = clean("<a href=\"#top\">up</a>")
        assertEquals("<a href=\"#top\" rel=\"noopener noreferrer nofollow\">up</a>", result.html)
        assertTrue(result.links.isEmpty())
    }

    @Test
    fun `target and id attributes are not carried over`() {
        assertEquals(
            "<a href=\"https://a.test\" rel=\"noopener noreferrer nofollow\">x</a>",
            clean("<a id=x target=_blank href=\"https://a.test\" name=n>x</a>").html
        )
    }

    @Test
    fun `duplicate attributes use the first value like browsers do`() {
        assertEquals("<p class=\"a\">x</p>", clean("<p class=a class=b>x</p>").html)
    }

    @Test
    fun `quotes and markup characters in attributes are escaped`() {
        assertEquals(
            "<p title=\"a&quot;b &lt;i&gt; &amp;\">x</p>",
            clean("<p title='a\"b <i> &amp;'>x</p>").html
        )
    }

    @Test
    fun `plaintext swallows the rest and noscript content is shown`() {
        assertEquals("a", clean("a<plaintext><b>x").html)
        assertEquals("<p>shown</p>", clean("<noscript><p>shown</p></noscript>").html)
    }

    @Test
    fun `empty and text-only input`() {
        assertEquals("", clean("").html)
        assertEquals("just text &amp; more", clean("just text &amp; more").html)
    }

    // --- Link model ---------------------------------------------------------------------------

    @Test
    fun `links expose their real target and visible text`() {
        val result = clean(
            "<p><a href=\"https://shop.example/a\"> Buy\n  <b>now</b> </a> " +
                "<a href=\"mailto:a@b.example\">mail</a> <a href=\"tel:123\">call</a></p>"
        )
        assertEquals(
            listOf(
                HtmlLink("https://shop.example/a", "Buy now", false),
                HtmlLink("mailto:a@b.example", "mail", false),
                HtmlLink("tel:123", "call", false)
            ),
            result.links
        )
    }

    @Test
    fun `entities in link text are decoded for the visible text`() {
        val result = clean("<a href=\"https://a.example\">Tom &amp; Jerry</a>")
        assertEquals("Tom & Jerry", result.links.single().text)
    }

    @Test
    fun `link text that names another site is flagged`() {
        val flagged = listOf(
            "https://evil.example/login" to "https://bank.example.com",
            "https://evil.example/login" to "bank.example.com",
            "https://evil.example/login" to "www.bank.example.com/account",
            "https://paypal.com.evil.net/x" to "paypal.com",
            "https://bank.example/x" to "Sign in at https://bank.example.com.evil.net now",
            "https://evil.example/" to "https://bank.example@evil.example/",
            "https://evil.example:8443/" to "(https://bank.example.com),"
        )
        flagged.forEach { (href, text) ->
            val link = clean("<a href=\"$href\">$text</a>").links.single()
            assertTrue(link.deceptive, "$text -> $href")
        }
    }

    @Test
    fun `link text that matches its target or says nothing about a site is not flagged`() {
        val fine = listOf(
            "https://bank.example.com/login" to "https://bank.example.com",
            "https://www.bank.example.com/login" to "bank.example.com",
            "https://BANK.example.com/login" to "www.bank.example.com/account",
            "https://click.bank.example.com/r/abc" to "bank.example.com",
            "https://bank.example.com/x" to "click.bank.example.com",
            "https://bank.example.com:8443/x" to "https://bank.example.com:8443/x",
            "https://bank.example.com/f.pdf" to "invoice.pdf",
            "https://bank.example.com/x" to "Click here, e.g. now.",
            "https://bank.example.com/x" to "Version 2.0",
            "https://bank.example.com/x" to ""
        )
        fine.forEach { (href, text) ->
            val link = clean("<a href=\"$href\">$text</a>").links.single()
            assertFalse(link.deceptive, "$text -> $href")
        }
    }

    @Test
    fun `mail and phone links are never flagged`() {
        val result = clean("<a href=\"mailto:a@b.example\">https://other.example</a>")
        assertFalse(result.links.single().deceptive)
    }

    @Test
    fun `a very long link text is truncated for the model`() {
        val result = clean("<a href=\"https://a.example\">${"word ".repeat(10_000)}</a>")
        assertTrue(result.links.single().text.length < 1_100)
    }

    @Test
    fun `the ui can ask whether a tapped target is a deceptive one`() {
        val result = clean(
            "<a href=\"https://evil.example\">https://bank.example.com</a>" +
                "<a href=\"https://fine.example/x\">fine.example</a>"
        )
        assertTrue(result.isDeceptiveTarget("https://evil.example"))
        assertTrue(result.isDeceptiveTarget("https://evil.example/"))
        assertFalse(result.isDeceptiveTarget("https://fine.example/x"))
        assertFalse(result.isDeceptiveTarget("https://other.example"))
    }
}
