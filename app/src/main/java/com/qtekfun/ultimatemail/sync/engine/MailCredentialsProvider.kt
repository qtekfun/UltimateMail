// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

/** What the credentials of an account allow right now. */
sealed interface CredentialsResult {
    data class Ready(val credentials: MailCredentials) : CredentialsResult

    /** Nothing usable is stored or the provider revoked access: the user must sign in again. */
    data object ReauthenticationNeeded : CredentialsResult

    /** A new token could not be had for now (network, provider trouble); try again later. */
    data object TemporarilyUnavailable : CredentialsResult
}

/**
 * Builds [MailCredentials] from the encrypted vault: the password for password accounts, and for
 * OAuth accounts the access token, refreshed through [OAuthTokenSource] when it has expired.
 */
class MailCredentialsProvider @Inject constructor(
    private val vault: CredentialVault,
    private val oauth: OAuthTokenSource,
    private val clock: Clock
) {
    /** [forceRefresh] gets a new OAuth token even if the stored one looks valid. */
    suspend fun forAccount(
        account: AccountEntity,
        forceRefresh: Boolean = false
    ): CredentialsResult {
        val stored = vault.load(account.id) ?: return CredentialsResult.ReauthenticationNeeded
        return when (account.authType) {
            AuthType.PASSWORD ->
                stored.password
                    ?.let {
                        CredentialsResult.Ready(MailCredentials.Password(account.username, it))
                    }
                    ?: CredentialsResult.ReauthenticationNeeded

            AuthType.OAUTH_GOOGLE, AuthType.OAUTH_MICROSOFT -> oauthCredentials(
                account,
                stored,
                forceRefresh
            )
        }
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun oauthCredentials(
        account: AccountEntity,
        stored: AccountCredentials,
        forceRefresh: Boolean
    ): CredentialsResult {
        val tokens = stored.oauth ?: return CredentialsResult.ReauthenticationNeeded
        val expired = tokens.expiresAt?.let { it <= clock.instant().plus(EXPIRY_MARGIN) } ?: false
        if (!expired && !forceRefresh) {
            return CredentialsResult.Ready(
                MailCredentials.OAuthBearer(account.username, tokens.accessToken)
            )
        }
        val refreshToken = tokens.refreshToken ?: return CredentialsResult.ReauthenticationNeeded
        return when (val result = oauth.refresh(account.authType, refreshToken)) {
            is OAuthRefreshResult.Refreshed -> {
                // Providers may leave the refresh token out of a refresh answer: keep the old one.
                val fresh = result.tokens.copy(
                    refreshToken = result.tokens.refreshToken ?: refreshToken
                )
                vault.save(account.id, stored.copy(oauth = fresh))
                CredentialsResult.Ready(
                    MailCredentials.OAuthBearer(account.username, fresh.accessToken)
                )
            }

            OAuthRefreshResult.Revoked -> CredentialsResult.ReauthenticationNeeded

            OAuthRefreshResult.TemporaryFailure, OAuthRefreshResult.Unavailable ->
                CredentialsResult.TemporarilyUnavailable
        }
    }

    private companion object {
        /** A token about to expire is refreshed now, not halfway through a sync. */
        val EXPIRY_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
