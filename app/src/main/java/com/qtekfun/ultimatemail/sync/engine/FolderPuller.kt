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
    suspend fun pull(
        session: MailSession,
        account: AccountEntity,
        folder: FolderEntity,
        assigner: ThreadAssigner
    ): PullOutcome {
        val status = session.folderStatus(folder.path).valueOr { return PullOutcome.Failed(it) }
        val previous = resetIfInvalidated(account.id, folder, status, assigner)
        val knownNext = previous.uidNext
        val untouched = knownNext != null && status.highestModSeq != null &&
            knownNext == status.uidNext && status.highestModSeq == previous.highestModSeq
        var counts = SyncCounts()
        if (!untouched) {
            val range = (knownNext ?: FIRST_UID) until status.uidNext
            if (!range.isEmpty()) {
                counts += fetchNew(session, account, folder.path, range, assigner)
                    .valueOr { return PullOutcome.Failed(it) }
            }
            if (knownNext != null) {
                counts += reconcile(session, account.id, folder.path, knownNext)
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
        pending.settle(account.id, folder.path)
        return PullOutcome.Pulled(counts)
    }

    /** UIDVALIDITY changed: every UID we hold is meaningless, but queued changes must survive. */
    private suspend fun resetIfInvalidated(
        accountId: Long,
        folder: FolderEntity,
        status: FolderStatus,
        assigner: ThreadAssigner
    ): FolderEntity {
        val stored = folder.uidValidity
        if (stored == null || stored == status.uidValidity) return folder
        pending.detach(accountId, folder.path)
        messages.deleteServerMessages(accountId, folder.path)
        // Saved last: if the run dies before this, the next one repeats the idempotent steps.
        folders.setSyncState(accountId, folder.path, status.uidValidity, null, null)
        assigner.reset()
        return folder.copy(uidValidity = status.uidValidity, uidNext = null, highestModSeq = null)
    }

    private suspend fun fetchNew(
        session: MailSession,
        account: AccountEntity,
        path: String,
        uids: LongRange,
        assigner: ThreadAssigner
    ): MailResult<SyncCounts> {
        val cutoff = windowStart(account)
        var added = 0
        var top = uids.last
        while (top >= uids.first) {
            val bottom = maxOf(uids.first, top - BATCH + 1)
            val headers = when (val result = session.fetchHeaders(path, UidRange(bottom, top))) {
                is MailResult.Success -> result.value.filter { !it.flags.deleted }
                is MailResult.Failure -> return result
            }
            val inWindow = headers.filter { cutoff == null || it.sentAt(clock.instant()) >= cutoff }
            added += store(account.id, path, inWindow, bottom, top, assigner)
            // UIDs grow with arrival: a whole batch older than the window means everything
            // below it is older too, so there is no point fetching on.
            if (headers.isNotEmpty() && inWindow.isEmpty()) break
            top = bottom - 1
        }
        return MailResult.Success(SyncCounts(added = added))
    }

    private suspend fun store(
        accountId: Long,
        path: String,
        headers: List<MessageHeader>,
        bottom: Long,
        top: Long,
        assigner: ThreadAssigner
    ): Int {
        val stored = messages.syncRows(accountId, path, bottom, top).map { it.uid }.toSet()
        val fresh = headers.filter { it.uid !in stored }
        if (fresh.isEmpty()) return 0
        val now = clock.instant()
        val threads = assigner.assign(fresh.map { it.toThreadMessage(accountId, path, now) })
        messages.insertNew(
            fresh.map { header ->
                val ref = MessageRef(accountId, path, header.uid)
                header.toEntity(accountId, path, threads.getValue(ref), now)
            }
        )
        return fresh.size
    }

    /** Takes flags and deletions of messages below [below] (the ones stored before this pull). */
    private suspend fun reconcile(
        session: MailSession,
        accountId: Long,
        path: String,
        below: Long
    ): MailResult<SyncCounts> {
        var counts = SyncCounts()
        for (chunk in messages.serverUids(accountId, path).filter { it < below }.chunked(BATCH)) {
            val first = chunk.first()
            val last = chunk.last()
            val onServer = when (val result = session.fetchHeaders(path, UidRange(first, last))) {
                is MailResult.Success -> result.value.filter { !it.flags.deleted }.associateBy { it.uid }
                is MailResult.Failure -> return result
            }
            val rows = messages.syncRows(accountId, path, first, last)
            val gone = rows.filter { it.uid !in onServer }.map { it.uid }
            if (gone.isNotEmpty()) messages.deleteUids(accountId, path, gone)
            val changed = rows.filter { row -> onServer[row.uid]?.let { differs(row, it) } == true }
            changed.forEach { row ->
                val header = onServer.getValue(row.uid)
                messages.updateServerState(
                    row.id,
                    header.flags.seen,
                    header.flags.flagged,
                    header.flags.answered,
                    header.flags.draft,
                    header.gmail?.labels.orEmpty()
                )
            }
            counts += SyncCounts(updated = changed.size, removed = gone.size)
        }
        return MailResult.Success(counts)
    }

    private fun differs(row: MessageSyncRow, header: MessageHeader) =
        row.seen != header.flags.seen || row.flagged != header.flags.flagged ||
            row.answered != header.flags.answered || row.draft != header.flags.draft ||
            row.labels != header.gmail?.labels.orEmpty()

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
