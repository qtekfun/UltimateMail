// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.domain.conversation.RemoteBanner
import com.qtekfun.ultimatemail.domain.conversation.RemoteBanners
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPolishTest {
    private fun page(colors: MailColorMode = MailColorMode.ORIGINAL) =
        EmailDocument.wrap(SanitizedHtml("<p>x</p>", 0, emptyList()), false, colors)

    @Test
    fun `the page fits the screen width and keeps wide content inside it`() {
        val page = page()
        assertTrue(
            "<meta name=\"viewport\" content=\"width=device-width\">" in page
        )
        assertTrue("img{max-width:100%;height:auto}" in page)
        assertTrue("table[width],table[style*=\"width\"]{width:100%!important}" in page)
        assertTrue("overflow-wrap:anywhere" in page)
        assertTrue("pre{white-space:pre-wrap}" in page)
    }

    @Test
    fun `blocked images get a placeholder and blocked pixels no room`() {
        val page = page()
        assertTrue("img[data-blocked-src]{display:inline-block" in page)
        assertTrue("img[data-blocked-src][width=\"1\"]" in page)
    }

    @Test
    fun `the colour scheme of the page follows the colour mode`() {
        assertTrue("<meta name=\"color-scheme\" content=\"light\">" in page(MailColorMode.DARKENED))
        assertTrue("<meta name=\"color-scheme\" content=\"dark\">" in page(MailColorMode.OWN_DARK))
        assertTrue("content=\"dark\"" in page(MailColorMode.DARK_DEFAULTS))
        assertTrue("content=\"light\"" in page())
    }

    @Test
    fun `light theme and the original colours request never darken`() {
        assertEquals(MailColorMode.ORIGINAL, decide(appDark = false))
        assertEquals(MailColorMode.ORIGINAL, decide(appDark = true, viewOriginal = true))
        assertEquals(MailColorMode.ORIGINAL, decide(appDark = false, declaresDark = true))
    }

    @Test
    fun `in the dark theme a light mail is darkened where the device can`() {
        assertEquals(MailColorMode.DARKENED, decide(appDark = true))
        assertTrue(MailColorMode.DARKENED.algorithmicDarkening)
        assertEquals(MailColorMode.DARK_DEFAULTS, decide(appDark = true, canDarken = false))
        assertFalse(MailColorMode.DARK_DEFAULTS.algorithmicDarkening)
    }

    @Test
    fun `a mail that already is dark is not inverted again`() {
        val mode = decide(appDark = true, declaresDark = true)
        assertEquals(MailColorMode.OWN_DARK, mode)
        assertFalse(mode.algorithmicDarkening)
    }

    @Test
    fun `dark schemes declared in the styles are found`() {
        assertTrue(
            MailDarkMode.declaresDarkScheme("<style>:root{color-scheme: light dark}</style>")
        )
        assertTrue(MailDarkMode.declaresDarkScheme("<style>body{COLOR-SCHEME:dark}</style>"))
        assertTrue(
            MailDarkMode.declaresDarkScheme(
                "<style>@media (prefers-color-scheme: dark){body{color:#fff}}</style>"
            )
        )
        assertFalse(MailDarkMode.declaresDarkScheme("<style>body{color-scheme:light}</style>"))
        assertFalse(MailDarkMode.declaresDarkScheme("<p>a dark color-scheme story</p>"))
    }

    @Test
    fun `the original colours toggle is only offered when the theme changed the colours`() {
        assertTrue(MailDarkMode.canToggleOriginal(MailColorMode.DARKENED, false))
        assertTrue(MailDarkMode.canToggleOriginal(MailColorMode.DARK_DEFAULTS, false))
        assertTrue(MailDarkMode.canToggleOriginal(MailColorMode.ORIGINAL, true))
        assertFalse(MailDarkMode.canToggleOriginal(MailColorMode.ORIGINAL, false))
        assertFalse(MailDarkMode.canToggleOriginal(MailColorMode.OWN_DARK, false))
    }

    @Test
    fun `the height is never zero and never beyond what layout can take`() {
        assertEquals(ReaderHeight.MIN_PX, ReaderHeight.clamp(0))
        assertEquals(ReaderHeight.MIN_PX, ReaderHeight.clamp(-5))
        assertEquals(1_234, ReaderHeight.clamp(1_234))
        assertEquals(ReaderHeight.MAX_PX, ReaderHeight.clamp(Int.MAX_VALUE))
        assertTrue(ReaderHeight.MAX_PX < (1 shl 18))
    }

    @Test
    fun `blocked content gets a banner worded by the policy`() {
        assertEquals(RemoteBanner.BLOCKED, banner(RemoteContentPolicy.NEVER))
        assertEquals(RemoteBanner.ASK, banner(RemoteContentPolicy.ASK))
    }

    @Test
    fun `there is no banner without blocked content or once the message was allowed`() {
        for (policy in RemoteContentPolicy.entries) {
            assertNull(
                RemoteBanners.of(policy, blockedRemoteContent = false, allowedForMessage = false)
            )
            assertNull(
                RemoteBanners.of(policy, blockedRemoteContent = true, allowedForMessage = true)
            )
        }
    }

    private fun banner(policy: RemoteContentPolicy) =
        RemoteBanners.of(policy, blockedRemoteContent = true, allowedForMessage = false)

    private fun decide(
        appDark: Boolean,
        viewOriginal: Boolean = false,
        declaresDark: Boolean = false,
        canDarken: Boolean = true
    ) = MailDarkMode.decide(appDark, viewOriginal, declaresDark, canDarken)
}
