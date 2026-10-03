// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// A DAO is a flat list of queries, one function each; splitting it would only scatter them.
@Suppress("TooManyFunctions")
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

    @Query(THREAD_SQL)
    fun observeThread(
        accountId: Long,
        folderPath: String,
        threadId: String
    ): Flow<List<MessageEntity>>

    /** The messages of one conversation in one folder, oldest first. */
    @Query(THREAD_SQL)
    suspend fun thread(accountId: Long, folderPath: String, threadId: String): List<MessageEntity>

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

    /**
     * Drops server headers older than the offline window (RF-10). Messages with a change still
     * waiting for the server (flagged as pending or named by a queued operation, as a move is),
     * and local-only rows (uid 0 or below), are kept.
     */
    @Query(
        "DELETE FROM message WHERE accountId = :accountId AND sentAt < :cutoffMillis " +
            "AND pendingSync = 0 AND uid > 0 AND NOT EXISTS (" +
            "SELECT 1 FROM pending_operation o WHERE o.accountId = message.accountId " +
            "AND o.folderPath = message.folderPath AND o.uid = message.uid)"
    )
    suspend fun deleteOlderThan(accountId: Long, cutoffMillis: Long)

    /** Inserts headers not stored yet and leaves the stored ones (and their bodies) alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(messages: List<MessageEntity>)

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    /** UIDs of the messages the server has, ascending. Rows with uid 0 or below are local-only. */
    @Query(
        "SELECT uid FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid > 0 ORDER BY uid"
    )
    suspend fun serverUids(accountId: Long, folderPath: String): List<Long>

    @Query(
        "SELECT id, uid, seen, flagged, answered, draft, labels FROM message " +
            "WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid BETWEEN :firstUid AND :lastUid"
    )
    suspend fun syncRows(
        accountId: Long,
        folderPath: String,
        firstUid: Long,
        lastUid: Long
    ): List<MessageSyncRow>

    /** Takes over what the server says about a message; bodies and local fields stay. */
    // One column per parameter, matching the query.
    @Suppress("LongParameterList")
    @Query(
        "UPDATE message SET seen = :seen, flagged = :flagged, answered = :answered, " +
            "draft = :draft, labels = :labels WHERE id = :id"
    )
    suspend fun updateServerState(
        id: Long,
        seen: Boolean,
        flagged: Boolean,
        answered: Boolean,
        draft: Boolean,
        labels: List<String>
    )

    @Query(
        "DELETE FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid IN (:uids)"
    )
    suspend fun deleteUids(accountId: Long, folderPath: String, uids: List<Long>)

    /**
     * Second step of a UIDVALIDITY reset: the messages that operations wait on move to the
     * negative uid those operations carry, so the rows survive [deleteServerRows].
     */
    @Query(
        "UPDATE message SET uid = -id WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uid > 0 AND -id IN (SELECT uid FROM pending_operation " +
            "WHERE accountId = :accountId AND folderPath = :folderPath AND uid < 0)"
    )
    suspend fun parkReferencedByOperations(accountId: Long, folderPath: String)

    @Query("UPDATE message SET threadId = :to WHERE accountId = :accountId AND threadId = :from")
    suspend fun renameThread(accountId: Long, from: String, to: String)

    @Query("UPDATE message SET threadId = :threadId WHERE id = :id")
    suspend fun setThreadId(id: Long, threadId: String)

    /** What the conversation algorithm needs from every message of the account. */
    @Query(
        "SELECT id, folderPath, uid, messageId, inReplyTo, referenceIds, subject, sentAt, " +
            "threadId FROM message WHERE accountId = :accountId"
    )
    suspend fun threadSeeds(accountId: Long): List<ThreadSeed>

    @Query(
        "UPDATE message SET pendingSync = :pending WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid = :uid"
    )
    suspend fun setPendingSync(accountId: Long, folderPath: String, uid: Long, pending: Boolean)

    /** The labels a message shows right after the user changed them, before the server agrees. */
    @Query(
        "UPDATE message SET labels = :labels WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid = :uid"
    )
    suspend fun setLabels(accountId: Long, folderPath: String, uid: Long, labels: List<String>)

    @Query(
        "SELECT uid FROM message WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND pendingSync = 1"
    )
    suspend fun pendingUids(accountId: Long, folderPath: String): List<Long>

    /** The rows of a folder with what identifies each message, for a UIDVALIDITY check. */
    @Query(
        "SELECT id, uid, messageId, gmailMessageId FROM message " +
            "WHERE accountId = :accountId AND folderPath = :folderPath"
    )
    suspend fun identities(accountId: Long, folderPath: String): List<MessageIdentityRow>

    /** Deletes server rows by id; rows already moved to a local uid (0 or below) are spared. */
    @Query("DELETE FROM message WHERE id IN (:ids) AND uid > 0")
    suspend fun deleteServerRows(ids: List<Long>)

    /** The stored rows, among [uids] of one folder, that a search on the server found (T20). */
    @Query(
        "SELECT id, uid, sentAt, hasAttachments FROM message WHERE accountId = :accountId " +
            "AND folderPath = :folderPath AND uid IN (:uids)"
    )
    suspend fun rowsByUids(accountId: Long, folderPath: String, uids: List<Long>): List<FoundRow>

    /**
     * How many messages of synced folders are inside the window ([sinceMillis]) and small enough
     * ([maxSize]) to have their body downloaded during sync: what "N of M" counts against.
     */
    @Query(
        "SELECT COUNT(*) FROM message m JOIN folder f ON f.accountId = m.accountId " +
            "AND f.path = m.folderPath WHERE m.accountId = :accountId AND f.syncEnabled = 1 " +
            "AND m.uid > 0 AND m.sentAt >= :sinceMillis AND m.size <= :maxSize"
    )
    suspend fun countBodyCandidates(accountId: Long, sinceMillis: Long, maxSize: Long): Int

    /** How many of those still have no body: the work list of the body download. */
    @Query(
        "SELECT COUNT(*) FROM message m JOIN folder f ON f.accountId = m.accountId " +
            "AND f.path = m.folderPath WHERE m.accountId = :accountId AND f.syncEnabled = 1 " +
            "AND m.uid > 0 AND m.sentAt >= :sinceMillis AND m.size <= :maxSize " +
            "AND m.bodyText IS NULL AND m.bodyHtml IS NULL"
    )
    suspend fun countBodiesMissing(accountId: Long, sinceMillis: Long, maxSize: Long): Int

    /**
     * The next [limit] messages without a body, newest first, after the cursor ([beforeSentAt],
     * [beforeId]) so a message that keeps failing does not hold up the ones behind it.
     */
    @Suppress("LongParameterList")
    @Query(BODY_WORK_SQL)
    suspend fun bodyWork(
        accountId: Long,
        sinceMillis: Long,
        maxSize: Long,
        beforeSentAt: Long,
        beforeId: Long,
        limit: Int
    ): List<BodyWorkRow>

    /** Server messages, in any folder, that are the message with this identity (SPEC section 5). */
    @Query(
        "SELECT folderPath, uid, messageId, gmailMessageId, labels FROM message " +
            "WHERE accountId = :accountId AND uid > 0 AND " +
            "((:messageId IS NOT NULL AND messageId = :messageId) OR " +
            "(:gmailMessageId IS NOT NULL AND gmailMessageId = :gmailMessageId))"
    )
    suspend fun withIdentity(
        accountId: Long,
        messageId: String?,
        gmailMessageId: Long?
    ): List<IdentifiedMessage>

    /** The messages of the account's Drafts folders: the drafts kept on the server. */
    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.accountId = m.accountId " +
            "AND f.path = m.folderPath WHERE m.accountId = :accountId AND f.role = 'DRAFTS' " +
            "AND m.uid > 0 ORDER BY m.sentAt DESC, m.id DESC"
    )
    fun observeServerDrafts(accountId: Long): Flow<List<MessageEntity>>

    @Query(
        "SELECT m.* FROM message m JOIN folder f ON f.accountId = m.accountId " +
            "AND f.path = m.folderPath WHERE f.role = 'DRAFTS' AND m.uid > 0 " +
            "ORDER BY m.sentAt DESC, m.id DESC"
    )
    fun observeAllServerDrafts(): Flow<List<MessageEntity>>

    /**
     * The participants of the newest [limit] messages of the account, for recipient suggestions
     * (RF-07). The to and cc lists are unpacked by the caller; SQLite cannot split them.
     */
    @Query(
        "SELECT m.senderName, m.senderAddress, m.toAddresses, m.ccAddresses, m.sentAt, " +
            "(f.role = 'SENT') AS fromUser FROM message m JOIN folder f " +
            "ON f.accountId = m.accountId AND f.path = m.folderPath " +
            "WHERE m.accountId = :accountId AND m.draft = 0 " +
            "AND f.role NOT IN ('DRAFTS', 'JUNK', 'TRASH') " +
            "ORDER BY m.sentAt DESC LIMIT :limit"
    )
    suspend fun addressSamples(accountId: Long, limit: Int): List<AddressSample>
}

