// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/**
 * Whether the app offers sign-in with Google or Microsoft (OAuth2) when an account is added.
 *
 * It is off: a Google client able to read Gmail needs a verified app, which costs a security
 * assessment, and without it the refresh token lasts 7 days; Microsoft needs an app registration
 * per user. Accounts use an app password instead, which is free and does not expire. The OAuth
 * code (sign-in, token refresh, the client ID stores, the reauthentication of an account that
 * already uses it) stays in place and tested, so turning this on brings the buttons back. See
 * `docs/oauth-setup.md` and decision 41 of `docs/autonomous-decisions.md`.
 */
object OAuthFeature {
    const val ENABLED = false
}
