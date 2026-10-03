// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.folder.FolderTree

internal fun entity(
    path: String,
    role: FolderRole = FolderRole.OTHER,
    isLabel: Boolean = false,
    syncEnabled: Boolean = true,
    name: String = path.substringAfterLast('/')
) = FolderEntity(
    accountId = 1,
    path = path,
    name = name,
    role = role,
    isLabel = isLabel,
    syncEnabled = syncEnabled
)

internal fun tree(vararg folders: FolderEntity): FolderTree =
    FolderTree.build(folders.toList(), emptyMap())

/** A plain IMAP account. */
internal fun imapTree(): FolderTree = tree(
    entity("INBOX", FolderRole.INBOX),
    entity("Drafts", FolderRole.DRAFTS),
    entity("Sent", FolderRole.SENT),
    entity("Archive", FolderRole.ARCHIVE),
    entity("Trash", FolderRole.TRASH),
    entity("Junk", FolderRole.JUNK),
    entity("Work/Invoices"),
    entity("Work/Clients"),
    entity("Personal")
)

/** A Gmail account: everything not special is a label, with Gmail's own container. */
internal fun gmailTree(): FolderTree = tree(
    entity("INBOX", FolderRole.INBOX),
    entity("[Gmail]", syncEnabled = false),
    entity("[Gmail]/Sent Mail", FolderRole.SENT),
    entity("[Gmail]/Drafts", FolderRole.DRAFTS),
    entity("[Gmail]/Trash", FolderRole.TRASH),
    entity("[Gmail]/Spam", FolderRole.JUNK),
    entity("[Gmail]/All Mail", FolderRole.ALL_MAIL),
    entity("[Gmail]/Important", isLabel = true),
    entity("Work/Invoices", isLabel = true),
    entity("Work/Clients", isLabel = true),
    entity("Personal", isLabel = true)
)
