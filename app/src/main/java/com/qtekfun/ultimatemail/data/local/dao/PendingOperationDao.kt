// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

/** Storage of the operation queue; ordering, merging and retries live in the queue (T08). */
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

    /** Replaces the data of an operation that was never sent (merging repeated changes). */
    @Query("UPDATE pending_operation SET payload = :payload WHERE id = :id")
    suspend fun replacePayload(id: Long, payload: String)

    @Query("DELETE FROM pending_operation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_operation WHERE accountId = :accountId")
    fun observeCount(accountId: Long): Flow<Int>
}
