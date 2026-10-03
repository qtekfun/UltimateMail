// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Removing an account deletes its credentials and all its local data (RF-01): that includes its
 * drafts, its outbox and the files attached to them.
 */
class AccountRemoval @Inject constructor(
    database: UltimateMailDatabase,
    private val vault: CredentialVault,
    private val attachments: AttachmentStorage,
    private val outbox: OutboxFileStorage,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()
    private val drafts = database.draftDao()

    /** Credentials go first, so a failure never leaves secrets behind without their account. */
    suspend fun remove(accountId: Long) = withContext(io) {
        vault.delete(accountId)
        // The files of the account's drafts are found through their rows, which go with it.
        val draftIds = drafts.idsOf(accountId)
        // Foreign keys cascade to folders, messages, attachments, drafts and pending operations.
        accounts.delete(accountId)
        draftIds.forEach(outbox::deleteDraft)
        // The downloaded attachments are files, which the cascade does not reach.
        attachments.deleteAccount(accountId)
    }
}
