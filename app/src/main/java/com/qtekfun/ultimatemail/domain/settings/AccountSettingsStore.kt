// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The settings of one account that are edited on its settings screen. */
data class AccountSettings(
    val id: Long,
    val email: String,
    val profile: AccountProfile,
    val offlineWindow: OfflineWindow,
    /** Sync also downloads the full bodies inside the offline window (RF-10). */
    val downloadForOffline: Boolean
)

/** One folder in the list where the user picks what to sync (RF-10). */
data class FolderSync(
    val path: String,
    val name: String,
    val role: FolderRole,
    val isLabel: Boolean,
    val syncEnabled: Boolean
) {
    /** The Inbox is always synced: an account without it would have nothing to show. */
    val canToggle: Boolean get() = role != FolderRole.INBOX
}

/** What happened when a profile was saved. */
sealed interface SaveResult {
    data object Saved : SaveResult

    data class Invalid(val errors: Set<ProfileError>) : SaveResult

    /** The account was removed meanwhile. */
    data object NotFound : SaveResult
}

/** Reads and changes the per-account settings: profile, offline window, folders to sync. */
class AccountSettingsStore @Inject constructor(
    database: UltimateMailDatabase,
    private val scheduler: SyncScheduler,
    private val downloads: OfflineDownloads,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()
    private val folders = database.folderDao()

    /** The settings of [accountId], or null when there is no such account (any more). */
    fun observe(accountId: Long): Flow<AccountSettings?> =
        combine(accounts.observe(accountId), downloads.observe(accountId)) { stored, download ->
            stored?.let { account ->
                AccountSettings(
                    id = account.id,
                    email = account.email,
                    profile = AccountProfile(
                        displayName = account.displayName,
                        signature = account.signature,
                        signatureEnabled = account.signatureEnabled,
                        signatureBeforeQuote = account.signatureBeforeQuote
                    ),
                    offlineWindow = OfflineWindow.fromDays(account.offlineWindowDays),
                    downloadForOffline = download
                )
            }
        }

    /** The folders of [accountId], by path, with whether each is synced. */
    fun observeFolders(accountId: Long): Flow<List<FolderSync>> =
        folders.observeAll(accountId).map { list ->
            list.map { FolderSync(it.path, it.name, it.role, it.isLabel, it.syncEnabled) }
        }

    /** Saves [profile] cleaned up by [ProfileRules.normalize], unless it is not valid. */
    suspend fun saveProfile(accountId: Long, profile: AccountProfile): SaveResult {
        val errors = ProfileRules.errors(profile)
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)
        val clean = ProfileRules.normalize(profile)
        return withContext(io) {
            val account = accounts.get(accountId) ?: return@withContext SaveResult.NotFound
            accounts.update(
                account.copy(
                    displayName = clean.displayName,
                    signature = clean.signature,
                    signatureEnabled = clean.signatureEnabled,
                    signatureBeforeQuote = clean.signatureBeforeQuote
                )
            )
            SaveResult.Saved
        }
    }

    /**
     * Sets how much mail [accountId] keeps offline. Narrowing it takes effect at the next sync,
     * which drops the older headers; widening it does not bring back headers already dropped
     * (known limit of the sync engine, T10).
     */
    suspend fun setOfflineWindow(accountId: Long, window: OfflineWindow) = withContext(io) {
        accounts.get(accountId)?.let { accounts.update(it.copy(offlineWindowDays = window.days)) }
    }

    /**
     * Turns the download of message bodies for offline reading on or off for [accountId]; turning
     * it on syncs the account right away so the download starts.
     */
    suspend fun setDownloadForOffline(accountId: Long, enabled: Boolean) {
        val exists = withContext(io) { accounts.get(accountId) != null }
        if (!exists) return
        downloads.setEnabled(accountId, enabled)
        if (enabled) scheduler.requestSync(accountId)
    }

    /** Turns the sync of one folder on or off; turning it on syncs the account right away. */
    suspend fun setFolderSync(accountId: Long, path: String, enabled: Boolean) {
        val changed = withContext(io) {
            val folder = folders.get(accountId, path)
            if (folder == null || folder.role == FolderRole.INBOX ||
                folder.syncEnabled == enabled
            ) {
                false
            } else {
                folders.setSyncEnabled(accountId, path, enabled)
                true
            }
        }
        if (changed && enabled) scheduler.requestSync(accountId)
    }
}
