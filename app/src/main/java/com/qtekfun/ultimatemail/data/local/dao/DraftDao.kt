// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.OutgoingAttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.DraftState
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Storage of drafts and their attachments (RF-07); the rules live in `domain.compose`. */
// A DAO is a flat list of queries, one function each; splitting it would only scatter them.
@Suppress("TooManyFunctions")
@Dao
interface DraftDao {
    @Insert
    suspend fun insert(draft: DraftEntity): Long

    @Update
    suspend fun update(draft: DraftEntity)

    @Query("SELECT * FROM draft WHERE id = :id")
    suspend fun get(id: Long): DraftEntity?

    @Query("SELECT * FROM draft WHERE id = :id")
    fun observe(id: Long): Flow<DraftEntity?>

    @Query("SELECT * FROM draft WHERE `key` = :key")
    suspend fun getByKey(key: String): DraftEntity?

    /** The draft that remembers [serverMessageId] as its copy in the server's Drafts folder. */
    @Query(
        "SELECT * FROM draft WHERE accountId = :accountId AND serverMessageId = :serverMessageId"
    )
    suspend fun getByServerMessageId(accountId: Long, serverMessageId: String): DraftEntity?

    @Query(DRAFTS_BY_STATE_SQL)
    fun observeByState(accountId: Long, state: DraftState): Flow<List<DraftEntity>>

    @Query(DRAFTS_ALL_SQL)
    fun observeAllByState(state: DraftState): Flow<List<DraftEntity>>

    @Query("SELECT COUNT(*) FROM draft WHERE accountId = :accountId AND state = :state")
    fun observeCount(accountId: Long, state: DraftState): Flow<Int>

    @Query("SELECT COUNT(*) FROM draft WHERE state = :state")
    fun observeCountAll(state: DraftState): Flow<Int>

    /** The ids of the account's drafts, to remove their files when the account goes. */
    @Query("SELECT id FROM draft WHERE accountId = :accountId")
    suspend fun idsOf(accountId: Long): List<Long>

    @Query("DELETE FROM draft WHERE id = :id")
    suspend fun delete(id: Long)

    /** Records the copy this device put on the server; clears `dirty` if nothing changed since. */
    @Query(
        "UPDATE draft SET serverMessageId = :serverMessageId, " +
            "dirty = CASE WHEN revision = :revision THEN 0 ELSE dirty END WHERE id = :id"
    )
    suspend fun markUploaded(id: Long, serverMessageId: String, revision: Int)

    /** The key changes when a conflict forks the local version into a draft of its own. */
    @Query("UPDATE draft SET `key` = :key WHERE id = :id")
    suspend fun rekey(id: Long, key: String)

    @Query("UPDATE draft SET smtpAcceptedAt = :at WHERE id = :id AND smtpAcceptedAt IS NULL")
    suspend fun markSmtpAccepted(id: Long, at: Instant)

    @Insert
    suspend fun insertAttachment(attachment: OutgoingAttachmentEntity): Long

    @Query("SELECT * FROM outgoing_attachment WHERE draftId = :draftId ORDER BY id")
    suspend fun attachments(draftId: Long): List<OutgoingAttachmentEntity>

    @Query("SELECT * FROM outgoing_attachment WHERE draftId = :draftId ORDER BY id")
    fun observeAttachments(draftId: Long): Flow<List<OutgoingAttachmentEntity>>

    @Query("SELECT * FROM outgoing_attachment WHERE id = :id")
    suspend fun attachment(id: Long): OutgoingAttachmentEntity?

    @Query("DELETE FROM outgoing_attachment WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("SELECT COALESCE(SUM(size), 0) FROM outgoing_attachment WHERE draftId = :draftId")
    suspend fun attachmentBytes(draftId: Long): Long
}

/** The drafts of one account in a state; a constant so the query plan can be tested. */
internal const val DRAFTS_BY_STATE_SQL =
    "SELECT * FROM draft WHERE accountId = :accountId AND state = :state " +
        "ORDER BY updatedAt DESC, id DESC"

/** The drafts of every account in a state; a constant so the query plan can be tested. */
internal const val DRAFTS_ALL_SQL =
    "SELECT * FROM draft WHERE state = :state ORDER BY updatedAt DESC, id DESC"
