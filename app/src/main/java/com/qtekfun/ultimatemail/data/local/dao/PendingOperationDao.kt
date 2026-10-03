// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Storage of the operation queue; ordering, merging and retries live in the queue (T08). */
// A DAO is a flat list of queries, one function each; splitting it would only scatter them.
@Suppress("TooManyFunctions")
@Dao
interface PendingOperationDao {
    @Insert
    suspend fun enqueue(operation: PendingOperationEntity): Long

    /** Every operation of the account, failed ones included, in the order they were queued. */
    @Query("SELECT * FROM pending_operation WHERE accountId = :accountId ORDER BY id")
    suspend fun all(accountId: Long): List<PendingOperationEntity>

    @Query(
        "SELECT * FROM pending_operation WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid = :uid ORDER BY id"
    )
    suspend fun forMessage(
        accountId: Long,
        folderPath: String,
        uid: Long
    ): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operation WHERE id = :id")
    suspend fun get(id: Long): PendingOperationEntity?

    /**
     * Replaces the data of an operation that was never handed to the server (merging repeated
     * changes). Returns 0 if the operation is gone or was started meanwhile, so a merge can never
     * rewrite something the server may already have received.
     */
    @Query("UPDATE pending_operation SET payload = :payload WHERE id = :id AND startedAt IS NULL")
    suspend fun replacePayload(id: Long, payload: String): Int

    /** Like [delete] but only for an operation never handed to the server; returns the rows removed. */
    @Query("DELETE FROM pending_operation WHERE id = :id AND startedAt IS NULL")
    suspend fun deleteUnstarted(id: Long): Int

    @Query("UPDATE pending_operation SET startedAt = :at WHERE id = :id")
    suspend fun markStarted(id: Long, at: Instant)

    @Query(
        "UPDATE pending_operation SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, " +
            "lastError = :lastError WHERE id = :id"
    )
    suspend fun markRetryLater(id: Long, attempts: Int, nextAttemptAt: Instant, lastError: String)

    @Query("UPDATE pending_operation SET failed = 1, lastError = :lastError WHERE id = :id")
    suspend fun markFailed(id: Long, lastError: String)

    /** Gives a failed operation a fresh start, as if just queued. */
    @Query(
        "UPDATE pending_operation SET failed = 0, attempts = 0, nextAttemptAt = :nextAttemptAt, " +
            "lastError = NULL WHERE id = :id AND failed = 1"
    )
    suspend fun resetFailed(id: Long, nextAttemptAt: Instant)

    @Query("DELETE FROM pending_operation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_operation WHERE accountId = :accountId")
    fun observeCount(accountId: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM pending_operation WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid = :uid"
    )
    fun observeMessageCount(accountId: Long, folderPath: String, uid: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operation WHERE accountId = :accountId AND failed = 1")
    fun observeFailedCount(accountId: Long): Flow<Int>

    /**
     * First step of a UIDVALIDITY reset, after the planner discarded the operations it could not
     * keep: operations on a stored message now point at that row by a negative uid, which no
     * server uses, until [rebase] gives them the new UID.
     */
    @Query(
        "UPDATE pending_operation SET uid = (SELECT -m.id FROM message m " +
            "WHERE m.accountId = pending_operation.accountId " +
            "AND m.folderPath = pending_operation.folderPath AND m.uid = pending_operation.uid) " +
            "WHERE accountId = :accountId AND folderPath = :folderPath AND uid > 0 " +
            "AND type NOT IN ('SEND', 'SAVE_DRAFT') AND EXISTS (SELECT 1 FROM message m " +
            "WHERE m.accountId = pending_operation.accountId " +
            "AND m.folderPath = pending_operation.folderPath AND m.uid = pending_operation.uid)"
    )
    suspend fun detachFromServerUids(accountId: Long, folderPath: String)

    /** Points an operation at the message's new UID. */
    @Query("UPDATE pending_operation SET uid = :uid WHERE id = :id")
    suspend fun rebase(id: Long, uid: Long)

    @Query(
        "SELECT COUNT(*) FROM pending_operation WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid = :uid"
    )
    suspend fun countForMessage(accountId: Long, folderPath: String, uid: Long): Int

    /** The SEND operations of the account, oldest first: the state of its outbox (RF-07). */
    @Query(OUTBOX_SQL)
    fun observeSends(accountId: Long): Flow<List<PendingOperationEntity>>

    @Query(OUTBOX_ALL_SQL)
    fun observeAllSends(): Flow<List<PendingOperationEntity>>

    /** Drops the operations of [type] that belong to one draft (they use `uid` = draft id). */
    @Query(
        "DELETE FROM pending_operation WHERE accountId = :accountId AND folderPath = '' " +
            "AND uid = :draftId AND type = :type"
    )
    suspend fun deleteForDraft(accountId: Long, draftId: Long, type: OperationType)

    @Query(
        "SELECT * FROM pending_operation WHERE accountId = :accountId AND folderPath = '' " +
            "AND uid = :draftId AND type = :type ORDER BY id"
    )
    suspend fun forDraft(
        accountId: Long,
        draftId: Long,
        type: OperationType
    ): List<PendingOperationEntity>
}

/** The outbox of one account; a constant so the query plan can be tested. */
internal const val OUTBOX_SQL =
    "SELECT * FROM pending_operation WHERE accountId = :accountId AND type = 'SEND' ORDER BY id"

/** The outbox of every account; a constant so the query plan can be tested. */
internal const val OUTBOX_ALL_SQL =
    "SELECT * FROM pending_operation WHERE type = 'SEND' ORDER BY id"
