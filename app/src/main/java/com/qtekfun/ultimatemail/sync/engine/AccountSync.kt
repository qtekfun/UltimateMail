// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

/**
 * One full sync of one account: connect, push the queue, refresh the folder list, then for each
 * folder push the queue again and pull the folder. Pushing first means a local change is on the
 * server before the pull could overwrite it (SPEC section 5, rule 1).
 */
// Every collaborator is a distinct part of the run; grouping them would only hide that.
@Suppress("LongParameterList")
class AccountSync @Inject internal constructor(
    private val accounts: AccountDao,
    private val folders: FolderDao,
    private val messages: MessageDao,
    private val sessions: AccountSessions,
    private val catalog: FolderCatalog,
    private val puller: FolderPuller,
    private val queue: OperationQueue,
    private val clock: Clock
) {
    suspend fun run(accountId: Long): AccountSyncResult {
        val account = accounts.get(accountId) ?: return AccountSyncResult.NoAccount
        return when (val leased = sessions.withSession(accountId) { syncWith(account, it) }) {
            is Leased.Ok -> leased.value
            Leased.AuthRequired -> AccountSyncResult.ReauthenticationNeeded
            is Leased.Failed -> failed(leased.failure)
            Leased.NoAccount -> AccountSyncResult.NoAccount
        }
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun syncWith(account: AccountEntity, session: MailSession): AccountSyncResult {
        queue.drain(account.id)
        catalog.refresh(account, session)?.let { return failed(it) }
        prune(account)
        val assigner = ThreadAssigner(account.id, messages)
        var counts = SyncCounts()
        var firstFailure: MailResult.Failure? = null
        for (folder in folders.syncable(account.id)) {
            // Push before pull, folder by folder, so changes made meanwhile go out too.
            queue.drain(account.id)
            when (val outcome = puller.pull(session, account, folder, assigner)) {
                is PullOutcome.Pulled -> counts += outcome.counts

                is PullOutcome.Failed -> {
                    // The connection is gone or the login no longer works: the other folders
                    // would fail the same way. Anything else only concerns this folder.
                    if (outcome.failure.endsRun) return failed(outcome.failure)
                    firstFailure = firstFailure ?: outcome.failure
                }
            }
        }
        return firstFailure?.let { failed(it) } ?: AccountSyncResult.Synced(counts)
    }

    /** Drops headers older than the account's offline window (RF-10). */
    private suspend fun prune(account: AccountEntity) {
        val days = account.offlineWindowDays ?: return
        messages.deleteOlderThan(
            account.id,
            clock.instant().minus(Duration.ofDays(days.toLong())).toEpochMilli()
        )
    }

    private fun failed(failure: MailResult.Failure): AccountSyncResult =
        if (failure == MailResult.AuthenticationFailed) {
            AccountSyncResult.ReauthenticationNeeded
        } else {
            AccountSyncResult.Failed(failure.toProblem())
        }

    private val MailResult.Failure.endsRun: Boolean
        get() = this == MailResult.NetworkUnavailable || this == MailResult.Timeout ||
            this == MailResult.AuthenticationFailed || this == MailResult.CertificateRejected
}
