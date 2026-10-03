// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType

/**
 * The provider table, keyed by mail server host like Thunderbird's. Client IDs are public, they
 * ship with the app or are entered by the user (SPEC §9), so they are injected here.
 */
class OAuthProviders(
    private val googleClientId: String,
    private val applicationId: String,
    private val microsoftClientId: String = ""
) {
    /** The OAuth setup for the IMAP or SMTP [host] of an account. */
    fun forHost(host: String): OAuthLookup = when (host.trim().lowercase()) {
        in GOOGLE_HOSTS -> google()
        in MICROSOFT_HOSTS -> microsoft()
        else -> OAuthLookup.NotOAuth
    }

    /** The OAuth setup for accounts of [authType]; [OAuthLookup.NotOAuth] for passwords. */
    fun forAuthType(authType: AuthType): OAuthLookup = when (authType) {
        AuthType.OAUTH_GOOGLE -> google()
        AuthType.OAUTH_MICROSOFT -> microsoft()
        AuthType.PASSWORD -> OAuthLookup.NotOAuth
    }

    /** How accounts of this server authenticate; null when the host needs no OAuth. */
    fun authTypeFor(host: String): AuthType? = when (host.trim().lowercase()) {
        in GOOGLE_HOSTS -> AuthType.OAUTH_GOOGLE
        in MICROSOFT_HOSTS -> AuthType.OAUTH_MICROSOFT
        else -> null
    }

    private fun google(): OAuthLookup = if (googleClientId.isBlank()) {
        OAuthLookup.NotConfigured
    } else {
        OAuthLookup.Available(
            OAuthProviderConfig(
                clientId = googleClientId,
                // openid and email give an ID token that names the account, which the IMAP
                // XOAUTH2 login needs as its user name.
                scopes = listOf("https://mail.google.com/", "openid", "email"),
                authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenEndpoint = "https://oauth2.googleapis.com/token",
                redirectUri = "$applicationId:/oauth2redirect"
            )
        )
    }

    // Public client with PKCE and no secret. offline_access gives the refresh token; the two
    // outlook.office.com scopes are the IMAP and SMTP access, and openid/email/profile give an ID
    // token that names the account. "common" accepts work/school and personal accounts.
    private fun microsoft(): OAuthLookup = if (microsoftClientId.isBlank()) {
        OAuthLookup.NotConfigured
    } else {
        OAuthLookup.Available(
            OAuthProviderConfig(
                clientId = microsoftClientId,
                scopes = listOf(
                    "openid",
                    "email",
                    "profile",
                    "offline_access",
                    "https://outlook.office.com/IMAP.AccessAsUser.All",
                    "https://outlook.office.com/SMTP.Send"
                ),
                authorizationEndpoint =
                    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                redirectUri = "$applicationId:/oauth2redirect"
            )
        )
    }

    private companion object {
        val GOOGLE_HOSTS = setOf(
            "imap.gmail.com",
            "imap.googlemail.com",
            "smtp.gmail.com",
            "smtp.googlemail.com"
        )
        val MICROSOFT_HOSTS = setOf(
            "outlook.office365.com",
            "smtp.office365.com",
            "smtp-mail.outlook.com",
            "outlook.office.com"
        )
    }
}
