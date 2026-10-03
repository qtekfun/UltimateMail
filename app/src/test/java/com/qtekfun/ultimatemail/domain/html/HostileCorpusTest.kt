// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

private val sanitizer = HtmlSanitizer()

internal fun clean(html: String, allowRemote: Boolean = false): SanitizedHtml =
    sanitizer.sanitize(html, allowRemote)

private val activeTag = Regex(
    "<(script|iframe|frame|frameset|object|embed|applet|svg|math|form|input|button|meta|link|" +
        "base|template|video|audio|canvas|select|textarea)\\b",
    RegexOption.IGNORE_CASE
)
private val eventHandler = Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE)

/** What no sanitized message may ever contain, whatever the input was. */
private fun assertInert(output: String) {
    assertFalse(activeTag.containsMatchIn(output), "active tag in: $output")
    assertFalse(eventHandler.containsMatchIn(output), "event handler in: $output")
    val lower = output.lowercase()
    listOf("javascript:", "vbscript:", "expression(", "@import", "-moz-binding").forEach {
        assertFalse(it in lower, "$it in: $output")
    }
}

class HostileCorpusTest {
    // --- Script injection -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<script>alert(1)</script>",
            "<SCRIPT>alert(1)</SCRIPT>",
            "<ScRiPt\n>alert(1)</sCrIpT\t>",
            "<script src=//evil.example/x.js></script>",
            "<script/x>alert(1)</script/x>",
            "<script>alert(1)",
            "<script><!--<script>alert(1)</script>alert(2)--></script>",
            "<scr<script>ipt>alert(1)</scr</script>ipt>",
            "<<script>script>alert(1)<</script>/script>",
            "<!--<script>alert(1)</script>-->",
            "<noscript><script>alert(1)</script></noscript>",
            "<img src=x onerror=alert(1)>",
            "<img/src=x/onerror=alert(1)>",
            "<img src=\"a\"onerror=\"alert(1)\">",
            "<body onload=alert(1)>",
            "<div onmouseover=\"alert(1)\" onclick=alert(2)>x</div>",
            "<a href=\"#\" onclick=\"alert(1)\">x</a>",
            "<p onclick\n=\n'alert(1)'>x</p>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
            "<object data=\"https://evil.example/x.swf\"></object>",
            "<embed src=\"https://evil.example/x.swf\">",
            "<applet code=\"X.class\"></applet>",
            "<video src=x onerror=alert(1)></video>",
            "<template><script>alert(1)</script></template>",
            "<math><mtext><script>alert(1)</script></mtext></math>",
            "<xmp><script>alert(1)</script></xmp>",
            "<textarea><script>alert(1)</script></textarea>",
            "<title><script>alert(1)</script></title>"
        ]
    )
    fun `script injection variants leave nothing active`(payload: String) {
        val result = clean("<p>before</p>$payload<p>after</p>")
        assertInert(result.html)
        assertTrue(result.html.startsWith("<p>before</p>"), result.html)
    }

    @Test
    fun `an unterminated script swallows the rest of the message`() {
        assertEquals("<p>a</p>", clean("<p>a</p><script>alert(1)<p>b</p>").html)
    }

    @Test
    fun `text around a removed script survives`() {
        assertEquals("<p>a</p><p>b</p>", clean("<p>a</p><script>x</script><p>b</p>").html)
    }

    @Test
    fun `split script tags cannot be reassembled`() {
        val html = clean("<scr<script>ipt>alert(1)</scr</script>ipt>").html
        assertFalse("<script" in html.lowercase(), html)
    }

    @Test
    fun `stray angle brackets become text`() {
        assertEquals("a &lt; b &gt; c", clean("a < b > c").html)
        assertEquals("&lt;3 &lt;", clean("<3 <").html)
    }

    // --- Obfuscated javascript: and other URL schemes -----------------------------------------

    @ParameterizedTest
    @ValueSource(
        strings = [
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "  javascript:alert(1)",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "java\r\nscript:alert(1)",
            "java&#x09;script:alert(1)",
            "java&#9;script:alert(1)",
            "&#106;avascript:alert(1)",
            "&#x6A;&#x61;&#x76;&#x61;&#x73;&#x63;&#x72;&#x69;&#x70;&#x74;:alert(1)",
            "&#106&#97&#118&#97&#115&#99&#114&#105&#112&#116:alert(1)",
            "javascript&colon;alert(1)",
            "javascript&#58;alert(1)",
            "javascript&#x3A;alert(1)",
            "jav&#x61;script&#58;alert(1)",
            "\u0001javascript:alert(1)",
            "java\u200bscript:alert(1)",
            "vbscript:msgbox(1)",
            "livescript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "data:text/html,<script>alert(1)</script>",
            "data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+",
            "data:image/png;base64,AAAA",
            "file:///etc/passwd",
            "content://com.example/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "blob:https://evil.example/uuid",
            "ftp://evil.example/x",
            "//evil.example/x",
            "/relative/path",
            "relative.html",
            "",
            "   "
        ]
    )
    fun `dangerous or unusable link targets are dropped`(target: String) {
        val result = clean("<a href=\"$target\">link</a>")
        assertInert(result.html)
        assertFalse("href" in result.html, result.html)
        assertTrue(result.links.isEmpty())
        assertTrue("link" in result.html)
    }

    @Test
    fun `the unquoted and single quoted forms are judged the same`() {
        assertFalse("href" in clean("<a href=javascript:alert(1)>x</a>").html)
        assertFalse("href" in clean("<a href='javascript:alert(1)'>x</a>").html)
        assertFalse("href" in clean("<a HREF = \" javascript:alert(1)\">x</a>").html)
    }

    @Test
    fun `a decoded url is written back decoded and escaped, never as the original text`() {
        val html = clean("<a href=\"https://example.com/?a=1&amp;b=%22&quot;&lt;\">x</a>").html
        assertTrue("href=\"https://example.com/?a=1&amp;b=%22&quot;&lt;\"" in html, html)
    }

    @Test
    fun `an entity that is not a reference stays literal text`() {
        val html = clean("<a href=\"https://example.com/?a=1&b=2&notanentity;\">x</a>").html
        assertTrue("a=1&amp;b=2&amp;notanentity;" in html, html)
    }

    @Test
    fun `image sources only accept safe schemes`() {
        val safe = "data:image/png;base64,iVBORw0KGgo="
        assertTrue("src=\"$safe\"" in clean("<img src=\"$safe\">").html)
        assertTrue("src=\"cid:logo@mail\"" in clean("<img src=\"cid:logo@mail\">").html)
        assertTrue("src=\"CID:logo@mail\"" in clean("<img src=\"CID:logo@mail\">").html)
        listOf(
            "data:image/svg+xml;base64,PHN2Zz4=",
            "data:text/html;base64,PGI+",
            "data:image/png,%89PNG",
            "data:image/png;base64,AA\"onerror=alert(1)",
            "javascript:alert(1)",
            "file:///sdcard/x.png",
            "x.png"
        ).forEach {
            val html = clean("<img src='$it'>").html
            assertEquals("<img>", html, it)
        }
    }

    // --- SVG and other foreign content --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<svg onload=alert(1)></svg>",
            "<svg><script>alert(1)</script></svg>",
            "<svg/onload=alert(1)>",
            "<svg><a xlink:href=\"javascript:alert(1)\"><text>x</text></a></svg>",
            "<svg><foreignObject><iframe src=\"https://evil.example\"></iframe></foreignObject></svg>",
            "<svg><svg><script>alert(1)</script></svg><script>alert(2)</script></svg>",
            "<svg><image href=\"https://evil.example/p.png\"></image></svg>",
            "<svg><use href=\"data:image/svg+xml;base64,AAAA#x\"></use></svg>",
            "<math><maction actiontype=\"statusline#\" xlink:href=\"javascript:alert(1)\">x</maction></math>"
        ]
    )
    fun `svg and mathml are removed with everything inside`(payload: String) {
        val result = clean("<p>a</p>$payload<p>b</p>")
        assertInert(result.html)
        assertFalse("evil.example" in result.html)
        assertFalse(result.hadBlockedRemoteContent)
        assertTrue(result.html.startsWith("<p>a</p>"), result.html)
    }

    // --- CSS ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
        strings = [
            "width:expression(alert(1))",
            "width:EXPRESSION(alert(1))",
            "width:expr/**/ession(alert(1))",
            "width:\\65xpression(alert(1))",
            "width:e\\0078pression(alert(1))",
            "background:url(javascript:alert(1))",
            "background:url('javascript:alert(1)')",
            "background:url( \"java\\73 cript:alert(1)\" )",
            "background:u\\72l(javascript:alert(1))",
            "background:\\75rl(https://evil.example/x.png)",
            "background:url(https://evil.example/x.png)",
            "background:URL(//evil.example/x.png)",
            "background:url(http://evil.example/x.png)",
            "background:url(data:text/html;base64,AAAA)",
            "background:url(data:image/svg+xml;base64,AAAA)",
            "background:url(x.png)",
            "background-image:image-set('https://evil.example/x.png' 1x)",
            "behavior:url(evil.htc)",
            "-moz-binding:url(https://evil.example/x.xml#x)",
            "-ms-behavior:url(evil.htc)",
            "background:url(https://evil.example/x.png",
            "content:url(https://evil.example/x.png)"
        ]
    )
    fun `dangerous style declarations are dropped`(declaration: String) {
        val result = clean("<p style=\"color:red;$declaration\">x</p>")
        assertInert(result.html)
        assertEquals("<p style=\"color:red\">x</p>", result.html)
    }

    @Test
    fun `a style attribute with only dangerous content disappears`() {
        assertEquals("<p>x</p>", clean("<p style=\"width:expression(alert(1))\">x</p>").html)
    }

    @Test
    fun `remote css urls are counted as blocked remote content`() {
        val result = clean("<p style=\"background:url(https://t.example/p.png)\">x</p>")
        assertEquals(1, result.blockedRemoteCount)
        assertTrue(result.hadBlockedRemoteContent)
        assertFalse(
            clean("<p style=\"background:url(javascript:alert(1))\">x</p>").hadBlockedRemoteContent
        )
    }

    @Test
    fun `css urls to cid and safe data images are kept`() {
        val html = clean(
            "<td style=\"background:url(cid:bg@x)\"></td>" +
                "<td style=\"background:url('data:image/png;base64,AAAA')\"></td>"
        ).html
        assertTrue("url(cid:bg@x)" in html, html)
        assertTrue("url('data:image/png;base64,AAAA')" in html, html)
    }

    @Test
    fun `allowing remote content keeps only https css urls`() {
        val allowed = clean("<p style=\"background:url(https://t.example/p.png)\">x</p>", true)
        assertTrue("url(https://t.example/p.png)" in allowed.html)
        assertFalse(allowed.hadBlockedRemoteContent)
        val plain = clean("<p style=\"background:url(http://t.example/p.png)\">x</p>", true)
        assertFalse("url(" in plain.html)
        assertEquals(1, plain.blockedRemoteCount)
    }

    @Test
    fun `fixed and absolute positioning cannot cover the message`() {
        val html = clean(
            "<div style=\"position:fixed;top:0\">a</div>" +
                "<div style=\"position:absolute\">b</div>" +
                "<div style=\"position:relative\">c</div>"
        ).html
        assertEquals(
            "<div style=\"top:0\">a</div><div>b</div><div style=\"position:relative\">c</div>",
            html
        )
    }

    @Test
    fun `style blocks keep ordinary rules and drop imports, fonts and scripts`() {
        val html = clean(
            "<style>@import url(https://evil.example/x.css); @charset \"utf-8\";" +
                "@font-face{font-family:x;src:url(https://evil.example/f.woff)}" +
                "/* c */ .a, td > b{color:#333;background:url(https://t.example/p.png)}" +
                "@media (max-width:600px){.b{width:100% !important}.c{width:expression(1)}}" +
                "@keyframes x{from{top:0}}" +
                "p{behavior:url(x.htc)}</style><p>x</p>"
        )
        assertEquals(
            "<style>.a, td > b{color:#333}@media (max-width:600px){.b{width:100% !important}}" +
                "</style><p>x</p>",
            html.html
        )
        assertEquals(1, html.blockedRemoteCount)
    }

    @Test
    fun `a style block cannot close itself to inject markup`() {
        val result = clean("<style>a{color:red}</style><script>alert(1)</script><p>x</p>")
        assertEquals("<style>a{color:red}</style><p>x</p>", result.html)
        val trick =
            clean("<style>a<b{color:red} @media <x>{p{color:red}} .k{content:'</s'}</style>")
        assertFalse("<b" in trick.html || "<x" in trick.html, trick.html)
        val escaped = clean("<style>.a{color:red\\3c /style><script>alert(1)}</style>")
        assertInert(escaped.html)
        assertFalse("<script" in escaped.html.lowercase())
    }

    @Test
    fun `css escapes are resolved before judging`() {
        // \2f* would become a comment opener if it survived as an escape.
        val html = clean(
            "<style>a{color:red;b:\\2f* ;background:url(https://t.example/x.png) /*}</style>"
        ).html
        assertFalse("/*" in html || "\\" in html, html)
        assertFalse("url(" in html, html)
    }

    @Test
    fun `css escapes decode numbers, replace invalid ones and drop line continuations`() {
        val html = clean(
            "<p style=\"font-family:x\\0 y\\110000 z\\d800 w\\41 b\\\nc\\\">t</p>"
        ).html
        assertEquals("<p style=\"font-family:x\uFFFDy\uFFFDz\uFFFDwAbc\">t</p>", html)
    }

    @Test
    fun `remote content is blocked unless asked otherwise`() {
        val html = "<img src=\"https://t.example/p.gif\">"
        assertTrue(HtmlSanitizer().sanitize(html).hadBlockedRemoteContent)
        assertFalse(
            HtmlSanitizer().sanitize(html, allowRemoteContent = true).hadBlockedRemoteContent
        )
    }

    @Test
    fun `broken css never throws`() {
        listOf(
            "}}}{{{",
            "a{",
            "a{b:c",
            "{color:red}",
            "@media{",
            "@media screen{a{b:c}",
            ";;;",
            "a{b:c;;d}"
        )
            .forEach { clean("<style>$it</style><p style=\"$it\">x</p>") }
    }

    // --- Redirects, base, forms ---------------------------------------------------------------

    @Test
    fun `meta refresh, link and base are removed`() {
        val result = clean(
            "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">" +
                "<meta charset=utf-8><link rel=stylesheet href=\"https://evil.example/x.css\">" +
                "<base href=\"https://evil.example/\"></head><body><p>x</p></body></html>"
        )
        assertEquals("<p>x</p>", result.html)
        assertFalse(result.hadBlockedRemoteContent)
    }

    @Test
    fun `a base href cannot turn relative links into attacker links`() {
        val html = clean(
            "<base href=\"https://evil.example/\"><a href=\"/login\">x</a><img src=\"p.gif\">"
        ).html
        assertFalse("evil.example" in html)
        assertFalse("href" in html)
        assertEquals("<a>x</a><img>", html)
    }

    @Test
    fun `forms and their controls are removed`() {
        val html = clean(
            "<form action=\"https://evil.example\" method=post>Password " +
                "<input name=pw type=password>" +
                "<button formaction=\"https://evil.example\">Go</button>" +
                "<select><option>a</option></select><textarea>t</textarea></form>"
        ).html
        assertInert(html)
        assertEquals("Password Go", html)
    }

    // --- Malformed and nested markup ----------------------------------------------------------

    @Test
    fun `unclosed tags are closed in order`() {
        assertEquals(
            "<div><p>a <b>b <i>c</i></b></p></div>",
            clean("<div><p>a <b>b <i>c</div>").html
        )
    }

    @Test
    fun `stray end tags and unknown tags are ignored`() {
        assertEquals("<p>a</p>b", clean("</div></span><p>a</p><blink>b</blink></x-y>").html)
    }

    @Test
    fun `a tag cut off by the end of the input is dropped`() {
        assertEquals("<p>a</p>", clean("<p>a</p><a href=\"https://x.test>click").html)
        assertEquals("<p>a</p>", clean("<p>a</p><img src=x").html)
        assertEquals("<p>a</p>", clean("<p>a</p><div class=").html)
    }

    @Test
    fun `an anchor inside an anchor closes the first`() {
        val result = clean("<a href=\"https://a.test\">one<a href=\"https://b.test\">two</a>")
        assertEquals(listOf("https://a.test", "https://b.test"), result.links.map { it.href })
        assertEquals(listOf("one", "two"), result.links.map { it.text })
    }

    @Test
    fun `nesting depth is bounded`() {
        val html = clean("<div>".repeat(100_000) + "x").html
        assertEquals(512, "<div>".toRegex().findAll(html).count())
        assertEquals(512, "</div>".toRegex().findAll(html).count())
    }

    @Test
    fun `huge attribute values are dropped, not copied`() {
        val huge = "a".repeat(2_000_000)
        val result = clean(
            "<a href=\"https://example.com/$huge\" title=\"$huge\" class=ok>x</a>" +
                "<img src=\"https://example.com/$huge.gif\"><p style=\"color:$huge\">y</p>"
        )
        assertEquals("<a class=\"ok\">x</a><img><p>y</p>", result.html)
    }

    @Test
    fun `an oversized data image is dropped and a reasonable one kept`() {
        val big = "data:image/png;base64," + "A".repeat(2_100_000)
        assertEquals("<img>", clean("<img src=\"$big\">").html)
        val ok = "data:image/png;base64," + "A".repeat(100_000)
        assertTrue(ok in clean("<img src=\"$ok\">").html)
    }

    @Test
    fun `huge numeric character references do not blow up`() {
        val html = clean("<a href=\"&#" + "9".repeat(100_000) + ";javascript:alert(1)\">x</a>").html
        assertInert(html)
    }

    @Test
    fun `many comments, tags and brackets are read in one pass`() {
        val soup = "<!--x".repeat(50_000) + "<<<<<" + "<a ".repeat(50_000)
        assertEquals("", clean("<!-- " + soup).html)
        assertTrue(clean("<!--a-->".repeat(100_000) + "z").html == "z")
        assertTrue(clean("<".repeat(100_000)).html.length == 4 * 100_000)
    }

    @Test
    fun `random tag soup always produces inert output`() {
        val pieces = listOf(
            "<", ">", "/", "=", "\"", "'", " ", "\n", "<script", "</script", "<img", " src=",
            " onerror=",
            " href=", "javascript:", "alert(1)", "<a", "</a>", "<svg", "</svg>", "<style>",
            "</style>",
            "<!--", "-->", "&#106;", "&colon;", "url(", ")", "{", "}", ";", "<p", "x", "&", "\\",
            " style=", "expression(", "<iframe", "<object", "<!", "<?", "[", "]"
        )
        val tag = Regex("<[a-z][^>]*>")
        val scriptUrl = Regex("(?i)(javascript|vbscript|expression)")
        val random = java.util.Random(42)
        repeat(300) {
            val input = (0 until 60).joinToString("") { pieces[random.nextInt(pieces.size)] }
            val output = clean(input).html
            assertFalse(activeTag.containsMatchIn(output), "input: $input output: $output")
            // Text may spell anything; inside a real tag no handler or script URL may appear.
            tag.findAll(output).map { it.value }.forEach {
                assertFalse(eventHandler.containsMatchIn(it), "input: $input output: $output")
                assertFalse(scriptUrl.containsMatchIn(it), "input: $input output: $output")
            }
        }
    }
}