/** What a search on the server needs to know about a stored message (T20). */
data class FoundRow(
    val id: Long,
    val uid: Long,
    val sentAt: java.time.Instant,
    val hasAttachments: Boolean
)

/** The state of a stored message that a sync compares with the server. */
data class MessageSyncRow(
    val id: Long,
    val uid: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val draft: Boolean,
    val labels: List<String>
)

/** A stored message as input for rebuilding conversations. */
data class ThreadSeed(
    val id: Long,
    val folderPath: String,
    val uid: Long,
    val messageId: String?,
    val inReplyTo: String?,
    val referenceIds: List<String>,
    val subject: String,
    val sentAt: java.time.Instant,
    val threadId: String
)

data class MessageIdentityRow(
    val id: Long,
    val uid: Long,
    val messageId: String?,
    val gmailMessageId: Long?
)

data class IdentifiedMessage(
    val folderPath: String,
    val uid: Long,
    val messageId: String?,
    val gmailMessageId: Long?,
    val labels: List<String>
)

/** One message's participants, as stored. [fromUser] is true for mail in a Sent folder. */
data class AddressSample(
    val senderName: String,
    val senderAddress: String,
    val toAddresses: List<String>,
    val ccAddresses: List<String>,
    val sentAt: Instant,
    val fromUser: Boolean
)

