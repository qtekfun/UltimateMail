// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.backup.NoPendingFolderChoices
import com.qtekfun.ultimatemail.domain.backup.PendingFolderChoices
import com.qtekfun.ultimatemail.domain.mail.MailFolder
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import javax.inject.Inject

/** Keeps the folders of an account in Room equal to the ones the server lists (RF-02). */
class FolderCatalog @Inject constructor(
    private val folders: FolderDao,
    private val pendingChoices: PendingFolderChoices = NoPendingFolderChoices
) {
    /** Returns the failure if the server could not list its folders; Room is then untouched. */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    suspend fun refresh(account: AccountEntity, session: MailSession): MailResult.Failure? {
        val listed = when (val result = session.listFolders()) {
            is MailResult.Success -> result.value
            is MailResult.Failure -> return result
        }
        // An empty list is a hiccup, not a mailbox with no folders: never wipe the account on it.
        if (listed.isEmpty()) return null
        val known = folders.all(account.id).map { it.path }.toSet()
        val gmail = account.imapHost.equals(GMAIL_HOST, ignoreCase = true)
        val (existing, added) = listed.partition { it.path in known }
        folders.insertNew(added.map { it.toEntity(account.id, gmail) })
        existing.forEach {
            val role = it.role.toLocal()
            val isLabel = gmail && role == FolderRole.OTHER
            folders.updateDescription(account.id, it.path, it.name, role, isLabel)
        }
        applyPendingChoices(account.id, listed)
        folders.deleteAllExcept(account.id, listed.map { it.path })
        return null
    }

    /**
     * The folder choices of an imported backup (RF-12) wait until the folders exist; this is the
     * first listing that has them. The Inbox and containers keep their rule, and a path the
     * server does not list any more is dropped.
     */
    private suspend fun applyPendingChoices(accountId: Long, listed: List<MailFolder>) {
        val choices = pendingChoices.peek(accountId)
        if (choices.isEmpty()) return
        listed.filter { it.selectable && it.role != MailFolderRole.INBOX }.forEach {
            choices[it.path]?.let { enabled -> folders.setSyncEnabled(accountId, it.path, enabled) }
        }
        pendingChoices.clear(accountId)
    }

    private fun MailFolder.toEntity(accountId: Long, gmail: Boolean): FolderEntity {
        val localRole = role.toLocal()
        return FolderEntity(
            accountId = accountId,
            path = path,
            name = name,
            role = localRole,
            // On Gmail every folder is a label.
            isLabel = gmail && localRole == FolderRole.OTHER,
            // Containers hold no messages; Gmail's All Mail and Starred repeat every other
            // folder, so they are opt-in (RF-11 lets the user choose).
            syncEnabled = selectable && localRole != FolderRole.ALL_MAIL &&
                localRole != FolderRole.STARRED
        )
    }

    private fun MailFolderRole.toLocal() = FolderRole.valueOf(name)

    private companion object {
        const val GMAIL_HOST = "imap.gmail.com"
    }
}
