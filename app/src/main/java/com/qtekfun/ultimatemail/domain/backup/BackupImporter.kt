// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.account.AccountValidator
import com.qtekfun.ultimatemail.domain.account.CreateAccountResult
import com.qtekfun.ultimatemail.domain.oauth.GoogleClientId
import com.qtekfun.ultimatemail.domain.oauth.MicrosoftClientId
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds
import com.qtekfun.ultimatemail.domain.settings.AccountProfile
import com.qtekfun.ultimatemail.domain.settings.AccountSettingsStore
import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import com.qtekfun.ultimatemail.domain.settings.OfflineWindow
import com.qtekfun.ultimatemail.domain.settings.SaveResult
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What can be done with one account of a backup. */
enum class PreviewStatus {
    IMPORTABLE,

    /** An account with this address and IMAP server exists (or comes earlier in the file). */
    DUPLICATE,

    /** The address is taken by an account on another server; accounts are unique by address. */
    ADDRESS_TAKEN,

    /** The data of the account is not valid (address, server, port); it cannot be imported. */
    INVALID
}

/** One account found in a backup, as the preview shows it. */
data class PreviewEntry(
    /** Position in the file; what the selection refers to. */
    val index: Int,
    val email: String,
    val displayName: String,
    val authType: AuthType,
    /** Whether the file holds the secrets of the account, so it needs no new sign-in. */
    val hasCredentials: Boolean,
    val status: PreviewStatus
) {
    // The address stays out of logs.
    override fun toString(): String = "PreviewEntry(#$index, $status)"
}

/** What a backup holds, ready to be shown and then imported with [BackupImporter.import]. */
class BackupPreview internal constructor(
    val entries: List<PreviewEntry>,
    val hasSettings: Boolean,
    internal val document: BackupDocument
) {
    override fun toString(): String = "BackupPreview(redacted)"
}

/** The outcome of [BackupImporter.open]. */
sealed interface OpenResult {
    class Opened(val preview: BackupPreview) : OpenResult

    data class Failed(val error: BackupError) : OpenResult
}

/** An account created by an import. */
data class ImportedAccount(val email: String, val needsSignIn: Boolean) {
    override fun toString(): String = "ImportedAccount(needsSignIn=$needsSignIn)"
}

/** What an import did. */
data class ImportSummary(
    val imported: List<ImportedAccount>,
    /** Selected accounts that already existed or were not valid. */
    val skipped: Int,
    /** Accounts that could not be stored; nothing of them was left behind. */
    val failed: Int,
    val settingsApplied: Boolean
)

