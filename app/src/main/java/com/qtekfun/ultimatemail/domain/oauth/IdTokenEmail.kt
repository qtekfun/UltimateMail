// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import java.util.Base64

/**
 * Reads the account address from an OpenID ID token: the "email" claim or, for Microsoft tokens
 * that lack it, "preferred_username" (the sign-in name, an address in practice). The token is not
 * verified here: it only tells the app which account just signed in, and the mail server checks
 * the access token itself.
 */
object IdTokenEmail {
    private val EMAIL_CLAIM = claim("email")
    private val PREFERRED_USERNAME_CLAIM = claim("preferred_username")

    private fun claim(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"")

    fun from(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1)
        val json = payload?.let { runCatching { decode(it) }.getOrNull() }
        return json?.let { text ->
            listOf(EMAIL_CLAIM, PREFERRED_USERNAME_CLAIM)
                .mapNotNull { it.find(text)?.groupValues?.get(1) }
                .firstOrNull { '@' in it }
        }
    }

    private fun decode(payload: String) =
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
}
