// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlQuoteDetectorTest {
    @Test
    fun `html without a quote is left whole`() {
        val html = "<div>Hello <b>Ana</b></div>"

        assertEquals(QuoteSplit.whole(html), HtmlQuoteDetector.split(html))
    }

    @Test
    fun `a Gmail quote is folded together with its attribution`() {
        val html = "<div dir=\"ltr\">Sounds good.</div><br>" +
            "<div class=\"gmail_quote\"><div dir=\"ltr\" class=\"gmail_attr\">On Mon, Jan 1, " +
            "2024 at 10:00 AM Ana &lt;ana@example.test&gt; wrote:<br></div>" +
            "<blockquote class=\"gmail_quote\" style=\"margin:0\">Free on Friday?</blockquote></div>"

        val result = HtmlQuoteDetector.split(html)

        assertEquals("<div dir=\"ltr\">Sounds good.</div><br>", result.visible)
        assertTrue(result.quoted!!.startsWith("<div class=\"gmail_quote\">"))
        assertEquals(html, result.visible + result.quoted)
    }

    @Test
    fun `an Apple Mail blockquote takes its attribution line with it`() {
        val html = "<div>Yes please.</div><div><br><blockquote type=\"cite\"><div>old</div>" +
            "</blockquote></div>"
        val withAttribution = "<div>Yes please.</div><div>On Jan 1, 2024, at 10:00 AM, Ana " +
            "&lt;ana@example.test&gt; wrote:</div><blockquote type=\"cite\"><div>old</div>" +
            "</blockquote>"

        assertEquals("<div>Yes please.</div><div><br>", HtmlQuoteDetector.split(html).visible)
        val result = HtmlQuoteDetector.split(withAttribution)
        assertEquals("<div>Yes please.</div>", result.visible)
        assertTrue(result.quoted!!.startsWith("<div>On Jan 1, 2024"))
    }

    @Test
    fun `an Outlook web reply header folds everything from it on`() {
        val html = "<div>My answer.</div><div id=\"appendonsend\"></div><hr tabindex=\"-1\">" +
            "<div id=\"divRplyFwdMsg\"><b>From:</b> Ana<br><b>Sent:</b> Monday</div>" +
            "<div>Original text</div><div>more</div>"

        val result = HtmlQuoteDetector.split(html)

        assertEquals("<div>My answer.</div>", result.visible)
        assertTrue(result.quoted!!.endsWith("<div>more</div>"))
    }

    @Test
    fun `a Thunderbird citation prefix folds the citation that follows`() {
        val html =
            "<p>Done.</p><div class=\"moz-cite-prefix\">On 1/1/24 10:00, Ana wrote:<br></div>" +
                "<blockquote type=\"cite\"><p>Please?</p></blockquote><p>-- signature</p>"

        val result = HtmlQuoteDetector.split(html)

        assertEquals("<p>Done.</p>", result.visible)
        assertTrue(result.quoted!!.contains("Please?"))
    }

    @Test
    fun `a Yahoo quote is folded`() {
        val html =
            "<div>Fine.</div><div class=\"yahoo_quoted\"><div>On Monday, Ana wrote 1:</div>" +
                "<div>old</div></div>"

        assertEquals("<div>Fine.</div>", HtmlQuoteDetector.split(html).visible)
    }

    @Test
    fun `text after the quote means an interleaved reply and nothing is folded`() {
        val html = "<blockquote>Question one?</blockquote><p>Answer one.</p>" +
            "<blockquote>Question two?</blockquote><p>Answer two.</p>"

        assertFalse(HtmlQuoteDetector.split(html).hasQuote)
    }

    @Test
    fun `only closing tags after the quote still count as the end`() {
        val html = "<div><div>Reply</div><blockquote>old</blockquote></div>"

        val result = HtmlQuoteDetector.split(html)

        assertEquals("<div><div>Reply</div>", result.visible)
        assertEquals("<blockquote>old</blockquote></div>", result.quoted)
    }

    @Test
    fun `nested blockquotes are folded as one`() {
        val html = "<p>Reply</p><blockquote>a<blockquote>b</blockquote></blockquote>"

        val result = HtmlQuoteDetector.split(html)

        assertEquals("<p>Reply</p>", result.visible)
        assertEquals("<blockquote>a<blockquote>b</blockquote></blockquote>", result.quoted)
    }

    @Test
    fun `a message that is only a quote is shown in full`() {
        val html = "<blockquote>only this</blockquote>"

        assertFalse(HtmlQuoteDetector.split(html).hasQuote)
    }

    @Test
    fun `an image before the quote counts as content`() {
        val html = "<img src=\"cid:logo\"><blockquote>old</blockquote>"

        assertEquals("<img src=\"cid:logo\">", HtmlQuoteDetector.split(html).visible)
    }

    @Test
    fun `a blockquote that never closes is folded to the end`() {
        val result = HtmlQuoteDetector.split("<p>Reply</p><blockquote>old and open")

        assertEquals("<p>Reply</p>", result.visible)
        assertEquals("<blockquote>old and open", result.quoted)
    }

    @Test
    fun `a Spanish attribution before the quote moves into the folded part`() {
        val html = "<div>Vale.</div><div>El lun, 1 ene 2024 a las 10:00, Ana " +
            "&lt;ana@example.test&gt; escribi&oacute;:</div><blockquote>viejo</blockquote>"

        assertEquals("<div>Vale.</div>", HtmlQuoteDetector.split(html).visible)
    }

    @Test
    fun `the words of a style block are not mistaken for visible text`() {
        val html = "<style>.gmail_quote{color:red}</style><blockquote>old</blockquote>"

        assertFalse(HtmlQuoteDetector.split(html).hasQuote)
    }
}
