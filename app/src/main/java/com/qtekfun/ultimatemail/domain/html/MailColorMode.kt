// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** How the colours of one HTML message are shown. */
enum class MailColorMode(
    /** The value of the page's `color-scheme`. */
    val colorScheme: String,
    /** Whether the web view may darken the page itself (Android 13+). */
    val algorithmicDarkening: Boolean
) {
    /** The colours the sender chose: light theme, or the reader asked for the original. */
    ORIGINAL("light", false),

    /** The web view inverts a light page to fit the dark theme. */
    DARKENED("light", true),

    /** The message brings its own dark colours: shown as they are, never inverted twice. */
    OWN_DARK("dark", false),

    /** Dark theme on a device that cannot darken pages: only the defaults turn dark. */
    DARK_DEFAULTS("dark", false)
}

/** Chooses the [MailColorMode] of a message from the app theme (RF-11) and what the mail says. */
object MailDarkMode {
    private val DECLARES_DARK = Regex(
        "color-scheme\\s*:[^;}\"]*dark|prefers-color-scheme\\s*:\\s*dark",
        RegexOption.IGNORE_CASE
    )

    /** True when the (sanitized) message styles itself for dark mode. */
    fun declaresDarkScheme(html: String): Boolean = DECLARES_DARK.containsMatchIn(html)

    fun decide(
        appDark: Boolean,
        viewOriginal: Boolean,
        declaresDark: Boolean,
        canDarken: Boolean
    ): MailColorMode = when {
        !appDark || viewOriginal -> MailColorMode.ORIGINAL
        declaresDark -> MailColorMode.OWN_DARK
        canDarken -> MailColorMode.DARKENED
        else -> MailColorMode.DARK_DEFAULTS
    }

    /** Offering "view original colours" only makes sense when the dark theme changed them. */
    fun canToggleOriginal(mode: MailColorMode, viewOriginal: Boolean): Boolean =
        viewOriginal || mode == MailColorMode.DARKENED || mode == MailColorMode.DARK_DEFAULTS
}
