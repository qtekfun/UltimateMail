// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/** A Google OAuth client ID as the user pastes it: a number, a hash and the Google suffix. */
object GoogleClientId {
    private val FORMAT = Regex("""\d+-[a-z0-9]+\.apps\.googleusercontent\.com""")

    /** The text without surrounding whitespace, which pasting often adds. */
    fun normalize(text: String): String = text.trim()

    /** Whether [text] looks like a Google client ID; the empty text means "none" and is valid. */
    fun isValid(text: String): Boolean = normalize(text).let { it.isEmpty() || FORMAT.matches(it) }
}
