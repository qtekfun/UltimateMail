// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderingPolicyTest {
    @Test
    fun `by default nothing can load but inline images and message parts`() {
        assertEquals(
            "default-src 'none'; img-src data: cid:; style-src 'unsafe-inline'; " +
                "base-uri 'none'; form-action 'none'",
            ContentSecurityPolicy.build(allowRemoteContent = false)
        )
    }

    @Test
    fun `allowing remote content opens images to https only`() {
        val policy = ContentSecurityPolicy.build(allowRemoteContent = true)
        assertTrue("img-src data: cid: https:;" in policy)
        assertTrue("default-src 'none'" in policy)
        assertFalse("http:" in policy.replace("https:", ""))
        assertFalse("script-src" in policy)
    }

    @Test
    fun `the document puts the policy before the message`() {
        val page = EmailDocument.wrap(SanitizedHtml("<p>hi</p>", 0, emptyList()), false)
        val policyAt = page.indexOf("Content-Security-Policy")
        assertTrue(policyAt in 0 until page.indexOf("<p>hi</p>"))
        assertTrue(page.startsWith("<!DOCTYPE html><html><head><meta charset=\"utf-8\">"))
        assertTrue("content=\"${ContentSecurityPolicy.build(false)}\"" in page)
        assertTrue(page.endsWith("<p>hi</p></body></html>"))
        assertTrue("cid: https:" in EmailDocument.wrap(SanitizedHtml("", 0, emptyList()), true))
    }

    @Test
    fun `requests are allowed for inline images and parts, https only on request`() {
        val inline = "data:image/png;base64,AAAA"
        assertTrue(RequestPolicy.allowsRequest(inline, false))
        assertTrue(RequestPolicy.allowsRequest("cid:logo@x", false))
        assertFalse(RequestPolicy.allowsRequest("https://t.example/p.gif", false))
        assertTrue(RequestPolicy.allowsRequest("https://t.example/p.gif", true))
        listOf(
            "http://t.example/p.gif", "//t.example/p.gif", "file:///data/x", "content://x/y",
            "javascript:alert(1)", "data:text/html;base64,AAAA", "ftp://x", "about:blank", ""
        ).forEach {
            assertFalse(RequestPolicy.allowsRequest(it, true), it)
            assertFalse(RequestPolicy.allowsRequest(it, false), it)
        }
        assertFalse(RequestPolicy.allowsRequest("  java\tscript:alert(1)", true))
    }

    @Test
    fun `only web, mail and phone links open outside the message`() {
        assertEquals("https://a.test/x", RequestPolicy.externalLink("https://a.test/x"))
        assertEquals("http://a.test", RequestPolicy.externalLink("http://a.test"))
        assertEquals("mailto:a@b.test", RequestPolicy.externalLink("mailto:a@b.test"))
        assertEquals("tel:+34600", RequestPolicy.externalLink(" tel:+34 600 "))
        listOf(
            "javascript:alert(1)", "file:///x", "content://x", "intent://x#Intent;end",
            "data:text/html,x",
            "#frag", "//a.test", "relative", "cid:x", ""
        ).forEach { assertNull(RequestPolicy.externalLink(it), it) }
    }

    @Test
    fun `the message document itself loads, any other main frame request does not`() {
        val document = "data:text/html;charset=utf-8;base64,PGh0bWw+PC9odG1sPg=="

        assertTrue(RequestPolicy.isOwnDocument(document, isMainFrame = true))
        assertTrue(RequestPolicy.isOwnDocument("DATA:TEXT/HTML,<p>x</p>", isMainFrame = true))
        // The same URL as a sub-resource is not the document.
        assertFalse(RequestPolicy.isOwnDocument(document, isMainFrame = false))
        // Navigating the main frame anywhere else stays blocked.
        assertFalse(RequestPolicy.isOwnDocument("https://tracker.example/x", isMainFrame = true))
        assertFalse(RequestPolicy.isOwnDocument("javascript:alert(1)", isMainFrame = true))
        assertFalse(RequestPolicy.isOwnDocument("file:///etc/passwd", isMainFrame = true))
        assertFalse(RequestPolicy.isOwnDocument("data:image/png;base64,AAAA", isMainFrame = true))
    }
}
