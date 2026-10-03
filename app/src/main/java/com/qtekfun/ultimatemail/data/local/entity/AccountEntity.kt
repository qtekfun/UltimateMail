// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity

/**
 * A mail account. Passwords and OAuth tokens are not stored here but in the Android Keystore
 * (T06). Everything else in the database hangs off [id], so deleting the account removes it all.
 */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val displayName: String,
    val username: String,
    val authType: AuthType,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: ConnectionSecurity,
    /** Plain-text signature of this account (RF-08). */
    @ColumnInfo(defaultValue = "")
    val signature: String = "",
    @ColumnInfo(defaultValue = "1")
    val signatureEnabled: Boolean = true,
    /** Replies and forwards put the signature above the quoted text unless this is false. */
    @ColumnInfo(defaultValue = "1")
    val signatureBeforeQuote: Boolean = true,
    /** Days of headers kept offline (RF-10); null keeps the whole mailbox. */
    @ColumnInfo(defaultValue = "90")
    val offlineWindowDays: Int? = DEFAULT_OFFLINE_WINDOW_DAYS
) {
    companion object {
        const val DEFAULT_OFFLINE_WINDOW_DAYS = 90
    }
}
