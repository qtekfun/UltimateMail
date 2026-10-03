// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import java.time.Instant

/** OAuth2 tokens of an account. Refresh-ready: the expiry tells when to ask for a new access token. */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?
) {
    // Never print tokens, not even by accident in a log line.
    override fun toString(): String = "OAuthTokens(redacted)"
}

/** The secrets of one account: an IMAP/SMTP password, OAuth tokens, or both. */
data class AccountCredentials(val password: String? = null, val oauth: OAuthTokens? = null) {
    override fun toString(): String = "AccountCredentials(redacted)"
}
