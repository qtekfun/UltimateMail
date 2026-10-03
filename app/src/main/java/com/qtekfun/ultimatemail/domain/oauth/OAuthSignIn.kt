// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import javax.inject.Inject

/** How the browser sign-in ended, as reported by the UI layer that ran AppAuth. */
sealed interface OAuthBrowserResult {
    data class Success(val tokens: OAuthTokens, val idToken: String?) : OAuthBrowserResult

    /** The user closed the browser or the provider refused the request. */
    data object Cancelled : OAuthBrowserResult

    /** The browser part worked but the code could not be exchanged for tokens. */
    data object Failed : OAuthBrowserResult
}

/** What a finished browser sign-in means for adding the account. */
sealed interface OAuthOutcome {
    /** Signed in: the account has this [address] and these tokens. */
    data class SignedIn(val address: String, val tokens: OAuthTokens) : OAuthOutcome

    data object Cancelled : OAuthOutcome

    data object Failed : OAuthOutcome

    /** Signed in, but the ID token does not say which address the account has. */
    data object NoAddress : OAuthOutcome
}

/** What starting a sign-in found. */
sealed interface OAuthStart {
    /** Open the browser with this configuration. */
    data class Ready(val config: OAuthProviderConfig) : OAuthStart

    /** The user entered text that is not a client ID of this provider. */
    data object InvalidClientId : OAuthStart

    /** There is no client ID to sign in with. */
    data object MissingClientId : OAuthStart
}

/** The rules of "Sign in with Google/Microsoft" that do not depend on the screen. */
class OAuthSignIn @Inject constructor(
    private val clientIds: OAuthClientIds,
    private val configs: OAuthConfigs
) {
    /** The OAuth type of the account for this incoming server, or null for password servers. */
    fun authTypeFor(imapHost: String): AuthType? = configs.authTypeFor(imapHost)

    /** The client ID saved for [authType], to show in the form; empty when there is none. */
    fun savedClientId(authType: AuthType): String = when (authType) {
        AuthType.OAUTH_GOOGLE -> clientIds.google()
        AuthType.OAUTH_MICROSOFT -> clientIds.microsoft()
        AuthType.PASSWORD -> null
    }.orEmpty()

    /** Reads the address of the account from the ID token of a finished sign-in. */
    fun outcomeOf(result: OAuthBrowserResult): OAuthOutcome = when (result) {
        OAuthBrowserResult.Cancelled -> OAuthOutcome.Cancelled

        OAuthBrowserResult.Failed -> OAuthOutcome.Failed

        is OAuthBrowserResult.Success -> IdTokenEmail.from(result.idToken)
            ?.let { OAuthOutcome.SignedIn(it, result.tokens) }
            ?: OAuthOutcome.NoAddress
    }

    /** Checks and saves the [clientIdText] the user entered, then looks up the configuration. */
    fun start(authType: AuthType, clientIdText: String): OAuthStart {
        val clientId = normalize(authType, clientIdText)
        return when {
            clientId == null -> OAuthStart.MissingClientId

            !isValid(authType, clientId) -> OAuthStart.InvalidClientId

            else -> {
                save(authType, clientId)
                when (val lookup = configs.forAuthType(authType)) {
                    is OAuthLookup.Available -> OAuthStart.Ready(lookup.config)
                    OAuthLookup.NotConfigured, OAuthLookup.NotOAuth -> OAuthStart.MissingClientId
                }
            }
        }
    }

    private fun normalize(authType: AuthType, text: String): String? = when (authType) {
        AuthType.OAUTH_GOOGLE -> GoogleClientId.normalize(text)
        AuthType.OAUTH_MICROSOFT -> MicrosoftClientId.normalize(text)
        AuthType.PASSWORD -> null
    }

    private fun isValid(authType: AuthType, clientId: String): Boolean = when (authType) {
        AuthType.OAUTH_GOOGLE -> GoogleClientId.isValid(clientId)
        else -> MicrosoftClientId.isValid(clientId)
    }

    private fun save(authType: AuthType, clientId: String) = when (authType) {
        AuthType.OAUTH_GOOGLE -> clientIds.setGoogle(clientId)
        else -> clientIds.setMicrosoft(clientId)
    }
}
