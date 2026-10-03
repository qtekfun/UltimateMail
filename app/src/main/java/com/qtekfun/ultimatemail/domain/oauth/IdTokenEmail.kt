// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import java.util.Base64

/**
 * Reads the "email" claim of an OpenID ID token. The token is not verified here: it only tells
 * the app which account just signed in, and the mail server checks the access token itself.
 */
object IdTokenEmail {
    private val EMAIL_CLAIM = Regex("\"email\"\\s*:\\s*\"([^\"]+)\"")

    fun from(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1)
        val json = payload?.let { runCatching { decode(it) }.getOrNull() }
        return json?.let { EMAIL_CLAIM.find(it)?.groupValues?.get(1) }
    }

    private fun decode(payload: String) =
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
}
