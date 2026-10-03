// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** Inserts new messages and replaces the ones already stored with the same server identity. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<MessageEntity>)

    @Query(
        "SELECT * FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid = :uid"
    )
    suspend fun get(accountId: Long, folderPath: String, uid: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query(
        "SELECT * FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND threadId = :threadId ORDER BY sentAt, id"
    )
    fun observeThread(
        accountId: Long,
        folderPath: String,
        threadId: String
    ): Flow<List<MessageEntity>>

    @Query(
        "UPDATE message SET seen = :seen, flagged = :flagged, answered = :answered, " +
            "pendingSync = :pendingSync WHERE id = :id"
    )
    suspend fun setFlags(
        id: Long,
        seen: Boolean,
        flagged: Boolean,
        answered: Boolean,
        pendingSync: Boolean
    )

    @Query(
        "UPDATE message SET bodyText = :text, bodyHtml = :html " +
            "WHERE accountId = :accountId AND folderPath = :folderPath AND uid = :uid"
    )
    suspend fun setBody(
        accountId: Long,
        folderPath: String,
        uid: Long,
        text: String?,
        html: String?
    )

    @Query(
        "DELETE FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid = :uid"
    )
    suspend fun delete(accountId: Long, folderPath: String, uid: Long)

    /** Drops headers older than the offline window (RF-10). */
    @Query(
        "DELETE FROM message WHERE accountId = :accountId AND sentAt < :cutoffMillis " +
            "AND pendingSync = 0"
    )
    suspend fun deleteOlderThan(accountId: Long, cutoffMillis: Long)
}
