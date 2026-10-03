// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.qtekfun.ultimatemail.data.local.model.AttachmentState

/** An attachment of a received message, downloaded on demand (RF-04). */
@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** IMAP body part number, e.g. "2.1". */
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val state: AttachmentState = AttachmentState.REMOTE,
    /** Path of the downloaded copy in app storage; null while the attachment is remote. */
    val localPath: String? = null,
    /** Content-ID the HTML refers to as `cid:`, without angle brackets; null when it has none. */
    val contentId: String? = null,
    /** The part is meant to be shown inside the message rather than listed (RF-04). */
    @ColumnInfo(defaultValue = "0")
    val inline: Boolean = false
)