/**
 * Reads a backup and recreates its accounts (RF-12). Accounts are created through
 * [AccountSetup], so the vault, the Keystore and every invariant of "add account" apply.
 * Nothing existing is ever changed or removed, and each account is imported completely or not
 * at all.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One collaborator and one step per part.
class BackupImporter @Inject constructor(
    database: UltimateMailDatabase,
    private val setup: AccountSetup,
    private val removal: AccountRemoval,
    private val accountSettings: AccountSettingsStore,
    private val settings: SettingsRepository,
    private val downloads: OfflineDownloads,
    private val clientIds: OAuthClientIds,
    private val pendingFolders: PendingFolderChoices,
    private val scheduler: SyncScheduler,
    private val crypto: BackupCrypto,
    private val source: DocumentSource,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()
    private val validator = AccountValidator()

    /** Reads, decrypts and checks the file at [uri]. [passphrase] is wiped before returning. */
    suspend fun open(uri: String, passphrase: CharArray): OpenResult = withContext(io) {
        try {
            val bytes = when (val read = source.read(uri, BackupFormat.MAX_FILE_BYTES)) {
                is DocumentRead.Bytes -> read.bytes

                DocumentRead.TooLarge -> return@withContext OpenResult.Failed(BackupError.TooLarge)

                DocumentRead.Unreadable ->
                    return@withContext OpenResult.Failed(BackupError.Unreadable)
            }
            val plain = when (val decrypted = crypto.decrypt(bytes, passphrase)) {
                is DecryptResult.Plain -> decrypted.bytes
                is DecryptResult.Failed -> return@withContext OpenResult.Failed(decrypted.error)
            }
            val decoded = try {
                BackupCodec.decode(plain)
            } finally {
                plain.fill(0)
            }
            when (decoded) {
                is DecodeResult.Failed -> OpenResult.Failed(decoded.error)
                is DecodeResult.Decoded -> OpenResult.Opened(preview(decoded.document))
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun preview(document: BackupDocument): BackupPreview {
        val statuses = classify(document.accounts, accounts.observeAll().first())
        return BackupPreview(
            entries = document.accounts.mapIndexed { index, account ->
                PreviewEntry(
                    index = index,
                    email = account.email,
                    displayName = account.displayName,
                    authType = account.authType,
                    hasCredentials = account.usableCredentials() != null,
                    status = statuses[index]
                )
            },
            hasSettings = document.settings != null,
            document = document
        )
    }

    /**
     * Imports the accounts of [preview] at the positions in [selected] that can be imported, and
     * the device settings when [withSettings] is set and the file has them. The duplicates are
     * judged again, against the accounts stored now.
     */
    suspend fun import(
        preview: BackupPreview,
        selected: Set<Int>,
        withSettings: Boolean
    ): ImportSummary = withContext(io) {
        val document = preview.document
        val statuses = classify(document.accounts, accounts.observeAll().first())
        val imported = mutableListOf<ImportedAccount>()
        var skipped = 0
        var failed = 0
        document.accounts.forEachIndexed { index, account ->
            if (index !in selected) return@forEachIndexed
            if (statuses[index] != PreviewStatus.IMPORTABLE) {
                skipped++
            } else {
                // Imported accounts count for the duplicates after them in the same file.
                importOne(account)?.let { imported += it } ?: run { failed++ }
            }
        }
        val settingsApplied = withSettings && document.settings?.let(::applySettings) != null
        ImportSummary(imported, skipped, failed, settingsApplied)
    }

    /**
     * The status of each account against [existing]. Two accounts of the file with the same
     * address count as duplicates of each other: the first one wins.
     */
    private fun classify(
        incoming: List<BackupAccount>,
        existing: List<AccountEntity>
    ): List<PreviewStatus> {
        val seen = existing.map { it.email.lowercase() to it.imapHost.lowercase() }.toMutableList()
        return incoming.map { account ->
            val email = account.email.trim().lowercase()
            val host = account.imap.host.lowercase()
            when {
                !isValid(account) -> PreviewStatus.INVALID

                email to host in seen -> PreviewStatus.DUPLICATE

                seen.any { it.first == email } -> PreviewStatus.ADDRESS_TAKEN

                else -> {
                    seen += email to host
                    PreviewStatus.IMPORTABLE
                }
            }
        }
    }

    private fun isValid(account: BackupAccount): Boolean =
        validator.validate(account.toInput(AccountCredentials()))
            .none { it != AccountInputError.MissingCredentials }

    private suspend fun importOne(account: BackupAccount): ImportedAccount? {
        val credentials = account.usableCredentials()
        val result = if (credentials != null) {
            setup.create(account.toInput(credentials))
        } else {
            setup.createAwaitingSignIn(account.toInput(AccountCredentials()))
        }
        val accountId = (result as? CreateAccountResult.Created)?.accountId ?: return null
        return try {
            if (!configure(accountId, account)) {
                rollBack(accountId)
                null
            } else {
                scheduler.requestSync(accountId)
                ImportedAccount(account.email, needsSignIn = credentials == null)
            }
        } catch (e: CancellationException) {
            rollBack(accountId)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            rollBack(accountId)
            null
        }
    }

    /** Applies everything that is not part of the account row; false when something failed. */
    private suspend fun configure(accountId: Long, account: BackupAccount): Boolean {
        val profile = AccountProfile(
            displayName = account.displayName,
            signature = account.signature,
            signatureEnabled = account.signatureEnabled,
            signatureBeforeQuote = account.signatureBeforeQuote
        )
        if (accountSettings.saveProfile(accountId, profile) != SaveResult.Saved) return false
        accountSettings.setOfflineWindow(
            accountId,
            OfflineWindow.fromDays(account.offlineWindowDays)
        )
        if (account.folders.isNotEmpty()) pendingFolders.save(accountId, account.folders)
        applyClientId(account)
        downloads.setEnabled(accountId, account.downloadForOffline)
        return true
    }

    /** The client ID of the backup is kept only when this device has none: nothing is overwritten. */
    private fun applyClientId(account: BackupAccount) {
        val id = account.oauthClientId?.trim().orEmpty()
        if (id.isEmpty()) return
        when (account.authType) {
            AuthType.OAUTH_GOOGLE ->
                if (clientIds.google() == null &&
                    GoogleClientId.isValid(id)
                ) {
                    clientIds.setGoogle(id)
                }

            AuthType.OAUTH_MICROSOFT ->
                if (clientIds.microsoft() == null && MicrosoftClientId.isValid(id)) {
                    clientIds.setMicrosoft(id)
                }

            AuthType.PASSWORD -> Unit
        }
    }

    private suspend fun rollBack(accountId: Long) = withContext(NonCancellable) {
        pendingFolders.clear(accountId)
        removal.remove(accountId)
    }

    private fun applySettings(imported: AppSettings): AppSettings {
        settings.setTheme(imported.theme)
        settings.setDynamicColor(imported.dynamicColor)
        settings.setAmoled(imported.amoled)
        settings.setDensity(imported.density)
        settings.setSwipeRight(imported.swipe.right)
        settings.setSwipeLeft(imported.swipe.left)
        settings.setRemoteContent(imported.remoteContent)
        return imported
    }

    private fun BackupAccount.toInput(credentials: AccountCredentials) = AccountInput(
        email = email,
        displayName = displayName,
        username = username,
        authType = authType,
        imap = imap,
        smtp = smtp,
        credentials = credentials
    )

    /** The secrets of the file, if they are what this kind of account needs. */
    private fun BackupAccount.usableCredentials(): AccountCredentials? = when (authType) {
        AuthType.PASSWORD ->
            credentials?.password?.takeIf {
                it.isNotEmpty()
            }?.let { AccountCredentials(password = it) }

        AuthType.OAUTH_GOOGLE, AuthType.OAUTH_MICROSOFT ->
            credentials?.oauth?.let { AccountCredentials(oauth = it) }
    }
}
