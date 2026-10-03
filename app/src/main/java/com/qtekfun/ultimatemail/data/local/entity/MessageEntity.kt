// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

/**
 * A message as it lives in one folder: the server identity is ([accountId], [folderPath], [uid])
 * and is unique. The surrogate [id] lets the full-text index follow the row.
 */
@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["accountId", "path"],
            childColumns = ["accountId", "folderPath"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("accountId", "folderPath", "uid", unique = true),
        Index("accountId", "folderPath", "sentAt"),
        Index("accountId", "folderPath", "threadId"),
        Index("accountId", "messageId")
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderPath: String,
    val uid: Long,
    /** RFC 5322 Message-ID, the stable identity when a message changes UID (SPEC §5). */
    val messageId: String?,
    /** Gmail X-GM-MSGID, when the server has it. */
    val gmailMessageId: Long? = null,
    /** Conversation key: X-GM-THRID, the server THREAD result or one computed locally (T11). */
    val threadId: String,
    val subject: String,
    val senderName: String,
    val senderAddress: String,
    val toAddresses: List<String> = emptyList(),
    val ccAddresses: List<String> = emptyList(),
    val sentAt: Instant,
    val snippet: String = "",
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val draft: Boolean = false,
    val hasAttachments: Boolean = false,
    val size: Long = 0,
    /** In-Reply-To header, kept so conversations can be rebuilt between syncs (T10, T11). */
    val inReplyTo: String? = null,
    /** References header ids, same purpose as [inReplyTo]. */
    @ColumnInfo(defaultValue = "")
    val referenceIds: List<String> = emptyList(),
    /** Gmail labels of the message; empty for other providers. */
    val labels: List<String> = emptyList(),
    /** Bodies are fetched on demand, so both are null until the message is opened. */
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    /** A local change is waiting to be sent: the per-message pending indicator (RF-10). */
    @ColumnInfo(defaultValue = "0")
    val pendingSync: Boolean = false
)