/** A message whose body is still to be downloaded. */
data class BodyWorkRow(
    val id: Long,
    val folderPath: String,
    val uid: Long,
    val sentAt: java.time.Instant
)

/** The messages of one conversation; a constant so the query plan can be tested. */
internal const val THREAD_SQL =
    "SELECT * FROM message INDEXED BY $THREAD_INDEX WHERE accountId = :accountId " +
        "AND folderPath = :folderPath AND threadId = :threadId ORDER BY sentAt, id"

/** The body download work list; a constant so the query plan can be tested. */
internal const val BODY_WORK_SQL =
    "SELECT m.id AS id, m.folderPath AS folderPath, m.uid AS uid, m.sentAt AS sentAt " +
        "FROM message m JOIN folder f ON f.accountId = m.accountId " +
        "AND f.path = m.folderPath WHERE m.accountId = :accountId AND f.syncEnabled = 1 " +
        "AND m.uid > 0 AND m.sentAt >= :sinceMillis AND m.size <= :maxSize " +
        "AND m.bodyText IS NULL AND m.bodyHtml IS NULL " +
        "AND (m.sentAt < :beforeSentAt OR (m.sentAt = :beforeSentAt AND m.id < :beforeId)) " +
        "ORDER BY m.sentAt DESC, m.id DESC LIMIT :limit"
