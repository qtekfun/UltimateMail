// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint

/** Constants of the backup file format (RF-12). */
object BackupFormat {
    /**
     * The version of the JSON inside the container. Fields may be added within a version (readers
     * ignore what they do not know); a change that breaks readers raises this number, and a
     * file with a higher number is refused.
     */
    const val VERSION = 1

    /** The suggested name of the file; the extension is ours, the content is encrypted. */
    const val SUGGESTED_FILE_NAME = "ultimatemail-accounts.umbackup"

    const val MIME_TYPE = "application/octet-stream"

    /** Larger files are refused before anything is decrypted or parsed. */
    const val MAX_FILE_BYTES = 1024 * 1024
}

/**
 * Everything one account contributes to the file: configuration only, never mail. The
 * [credentials] are present only when the user asked for them at export time.
 */
data class BackupAccount(
    val email: String,
    val displayName: String,
    val username: String,
    val authType: AuthType,
    val imap: ServerEndpoint,
    val smtp: ServerEndpoint,
    val signature: String,
    val signatureEnabled: Boolean,
    val signatureBeforeQuote: Boolean,
    /** Days of headers kept offline; null keeps the whole mailbox. */
    val offlineWindowDays: Int?,
    val downloadForOffline: Boolean,
    /** Whether each folder (by path) is synced. */
    val folders: Map<String, Boolean>,
    /** The OAuth client ID the account signs in with (a public value); null for passwords. */
    val oauthClientId: String?,
    val credentials: AccountCredentials?
) {
    // The address and the credentials stay out of logs and crash reports.
    override fun toString(): String = "BackupAccount(redacted)"
}

/** The content of a backup file after decryption. */
data class BackupDocument(
    val formatVersion: Int,
    val appVersion: String,
    val accounts: List<BackupAccount>,
    /** The device settings, or null when the file has none. The language is never exported. */
    val settings: AppSettings?
)

/** Why a backup could not be read. The UI maps each one to a message; none carries data. */
sealed interface BackupError {
    /** The file does not start with the backup signature. */
    data object NotABackup : BackupError

    /** The file (or the data inside) was written by a newer version of the format. */
    data class UnsupportedVersion(val version: Int) : BackupError

    /** The file is larger than [BackupFormat.MAX_FILE_BYTES]. */
    data object TooLarge : BackupError

    /** The file ends before the encrypted data does. */
    data object Truncated : BackupError

    /** The key derivation settings in the header are outside what this app accepts. */
    data object UnsupportedKdf : BackupError

    /**
     * Decryption failed: the passphrase is wrong or the file was changed. The two cannot be told
     * apart (that is what authenticated encryption gives), so the message says both.
     */
    data object WrongPassphraseOrDamaged : BackupError

    /** The decrypted content is not a valid backup. */
    data object Malformed : BackupError

    /** The file could not be read from the storage. */
    data object Unreadable : BackupError
}
