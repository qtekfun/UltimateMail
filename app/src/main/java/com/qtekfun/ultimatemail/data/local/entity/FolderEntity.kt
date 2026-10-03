// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.qtekfun.ultimatemail.data.local.model.FolderRole

/**
 * A mailbox folder, or a Gmail label ([isLabel]) which is also an IMAP folder. The UID triple
 * is the IMAP sync state of the folder (T10).
 */
@Entity(
    tableName = "folder",
    primaryKeys = ["accountId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FolderEntity(
    val accountId: Long,
    /** Full IMAP path, e.g. "Work/Invoices". */
    val path: String,
    val name: String,
    val role: FolderRole = FolderRole.OTHER,
    val isLabel: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val syncEnabled: Boolean = true,
    val uidValidity: Long? = null,
    val uidNext: Long? = null,
    val highestModSeq: Long? = null
)
