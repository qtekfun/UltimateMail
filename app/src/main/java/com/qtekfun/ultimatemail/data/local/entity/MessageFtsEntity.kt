// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.Entity
import androidx.room3.Fts4

/** Full-text index over the searchable columns of [MessageEntity] (RF-09), kept by Room. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    val subject: String,
    val senderName: String,
    val senderAddress: String,
    val bodyText: String?
)
