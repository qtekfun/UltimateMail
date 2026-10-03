// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/** Everything needed to sign in to one OAuth provider with the authorization code + PKCE flow. */
data class OAuthProviderConfig(
    val clientId: String,
    val scopes: List<String>,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String
)

/** What the provider table knows about a mail server. */
sealed interface OAuthLookup {
    /** The provider uses OAuth and this build has a client for it. */
    data class Available(val config: OAuthProviderConfig) : OAuthLookup

    /** The provider uses OAuth but no client ID was configured in this build or by the user. */
    data object NotConfigured : OAuthLookup

    /** Not a provider that needs OAuth: the account signs in with a password. */
    data object NotOAuth : OAuthLookup
}
