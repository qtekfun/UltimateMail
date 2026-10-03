// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The result of running something with an open IMAP session of an account. */
sealed interface Leased<out T> {
    data class Ok<out T>(val value: T) : Leased<T>

    /** The user has to sign in again (RF-01); the sync state of the account says so. */
    data object AuthRequired : Leased<Nothing>

    /** No session could be opened. */
    data class Failed(val failure: MailResult.Failure) : Leased<Nothing>

    data object NoAccount : Leased<Nothing>
}

internal fun ConnectionSecurity.toTransport() = when (this) {
    ConnectionSecurity.TLS -> TransportSecurity.TLS
    ConnectionSecurity.STARTTLS -> TransportSecurity.STARTTLS
}

internal fun AccountEntity.imapServer() = MailServer(imapHost, imapPort, imapSecurity.toTransport())

internal fun AccountEntity.smtpServer() = MailServer(smtpHost, smtpPort, smtpSecurity.toTransport())

/**
 * Hands out one IMAP session per account, shared by everything that needs the server at the same
 * time (the sync, the operation queue, reading a body). The session opens with the first user
 * and closes with the last, so a sync that holds it for its whole run lets the queue reuse the
 * connection instead of logging in again for every operation.
 */
@Singleton
class AccountSessions @Inject constructor(
    private val accounts: AccountDao,
    private val credentials: MailCredentialsProvider,
    private val connector: MailConnector,
    private val status: SyncStatusStore
) {
    private class Entry(val session: MailSession) {
        var users = 0
    }

    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val open = ConcurrentHashMap<Long, Entry>()

    /** Runs [block] with a connected session of [accountId], opening one if needed. */
    suspend fun <T> withSession(accountId: Long, block: suspend (MailSession) -> T): Leased<T> {
        val acquired = acquire(accountId)
        if (acquired is Acquired.Not) return acquired.result
        acquired as Acquired.Session
        return try {
            Leased.Ok(block(acquired.session))
        } finally {
            withContext(NonCancellable) { release(accountId) }
        }
    }

    private sealed interface Acquired {
        class Session(val session: MailSession) : Acquired

        class Not(val result: Leased<Nothing>) : Acquired
    }

    private suspend fun acquire(accountId: Long): Acquired =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            open[accountId]?.let {
                it.users++
                return@withLock Acquired.Session(it.session)
            }
            when (val connected = connect(accountId)) {
                is Leased.Ok -> {
                    open[accountId] = Entry(connected.value).also { it.users = 1 }
                    Acquired.Session(connected.value)
                }

                Leased.AuthRequired -> Acquired.Not(Leased.AuthRequired)

                is Leased.Failed -> Acquired.Not(connected)

                Leased.NoAccount -> Acquired.Not(Leased.NoAccount)
            }
        }

    private suspend fun release(accountId: Long) {
        val session = locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val entry = open[accountId] ?: return
            entry.users--
            if (entry.users > 0) return
            open.remove(accountId)
            entry.session
        }
        session.close()
    }

    private suspend fun connect(accountId: Long): Leased<MailSession> {
        val account = accounts.get(accountId) ?: return Leased.NoAccount
        var attempt = connectWith(account, forceRefresh = false)
        // A rejected OAuth token may only be stale: refresh it once before giving up on it.
        if (attempt is Leased.AuthRequired && account.authType != AuthType.PASSWORD) {
            attempt = connectWith(account, forceRefresh = true)
        }
        if (attempt is Leased.AuthRequired) {
            status.set(accountId, AccountSyncState.ReauthenticationNeeded)
        }
        return attempt
    }

    private suspend fun connectWith(
        account: AccountEntity,
        forceRefresh: Boolean
    ): Leased<MailSession> = when (val ready = credentials.forAccount(account, forceRefresh)) {
        CredentialsResult.ReauthenticationNeeded -> Leased.AuthRequired

        CredentialsResult.TemporarilyUnavailable -> Leased.Failed(MailResult.NetworkUnavailable)

        is CredentialsResult.Ready -> when (
            val result = connector.connect(account.imapServer(), ready.credentials)
        ) {
            is MailResult.Success -> Leased.Ok(result.value)
            MailResult.AuthenticationFailed -> Leased.AuthRequired
            is MailResult.Failure -> Leased.Failed(result)
        }
    }
}
