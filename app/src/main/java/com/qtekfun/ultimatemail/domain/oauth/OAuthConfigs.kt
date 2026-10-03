// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType

/**
 * The provider table with the client IDs of this moment: what the user saved, or for Google the
 * one built into the app. It reads the IDs on every call, so a newly entered ID applies at once.
 */
class OAuthConfigs(
    private val clientIds: OAuthClientIds,
    private val applicationId: String,
    private val builtInGoogleClientId: String
) {
    private fun providers() = OAuthProviders(
        googleClientId = clientIds.google() ?: builtInGoogleClientId,
        applicationId = applicationId,
        microsoftClientId = clientIds.microsoft().orEmpty()
    )

    /** The OAuth setup for accounts of [authType]; [OAuthLookup.NotOAuth] for passwords. */
    fun forAuthType(authType: AuthType): OAuthLookup = providers().forAuthType(authType)

    /** The provider setup for [authType], or null when it needs no OAuth or has no client ID. */
    fun configFor(authType: AuthType): OAuthProviderConfig? =
        (forAuthType(authType) as? OAuthLookup.Available)?.config

    /** How accounts of this server authenticate; null when the host needs no OAuth. */
    fun authTypeFor(host: String): AuthType? = providers().authTypeFor(host)
}
