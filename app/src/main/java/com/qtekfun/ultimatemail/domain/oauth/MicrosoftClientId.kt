// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/** A Microsoft Entra application (client) ID as the user pastes it: a GUID. */
object MicrosoftClientId {
    private val FORMAT =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    /** The text without surrounding whitespace, which pasting often adds. */
    fun normalize(text: String): String = text.trim()

    /** Whether [text] is a GUID; the empty text means "none" and is valid. */
    fun isValid(text: String): Boolean = normalize(text).let { it.isEmpty() || FORMAT.matches(it) }
}
