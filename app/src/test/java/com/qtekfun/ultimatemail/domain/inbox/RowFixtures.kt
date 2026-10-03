// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.time.Instant

fun rowItem(
    accountId: Long = 1,
    folderPath: String = "INBOX",
    threadId: String = "t1",
    unreadCount: Int = 0,
    flagged: Boolean = false,
    latestMessageId: Long = 1
) = ConversationItem(
    accountId = accountId,
    folderPath = folderPath,
    threadId = threadId,
    latestMessageId = latestMessageId,
    senderName = "Bob",
    senderAddress = "bob@example.test",
    subject = "Subject",
    snippet = "",
    sentAt = Instant.EPOCH,
    messageCount = 1,
    unreadCount = unreadCount,
    flagged = flagged,
    hasAttachments = false,
    labels = emptyList(),
    pendingSync = false
)

/** An account with INBOX, plus Archive and Trash unless left out. */
fun accountFolders(accountId: Long = 1, archive: Boolean = true, trash: Boolean = true) =
    buildList {
        add(folder(accountId))
        if (archive) add(folder(accountId, "Archive", FolderRole.ARCHIVE))
        if (trash) add(folder(accountId, "Trash", FolderRole.TRASH))
    }
