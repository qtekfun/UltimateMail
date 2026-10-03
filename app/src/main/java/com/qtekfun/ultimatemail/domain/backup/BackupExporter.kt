// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.di.AppVersionName
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds
import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The outcome of [BackupExporter.export]. */
sealed interface ExportResult {
    data class Done(val accounts: Int, val credentialsIncluded: Boolean) : ExportResult

    /** The passphrase is not acceptable; nothing was written. */
    data class BadPassphrase(val issue: PassphraseIssue) : ExportResult

    /** The chosen location could not be written. */
    data object WriteFailed : ExportResult
}

/**
 * Writes the encrypted backup of the accounts and settings (RF-12). It reads through the DAOs
 * and repositories, so it needs no knowledge of the tables, and it never reads mail.
 */
@Suppress("LongParameterList") // One collaborator per thing an account is made of.
class BackupExporter @Inject constructor(
    database: UltimateMailDatabase,
    private val vault: CredentialVault,
    private val settings: SettingsRepository,
    private val downloads: OfflineDownloads,
    private val clientIds: OAuthClientIds,
    private val crypto: BackupCrypto,
    private val sink: DocumentSink,
    @AppVersionName private val appVersion: String,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()
    private val folders = database.folderDao()

    /**
     * Encrypts the backup with [passphrase] and writes it to [uri]. The passphrase is wiped
     * before this returns, whatever happens; [includeCredentials] puts the passwords and tokens
     * in the encrypted data too.
     */
    suspend fun export(
        uri: String,
        passphrase: CharArray,
        includeCredentials: Boolean,
        includeSettings: Boolean = true
    ): ExportResult = withContext(io) {
        try {
            val issue = BackupPassphrase.check(passphrase, passphrase, includeCredentials)
            if (issue != null) return@withContext ExportResult.BadPassphrase(issue)
            val document = buildDocument(includeCredentials, includeSettings)
            val plain = BackupCodec.encode(document)
            val container = try {
                crypto.encrypt(plain, passphrase)
            } finally {
                plain.fill(0)
            }
            if (sink.write(uri, container)) {
                ExportResult.Done(
                    document.accounts.size,
                    document.accounts.any { it.credentials != null }
                )
            } else {
                ExportResult.WriteFailed
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun buildDocument(
        includeCredentials: Boolean,
        includeSettings: Boolean
    ): BackupDocument = BackupDocument(
        formatVersion = BackupFormat.VERSION,
        appVersion = appVersion,
        accounts = accounts.observeAll().first().map { toBackup(it, includeCredentials) },
        settings = if (includeSettings) settings.current() else null
    )

    private suspend fun toBackup(account: AccountEntity, includeCredentials: Boolean) =
        BackupAccount(
            email = account.email,
            displayName = account.displayName,
            username = account.username,
            authType = account.authType,
            imap = ServerEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            smtp = ServerEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            signature = account.signature,
            signatureEnabled = account.signatureEnabled,
            signatureBeforeQuote = account.signatureBeforeQuote,
            offlineWindowDays = account.offlineWindowDays,
            downloadForOffline = downloads.isEnabled(account.id),
            folders = folders.all(account.id).associate { it.path to it.syncEnabled },
            oauthClientId = when (account.authType) {
                AuthType.PASSWORD -> null
                AuthType.OAUTH_GOOGLE -> clientIds.google()
                AuthType.OAUTH_MICROSOFT -> clientIds.microsoft()
            },
            credentials = if (includeCredentials) vault.load(account.id) else null
        )
}
