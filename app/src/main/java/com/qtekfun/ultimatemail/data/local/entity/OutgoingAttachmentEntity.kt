// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** A file attached to a draft: a copy kept in app-private storage until the draft is gone. */
@Entity(
    tableName = "outgoing_attachment",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("draftId")]
)
data class OutgoingAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: Long,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    /** Absolute path of the copy in the outbox storage. */
    val filePath: String
)
