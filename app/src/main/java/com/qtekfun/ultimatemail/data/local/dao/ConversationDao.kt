// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** A conversation row: its newest message plus the counters the list shows (RF-03). */
data class ConversationSummary(
    @Embedded val latest: MessageEntity,
    val messageCount: Int,
    val unreadCount: Int
)

/*
 * A message with a change waiting that takes it out of its folder (a move or a delete the user made
 * and the server does not have yet) is hidden from the lists at once, and shown again if the
 * change is cancelled (undo) or refused for good. One constant per table alias, as Room needs
 * constant strings.
 */
private const val LEAVES_M =
    "EXISTS (SELECT 1 FROM pending_operation p WHERE p.accountId = m.accountId " +
        "AND p.folderPath = m.folderPath AND p.uid = m.uid " +
        "AND p.type IN ('MOVE', 'DELETE') AND p.failed = 0)"

private const val LEAVES_T =
    "EXISTS (SELECT 1 FROM pending_operation p WHERE p.accountId = t.accountId " +
        "AND p.folderPath = t.folderPath AND p.uid = t.uid " +
        "AND p.type IN ('MOVE', 'DELETE') AND p.failed = 0)"

/** Read-only queries that group messages into conversations and search them (RF-03, RF-09). */
@Dao
interface ConversationDao {
    /** Conversations of one folder, newest first. */
    @Query(
        "SELECT m.*, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId " +
            "AND NOT " + LEAVES_T + ") AS messageCount, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
            "AND NOT " + LEAVES_T + ") AS unreadCount " +
            "FROM message m WHERE m.accountId = :accountId AND m.folderPath = :folderPath " +
            "AND NOT " + LEAVES_M + " " +
            "AND m.id = (SELECT t.id FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId " +
            "AND NOT " + LEAVES_T + " ORDER BY t.sentAt DESC, t.id DESC LIMIT 1) " +
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
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId " +
            "AND NOT " + LEAVES_T + ") AS messageCount, " +
            "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
            "AND NOT " + LEAVES_T + ") AS unreadCount " +
            "FROM message m JOIN folder f ON f.accountId = m.accountId AND f.path = m.folderPath " +
            "WHERE f.role = 'INBOX' AND NOT " + LEAVES_M + " " +
            "AND m.id = (SELECT t.id FROM message t WHERE t.accountId = m.accountId " +
            "AND t.folderPath = m.folderPath AND t.threadId = m.threadId " +
            "AND NOT " + LEAVES_T + " ORDER BY t.sentAt DESC, t.id DESC LIMIT 1) " +
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
}
