// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.domain.picker.GmailLabels
import kotlinx.coroutines.flow.Flow

/**
 * A message with a queued move or delete, or a queued removal of the label of the folder it is listed in
 * (Gmail's Inbox label for the Inbox), has already left the folder as far as the user is
 * concerned (RF-06): the lists drop it at once and the sync engine deletes the row when the
 * server confirms. The queue is the single source of truth, so undoing the move (the queue
 * cancels it) brings the message back with no extra state; a refused operation (failed) shows
 * it again; a label added again after its removal (the undo of an archive) counts as never removed.
 * [VISIBLE_T] is the test for a message of a conversation (alias `t`); the latest
 * visible one stands for the conversation, so a conversation with every message gone is dropped.
 */
internal const val VISIBLE_T = "NOT EXISTS (SELECT 1 FROM pending_operation p " +
    "WHERE p.accountId = t.accountId AND p.folderPath = t.folderPath AND p.uid = t.uid " +
    "AND p.failed = 0 AND (p.type IN ('MOVE', 'DELETE') OR (p.type = 'REMOVE_LABEL' AND " +
    "(p.payload = t.folderPath OR (p.payload = '${GmailLabels.INBOX}' AND EXISTS " +
    "(SELECT 1 FROM folder f WHERE f.accountId = t.accountId AND f.path = t.folderPath " +
    "AND f.role = 'INBOX'))) AND NOT EXISTS (SELECT 1 FROM pending_operation a " +
    "WHERE a.accountId = p.accountId AND a.folderPath = p.folderPath AND a.uid = p.uid " +
    "AND a.type = 'ADD_LABEL' AND a.payload = p.payload AND a.id > p.id))))"

/** [VISIBLE_T] for the alias `m`. Room needs constant strings, so there is one per alias. */
internal const val VISIBLE_M = "NOT EXISTS (SELECT 1 FROM pending_operation p " +
    "WHERE p.accountId = m.accountId AND p.folderPath = m.folderPath AND p.uid = m.uid " +
    "AND p.failed = 0 AND (p.type IN ('MOVE', 'DELETE') OR (p.type = 'REMOVE_LABEL' AND " +
    "(p.payload = m.folderPath OR (p.payload = '${GmailLabels.INBOX}' AND EXISTS " +
    "(SELECT 1 FROM folder f WHERE f.accountId = m.accountId AND f.path = m.folderPath " +
    "AND f.role = 'INBOX'))) AND NOT EXISTS (SELECT 1 FROM pending_operation a " +
    "WHERE a.accountId = p.accountId AND a.folderPath = p.folderPath AND a.uid = p.uid " +
    "AND a.type = 'ADD_LABEL' AND a.payload = p.payload AND a.id > p.id))))"

/** A conversation row: its newest message plus the counters the list shows (RF-03). */
data class ConversationSummary(
    @Embedded val latest: MessageEntity,
    val messageCount: Int,
    val unreadCount: Int
)

/** Read-only queries that group messages into conversations and search them (RF-03, RF-09). */
@Dao
interface ConversationDao {
    /** Conversations of one folder, newest first. */
    @Query(
        "SELECT m.*, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T) " +
            "AS messageCount, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
            "AND $VISIBLE_T) AS unreadCount " +
            "FROM message m WHERE m.accountId = :accountId AND m.folderPath = :folderPath " +
            "AND " + VISIBLE_M + " " +
            "AND m.id = (SELECT t.id FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T " +
            "ORDER BY t.sentAt DESC, t.id DESC LIMIT 1) " +
            "ORDER BY m.sentAt DESC, m.id DESC LIMIT :limit"
    )
    fun observeConversations(
        accountId: Long,
        folderPath: String,
        limit: Int
    ): Flow<List<ConversationSummary>>

    /** The unified inbox: conversations of every account's INBOX folder, newest first. */
    @Query(
        "SELECT m.*, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T) " +
            "AS messageCount, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
            "AND $VISIBLE_T) AS unreadCount " +
            "FROM message m JOIN folder f ON f.accountId = m.accountId AND f.path = m.folderPath " +
            "WHERE f.role = 'INBOX' AND " + VISIBLE_M + " " +
            "AND m.id = (SELECT t.id FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T " +
            "ORDER BY t.sentAt DESC, t.id DESC LIMIT 1) " +
            "ORDER BY m.sentAt DESC, m.id DESC LIMIT :limit"
    )
    fun observeUnifiedInbox(limit: Int): Flow<List<ConversationSummary>>

    /** Full-text search over subject, sender and cached bodies; [query] is an FTS4 expression. */
    @Query(
        "SELECT message.* FROM message JOIN message_fts ON message.id = message_fts.rowid " +
            "WHERE message_fts MATCH :query " +
            "AND (:accountId IS NULL OR message.accountId = :accountId) " +
            "ORDER BY message.sentAt DESC, message.id DESC LIMIT :limit"
    )
    suspend fun search(query: String, accountId: Long?, limit: Int): List<MessageEntity>

    /**
     * The search screen's query (T20), already assembled by [SearchSql]: the hits as rows with
     * their conversation counters, re-emitted when messages, folders or queued operations
     * change, so a hit read or archived meanwhile updates in place.
     */
    @RawQuery(
        observedEntities = [
            MessageEntity::class,
            FolderEntity::class,
            PendingOperationEntity::class
        ]
    )
    fun observeSearch(query: RoomRawQuery): Flow<List<ConversationSummary>>

    /** The rows for [ids] (hits found on the server), newest first. */
    @Query(
        "SELECT m.*, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T) " +
            "AS messageCount, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
            "AND $VISIBLE_T) AS unreadCount " +
            "FROM message m WHERE m.id IN (:ids) AND " + VISIBLE_M + " " +
            "ORDER BY m.sentAt DESC, m.id DESC"
    )
    fun observeByIds(ids: List<Long>): Flow<List<ConversationSummary>>
}
