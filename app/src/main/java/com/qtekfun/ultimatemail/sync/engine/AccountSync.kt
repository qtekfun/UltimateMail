// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    private val cleaner: AttachmentFileCleaner,
    private val bodies: BodyDownloader,
    private val depth: SyncDepthLog,
    private val status: SyncStatusStore,
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
        val stages = SyncStages.plan(account.offlineWindowDays)
        val reached = depth.get(account.id)
        // Until the account is fully brought down, the regular pass only needs the first phase:
        // new mail is new, and the older headers come in the deeper phases below.
        val firstWindow = account.offlineWindowDays
            .takeIf { reached >= SyncStages.target(account.offlineWindowDays) }
            ?: stages.first()
        val head = pullAll(account, session, assigner, firstWindow)
        head.ended?.let { return failed(it) }
        // Pruning, expunges and moves have dropped rows; drop the files that belonged to them.
        cleaner.clean(account.id)
        // Headers first, so the lists are complete before the (slow, budgeted) body downloads.
        val downloaded = bodies.run(session, account)
        if (downloaded is BodyOutcome.Stopped) return failed(downloaded.failure)
        if (head.failure != null) return failed(head.failure)
        // A label that stayed behind is tried again by the next sync; until then the account is
        // not marked as brought down, or its older mail would be skipped by the deeper phases.
        if (head.behind > 0) return AccountSyncResult.Synced(head.counts)
        depth.put(account.id, maxOf(reached, stages.first()))
        val deeper = backfill(account, session, assigner, stages)
        deeper.ended?.let { return failed(it) }
        return AccountSyncResult.Synced(head.counts + deeper.counts)
    }

    /**
     * What one pass over the folders did: [ended] stops the whole run, [failure] fails the sync
     * (a folder the user reads every day did not come), [behind] counts the Gmail labels that
     * did not come and wait for the next sync without failing this one.
     */
    private class Pass(
        val counts: SyncCounts = SyncCounts(),
        val failure: MailResult.Failure? = null,
        val ended: MailResult.Failure? = null,
        val behind: Int = 0
    )

    /** The regular pull of every synced folder, pushing queued changes before each one. */
    private suspend fun pullAll(
        account: AccountEntity,
        session: MailSession,
        assigner: ThreadAssigner,
        windowDays: Int?
    ): Pass {
        var counts = SyncCounts()
        var firstFailure: MailResult.Failure? = null
        var behind = 0
        val ordered = syncOrder(account.id)
        for ((index, folder) in ordered.withIndex()) {
            status.set(account.id, AccountSyncState.SyncingFolders(index, ordered.size))
            // Push before pull, folder by folder, so changes made meanwhile go out too.
            queue.drain(account.id)
            when (val outcome = puller.pull(session, account, folder, assigner, windowDays)) {
                is PullOutcome.Pulled -> counts += outcome.counts

                is PullOutcome.Failed -> {
                    // The connection is gone or the login no longer works: the other folders
                    // would fail the same way. Anything else only concerns this folder.
                    if (outcome.failure.endsRun) return Pass(ended = outcome.failure)
                    if (folder.isLabel) behind++ else firstFailure = firstFailure ?: outcome.failure
                }
            }
        }
        return Pass(counts, firstFailure, behind = behind)
    }

    /**
     * The deeper phases: older headers, phase by phase, each remembered once every folder has
     * it. A folder that fails only holds its phase back (it is tried again by the next sync);
     * the lists already work, so that is not reported as a failed sync.
     */
    // A hard failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun backfill(
        account: AccountEntity,
        session: MailSession,
        assigner: ThreadAssigner,
        stages: List<Int>
    ): Pass {
        var counts = SyncCounts()
        for (stage in stages.filter { it > depth.get(account.id) }) {
            val done = backfillStage(account, session, assigner, stage)
            counts += done.counts
            if (done.ended != null) return Pass(counts, ended = done.ended)
            if (done.failure != null) break
            depth.put(account.id, stage)
            val downloaded = bodies.run(session, account)
            if (downloaded is BodyOutcome.Stopped) return Pass(counts, ended = downloaded.failure)
        }
        return Pass(counts)
    }

    /** One deeper phase over every synced folder; [Pass.failure] is set when one stayed behind. */
    private suspend fun backfillStage(
        account: AccountEntity,
        session: MailSession,
        assigner: ThreadAssigner,
        stage: Int
    ): Pass {
        var counts = SyncCounts()
        var behind: MailResult.Failure? = null
        val ordered = syncOrder(account.id)
        for ((index, folder) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            status.set(account.id, AccountSyncState.SyncingFolders(index, ordered.size))
            queue.drain(account.id)
            when (val outcome = puller.backfill(session, account, folder, assigner, stage)) {
                is PullOutcome.Pulled -> counts += outcome.counts

                is PullOutcome.Failed -> {
                    if (outcome.failure.endsRun) return Pass(counts, ended = outcome.failure)
                    behind = behind ?: outcome.failure
                }
            }
        }
        return Pass(counts, behind)
    }

    /**
     * The synced folders. While the Inbox has never been synced (the first sync of an account)
     * the Inbox goes first, then the special folders (Sent, Trash...), then the rest: on a
     * mailbox with hundreds of labels the Inbox is there within seconds instead of after the
     * last label. Afterwards the order stays the one of the folder paths, which the handling of
     * a moved message after a UIDVALIDITY reset relies on (its destination is read first).
     */
    private suspend fun syncOrder(accountId: Long): List<FolderEntity> {
        val all = folders.syncable(accountId)
        val inboxNeverSynced = all.any { it.role == FolderRole.INBOX && it.uidNext == null }
        if (!inboxNeverSynced) return all
        return all.sortedBy {
            when (it.role) {
                FolderRole.INBOX -> 0
                FolderRole.OTHER -> 2
                else -> 1
            }
        }
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
}
