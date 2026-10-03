// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.MessageSyncRow
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.domain.mail.FolderStatus
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.mail.UidRange
import com.qtekfun.ultimatemail.domain.thread.MessageRef
import com.qtekfun.ultimatemail.sync.conflict.FlagResolver
import com.qtekfun.ultimatemail.sync.conflict.Flags
import com.qtekfun.ultimatemail.sync.conflict.PendingFlagOperation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

internal sealed interface PullOutcome {
    data class Pulled(val counts: SyncCounts) : PullOutcome

    data class Failed(val failure: MailResult.Failure) : PullOutcome
}

/**
 * Pulls one folder from the server into Room (RF-10): UIDVALIDITY, UIDNEXT and HIGHESTMODSEQ say
 * what changed; new headers inside the offline window come in batches, newest first; flags and
 * deletions of known messages are reconciled. The folder's sync state is saved only after the
 * whole pull worked, so a pull cut short by the network leaves Room consistent (every batch is
 * stored on its own) and the next run simply does it again, skipping what is already stored.
 */
internal class FolderPuller @Inject constructor(
    private val messages: MessageDao,
    private val folders: FolderDao,
    private val pending: PendingReconciler,
    private val clock: Clock
) {
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    suspend fun pull(
        session: MailSession,
        account: AccountEntity,
        folder: FolderEntity,
        assigner: ThreadAssigner
    ): PullOutcome {
        val status = session.folderStatus(folder.path).valueOr { return PullOutcome.Failed(it) }
        val reset = pending.resetIfInvalidated(folder, status.uidValidity)
        val previous = if (reset) startAnew(account.id, folder, status, assigner) else folder
        val run = Run(
            account,
            folder.path,
            assigner,
            pending.flagOperations(account.id, folder.path)
        )
        val knownNext = previous.uidNext
        val untouched = knownNext != null && status.highestModSeq != null &&
            knownNext == status.uidNext && status.highestModSeq == previous.highestModSeq
        var counts = SyncCounts()
        if (!untouched) {
            val range = (knownNext ?: FIRST_UID) until status.uidNext
            if (!range.isEmpty()) {
                counts += fetchNew(session, run, range)
                    .valueOr { return PullOutcome.Failed(it) }
            }
            if (knownNext != null) {
                counts += reconcile(session, run, knownNext)
                    .valueOr { return PullOutcome.Failed(it) }
            }
        }
        folders.setSyncState(
            account.id,
            folder.path,
            status.uidValidity,
            status.uidNext,
            status.highestModSeq
        )
        pending.settle(account.id, folder.path, afterReset = reset)
        return PullOutcome.Pulled(counts)
    }

    /** The folder was invalidated: every UID we hold is meaningless, so start from nothing. */
    private suspend fun startAnew(
        accountId: Long,
        folder: FolderEntity,
        status: FolderStatus,
        assigner: ThreadAssigner
    ): FolderEntity {
        // Saved last: if the run dies before this, the next one repeats the idempotent steps.
        folders.setSyncState(accountId, folder.path, status.uidValidity, null, null)
        assigner.reset()
        return folder.copy(uidValidity = status.uidValidity, uidNext = null, highestModSeq = null)
    }

    /** What one folder pull works with, so the helpers do not each take it piecemeal. */
    private class Run(
        val account: AccountEntity,
        val path: String,
        val assigner: ThreadAssigner,
        val flagOperations: Map<Long, List<PendingFlagOperation>>
    )

    private suspend fun fetchNew(
        session: MailSession,
        run: Run,
        uids: LongRange
    ): MailResult<SyncCounts> {
        val path = run.path
        val cutoff = windowStart(run.account)
        var added = 0
        var top = uids.last
        while (top >= uids.first) {
            val bottom = maxOf(uids.first, top - BATCH + 1)
            val headers = when (val result = session.fetchHeaders(path, UidRange(bottom, top))) {
                is MailResult.Success -> result.value.filter { !it.flags.deleted }
                is MailResult.Failure -> return result
            }
            val inWindow = headers.filter { cutoff == null || it.sentAt(clock.instant()) >= cutoff }
            added += store(run, inWindow, bottom..top)
            // UIDs grow with arrival: a whole batch older than the window means everything
            // below it is older too, so there is no point fetching on.
            if (headers.isNotEmpty() && inWindow.isEmpty()) break
            top = bottom - 1
        }
        return MailResult.Success(SyncCounts(added = added))
    }

    private suspend fun store(run: Run, headers: List<MessageHeader>, uids: LongRange): Int {
        val accountId = run.account.id
        val path = run.path
        val stored = messages.syncRows(accountId, path, uids.first, uids.last).map {
            it.uid
        }.toSet()
        val fresh = headers.filter { it.uid !in stored }
        if (fresh.isEmpty()) return 0
        val now = clock.instant()
        val threads = run.assigner.assign(fresh.map { it.toThreadMessage(accountId, path, now) })
        messages.insertNew(
            fresh.map { header ->
                val ref = MessageRef(accountId, path, header.uid)
                val flags = effectiveFlags(header, run.flagOperations)
                header.toEntity(accountId, path, threads.getValue(ref), now).copy(
                    seen = flags.seen,
                    flagged = flags.flagged,
                    answered = flags.answered
                )
            }
        )
        return fresh.size
    }

    /** Takes flags and deletions of messages below [below] (the ones stored before this pull). */
    private suspend fun reconcile(
        session: MailSession,
        run: Run,
        below: Long
    ): MailResult<SyncCounts> {
        val accountId = run.account.id
        val path = run.path
        var counts = SyncCounts()
        for (chunk in messages.serverUids(accountId, path).filter { it < below }.chunked(BATCH)) {
            val first = chunk.first()
            val last = chunk.last()
            val onServer = when (val result = session.fetchHeaders(path, UidRange(first, last))) {
                is MailResult.Success -> result.value.filter {
                    !it.flags.deleted
                }.associateBy { it.uid }

                is MailResult.Failure -> return result
            }
            val rows = messages.syncRows(accountId, path, first, last)
            val gone = rows.filter { it.uid !in onServer }.map { it.uid }
            if (gone.isNotEmpty()) messages.deleteUids(accountId, path, gone)
            var updated = 0
            for (row in rows) {
                val header = onServer[row.uid] ?: continue
                val flags = effectiveFlags(header, run.flagOperations)
                val labels = header.gmail?.labels.orEmpty()
                if (differs(row, flags, header, labels)) {
                    messages.updateServerState(
                        row.id,
                        flags.seen,
                        flags.flagged,
                        flags.answered,
                        header.flags.draft,
                        labels
                    )
                    updated++
                }
            }
            counts += SyncCounts(updated = updated, removed = gone.size)
        }
        return MailResult.Success(counts)
    }

    /**
     * The flags the message should show: the server's, with the user's queued changes over them
     * (rule 1). Operations the server already reflects are completed.
     */
    private suspend fun effectiveFlags(
        header: MessageHeader,
        flagOperations: Map<Long, List<PendingFlagOperation>>
    ): Flags {
        val server = Flags(header.flags.seen, header.flags.flagged, header.flags.answered)
        val queued = flagOperations[header.uid] ?: return server
        val resolution = FlagResolver.resolve(server, queued)
        pending.complete(resolution)
        return resolution.merged
    }

    private fun differs(
        row: MessageSyncRow,
        flags: Flags,
        header: MessageHeader,
        labels: List<String>
    ) = row.seen != flags.seen || row.flagged != flags.flagged || row.answered != flags.answered ||
        row.draft != header.flags.draft || row.labels != labels

    private fun windowStart(account: AccountEntity): Instant? = account.offlineWindowDays
        ?.let { clock.instant().minus(Duration.ofDays(it.toLong())) }

    private companion object {
        const val FIRST_UID = 1L

        /** Headers fetched and stored at a time: keeps memory bounded on huge folders. */
        const val BATCH = 200
    }
}

internal inline fun <T> MailResult<T>.valueOr(onFailure: (MailResult.Failure) -> Nothing): T =
    when (this) {
        is MailResult.Success -> value
        is MailResult.Failure -> onFailure(this)
    }
