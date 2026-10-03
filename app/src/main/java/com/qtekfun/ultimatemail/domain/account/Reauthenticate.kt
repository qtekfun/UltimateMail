// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What the re-authentication screen shows about the account; never any secret. */
data class ReauthTarget(
    val accountId: Long,
    val email: String,
    val authType: AuthType,
    val imapHost: String
)

sealed interface ReauthResult {
    /** New credentials are stored, the account no longer waits and a sync was requested. */
    data object Success : ReauthResult

    /** The servers did not accept the new credentials; the old ones are untouched. */
    data class Failure(val reason: ConnectionFailure) : ReauthResult

    /** This build has no connection tester, so nothing was verified and nothing was stored. */
    data object TestUnavailable : ReauthResult

    /** The provider signed in a different address than the account's: nothing was stored. */
    data object AddressMismatch : ReauthResult

    /** The credentials do not fit the account (empty password, or the wrong sign-in method). */
    data object Invalid : ReauthResult

    /** Saving failed; the previous credentials are still there. */
    data object StorageFailed : ReauthResult

    /** The account does not exist (any more). */
    data object NoAccount : ReauthResult
}

/**
 * Signing in again to an existing account (RF-01): the way out of
 * `AccountSyncState.ReauthenticationNeeded`. Only the credentials change: the account row, its
 * folders, messages and queued operations are never touched, so nothing local is lost. The new
 * credentials are tested first and replace the old ones only if the server accepts them.
 */
class Reauthenticate @Inject constructor(
    private val accounts: AccountDao,
    private val vault: CredentialVault,
    private val connectionTester: AccountConnectionTester,
    private val status: SyncStatusStore,
    private val scheduler: SyncScheduler,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** What to show for [accountId], or null when there is no such account. */
    suspend fun target(accountId: Long): ReauthTarget? = withContext(io) {
        accounts.get(accountId)?.let {
            ReauthTarget(it.id, it.email, it.authType, it.imapHost)
        }
    }

    /** Signs a password account in with [password] (an app password where the provider needs one). */
    suspend fun withPassword(accountId: Long, password: String): ReauthResult = withContext(io) {
        val account = accounts.get(accountId) ?: return@withContext ReauthResult.NoAccount
        if (account.authType != AuthType.PASSWORD || password.isEmpty()) {
            return@withContext ReauthResult.Invalid
        }
        replace(account, AccountCredentials(password = password))
    }

    /**
     * Signs an OAuth account in with the [tokens] a browser sign-in returned for [address]. An
     * address other than the account's is refused: tokens of another mailbox must never end up on
     * this account.
     */
    suspend fun withOAuth(accountId: Long, address: String, tokens: OAuthTokens): ReauthResult =
        withContext(io) {
            val account = accounts.get(accountId) ?: return@withContext ReauthResult.NoAccount
            when {
                account.authType == AuthType.PASSWORD -> ReauthResult.Invalid

                !address.trim().equals(account.email.trim(), ignoreCase = true) ->
                    ReauthResult.AddressMismatch

                else -> replace(account, AccountCredentials(oauth = tokens))
            }
        }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun replace(
        account: AccountEntity,
        credentials: AccountCredentials
    ): ReauthResult {
        when (val tested = connectionTester.test(account.toInput(credentials))) {
            ConnectionTestResult.Success -> Unit
            is ConnectionTestResult.Failure -> return ReauthResult.Failure(tested.reason)
            ConnectionTestResult.NotAvailable -> return ReauthResult.TestUnavailable
        }
        try {
            vault.save(account.id, credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return ReauthResult.StorageFailed
        }
        status.clearReauthentication(account.id)
        scheduler.requestSync(account.id, userInitiated = true)
        return ReauthResult.Success
    }

    private fun AccountEntity.toInput(credentials: AccountCredentials) = AccountInput(
        email = email,
        displayName = displayName,
        username = username,
        authType = authType,
        imap = ServerEndpoint(imapHost, imapPort, imapSecurity),
        smtp = ServerEndpoint(smtpHost, smtpPort, smtpSecurity),
        credentials = credentials
    )
}
