// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
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
}
