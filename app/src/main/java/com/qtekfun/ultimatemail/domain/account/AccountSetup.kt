// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.di.IoDispatcher
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface CreateAccountResult {
    data class Created(val accountId: Long) : CreateAccountResult

    data class Invalid(val errors: List<AccountInputError>) : CreateAccountResult

    /** Saving failed; nothing was left behind (no account row, no credentials). */
    data object StorageFailed : CreateAccountResult
}

/** Adding an account: autodetection, validation, connection test and atomic creation (RF-01). */
class AccountSetup @Inject constructor(
    database: UltimateMailDatabase,
    private val vault: CredentialVault,
    private val validator: AccountValidator,
    private val autodetector: ServerAutodetector,
    private val connectionTester: AccountConnectionTester,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()

    fun detectServers(email: String): ServerSuggestion? = autodetector.detect(email)

    fun validate(input: AccountInput): List<AccountInputError> = validator.validate(input)

    /** Tries the settings against the servers; invalid input is reported as an unknown failure. */
    suspend fun testConnection(input: AccountInput): ConnectionTestResult =
        if (validator.validate(input).isNotEmpty()) {
            ConnectionTestResult.Failure(ConnectionFailure.UNKNOWN)
        } else {
            connectionTester.test(input)
        }

    /**
     * Stores the account and its credentials together: if the credentials cannot be saved, the
     * account row is removed again.
     */
    suspend fun create(input: AccountInput): CreateAccountResult = withContext(io) {
        val errors = validator.validate(input) + duplicateErrors(input)
        if (errors.isNotEmpty()) return@withContext CreateAccountResult.Invalid(errors)
        val accountId = accounts.insert(input.toEntity())
        try {
            vault.save(accountId, input.credentials)
            CreateAccountResult.Created(accountId)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { accounts.delete(accountId) }
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            withContext(NonCancellable) { accounts.delete(accountId) }
            CreateAccountResult.StorageFailed
        }
    }

    /**
     * Stores an account without credentials (an imported configuration, RF-12). It is valid
     * apart from the missing secrets, and ends up in the "sign in again" state: nothing is
     * stored in the vault, so the sync engine reports `ReauthenticationNeeded` for it.
     * [AccountInput.credentials] is ignored.
     */
    suspend fun createAwaitingSignIn(input: AccountInput): CreateAccountResult = withContext(io) {
        val errors = validator.validate(input)
            .filterNot { it == AccountInputError.MissingCredentials } + duplicateErrors(input)
        if (errors.isNotEmpty()) {
            CreateAccountResult.Invalid(errors)
        } else {
            CreateAccountResult.Created(accounts.insert(input.toEntity()))
        }
    }

    private suspend fun duplicateErrors(input: AccountInput): List<AccountInputError> {
        val email = input.email.trim()
        val exists = accounts.observeAll().first().any { it.email.equals(email, ignoreCase = true) }
        return if (exists) listOf(AccountInputError.DuplicateAccount) else emptyList()
    }

    private fun AccountInput.toEntity() = AccountEntity(
        email = email.trim(),
        displayName = displayName.ifBlank { email.trim().substringBefore('@') },
        username = username.trim(),
        authType = authType,
        imapHost = imap.host,
        imapPort = imap.port,
        imapSecurity = imap.security,
        smtpHost = smtp.host,
        smtpPort = smtp.port,
        smtpSecurity = smtp.security
    )
}
