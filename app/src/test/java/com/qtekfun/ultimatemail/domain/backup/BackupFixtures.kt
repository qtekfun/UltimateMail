// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.SwipeActions
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint
import java.time.Instant

internal val FAST_KDF = KdfPolicy(iterations = 1_000, minIterations = 500, maxIterations = 20_000)

internal fun backupAccount(
    email: String = "ana@example.test",
    authType: AuthType = AuthType.PASSWORD,
    imapHost: String = "imap.example.test",
    credentials: AccountCredentials? = null,
    folders: Map<String, Boolean> = mapOf("INBOX" to true, "Work/Invoices" to false),
    oauthClientId: String? = null
) = BackupAccount(
    email = email,
    displayName = "Ana Gil",
    username = email,
    authType = authType,
    imap = ServerEndpoint(imapHost, 993, ConnectionSecurity.TLS),
    smtp = ServerEndpoint("smtp.example.test", 587, ConnectionSecurity.STARTTLS),
    signature = "Ana\nAcme",
    signatureEnabled = false,
    signatureBeforeQuote = false,
    offlineWindowDays = 30,
    downloadForOffline = false,
    folders = folders,
    oauthClientId = oauthClientId,
    credentials = credentials
)

internal val CUSTOM_SETTINGS = AppSettings(
    theme = ThemeMode.DARK,
    dynamicColor = false,
    amoled = true,
    density = DisplayDensity.COMPACT,
    swipe = SwipeActions(right = SwipeAction.TOGGLE_READ, left = SwipeAction.MOVE),
    remoteContent = RemoteContentPolicy.ASK
)

internal fun backupDocument(
    accounts: List<BackupAccount> = listOf(backupAccount()),
    settings: AppSettings? = CUSTOM_SETTINGS
) = BackupDocument(BackupFormat.VERSION, "0.1.0", accounts, settings)

internal val SAMPLE_TOKENS =
    OAuthTokens("access-token-1", "refresh-token-1", Instant.ofEpochSecond(1_900_000_000))

/** A [CredentialVault] in memory; [failure] makes saving throw. */
internal class MemoryVault : CredentialVault {
    val saved = mutableMapOf<Long, AccountCredentials>()
    var failure: Exception? = null

    override suspend fun save(accountId: Long, credentials: AccountCredentials) {
        failure?.let { throw it }
        saved[accountId] = credentials
    }

    override suspend fun load(accountId: Long) = saved[accountId]

    override suspend fun delete(accountId: Long) {
        saved.remove(accountId)
    }
}

internal class MemoryPendingChoices : PendingFolderChoices {
    val stored = mutableMapOf<Long, Map<String, Boolean>>()

    override fun save(accountId: Long, choices: Map<String, Boolean>) {
        stored[accountId] = choices
    }

    override fun peek(accountId: Long) = stored[accountId].orEmpty()

    override fun clear(accountId: Long) {
        stored.remove(accountId)
    }
}

internal class MemoryDocuments :
    DocumentSink,
    DocumentSource {
    val files = mutableMapOf<String, ByteArray>()
    var writeFails = false
    var readResult: DocumentRead? = null

    override suspend fun write(uri: String, bytes: ByteArray): Boolean {
        if (writeFails) return false
        files[uri] = bytes
        return true
    }

    override suspend fun read(uri: String, maxBytes: Int): DocumentRead =
        readResult ?: files[uri]?.let { DocumentRead.Bytes(it) } ?: DocumentRead.Unreadable
}
