// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Removing an account deletes its credentials and all its local data (RF-01). */
class AccountRemoval @Inject constructor(
    database: UltimateMailDatabase,
    private val vault: CredentialVault,
    private val attachments: AttachmentStorage,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val accounts = database.accountDao()

    /** Credentials go first, so a failure never leaves secrets behind without their account. */
    suspend fun remove(accountId: Long) = withContext(io) {
        vault.delete(accountId)
        // Foreign keys cascade to folders, messages, attachments and pending operations.
        accounts.delete(accountId)
        // The downloaded attachments are files, which the cascade does not reach.
        attachments.deleteAccount(accountId)
    }
}
