// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import java.time.Instant

/**
 * A message being written, or waiting in the outbox (RF-07). Recipients are stored as formatted
 * RFC 5322 strings (`Name <address>`), so display names survive; the domain layer parses them.
 *
 * [key] is the identity of the draft across devices: it is part of the Message-ID of the copy kept
 * in the server's Drafts folder, which is how two devices editing the same draft are told apart
 * (SPEC section 5, rule 3). The source message is not a foreign key on purpose: a sync may
 * rebuild or drop that row while the reply is still being written.
 */
@Entity(
    tableName = "draft",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId", "state", "updatedAt"), Index("key", unique = true)]
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val accountId: Long,
    val kind: DraftKind,
    @ColumnInfo(defaultValue = "EDITING")
    val state: DraftState = DraftState.EDITING,
    val toAddresses: List<String> = emptyList(),
    val ccAddresses: List<String> = emptyList(),
    val bccAddresses: List<String> = emptyList(),
    val subject: String = "",
    /** Plain text, signature and quoted text included, as the user sees it. */
    val body: String = "",
    val inReplyTo: String? = null,
    @ColumnInfo(defaultValue = "")
    val referenceIds: List<String> = emptyList(),
    /** The message being answered or forwarded; its flag is set once the reply is sent. */
    val sourceAccountId: Long? = null,
    val sourceFolderPath: String? = null,
    val sourceUid: Long? = null,
    /** Message-ID of the source, to check that the UID still is that message. */
    val sourceMessageId: String? = null,
    /** The signature block text now in [body], so a change of sender can swap exactly that. */
    val signatureText: String? = null,
    @ColumnInfo(defaultValue = "1")
    val signatureBeforeQuote: Boolean = true,
    /** Message-ID of the copy in the server's Drafts folder that this device last wrote. */
    val serverMessageId: String? = null,
    /** Edited since the last upload to the server. */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    /** Counts local saves, so an upload knows whether the draft changed while it ran. */
    @ColumnInfo(defaultValue = "0")
    val revision: Int = 0,
    /** Fixed when the draft is sent: how the sent message is found again in Sent. */
    val outgoingMessageId: String? = null,
    /** When the SMTP server accepted the message; from then on it must never be sent again. */
    val smtpAcceptedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
