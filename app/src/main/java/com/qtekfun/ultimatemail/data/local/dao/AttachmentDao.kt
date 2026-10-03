// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    fun observe(messageId: Long): Flow<List<AttachmentEntity>>

    /** The attachments of several messages at once, for a whole conversation. */
    @Query("SELECT * FROM attachment WHERE messageId IN (:messageIds) ORDER BY messageId, id")
    fun observeForMessages(messageIds: List<Long>): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    suspend fun listFor(messageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun get(id: Long): AttachmentEntity?

    @Query("UPDATE attachment SET state = :state, localPath = :localPath WHERE id = :id")
    suspend fun setState(id: Long, state: AttachmentState, localPath: String?)

    /** Ids of every attachment row of an account: the files of any other id are orphans. */
    @Query(
        "SELECT a.id FROM attachment a JOIN message m ON m.id = a.messageId " +
            "WHERE m.accountId = :accountId"
    )
    suspend fun idsOfAccount(accountId: Long): List<Long>
}
