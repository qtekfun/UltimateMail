// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.time.Instant

/**
 * Debug builds only: two made-up accounts of the reserved demo domain ([DemoData.DEMO_DOMAIN]) to
 * try the move / label picker: one with plain folders, one whose folders are Gmail labels. Neither
 * has credentials, so a sync requested for them can never reach a server, and no real account is
 * involved.
 */
object PickerDemoData {
    const val FOLDERS_EMAIL = "demo.picker.folders@${DemoData.DEMO_DOMAIN}"
    const val LABELS_EMAIL = "demo.picker.labels@${DemoData.DEMO_DOMAIN}"

    fun isPickerDemo(email: String) = email == FOLDERS_EMAIL || email == LABELS_EMAIL

    fun foldersAccount() = DemoData.accounts().first().copy(
        email = FOLDERS_EMAIL,
        displayName = "Demo folders",
        username = FOLDERS_EMAIL
    )

    fun labelsAccount(): AccountEntity = DemoData.accounts().first().copy(
        email = LABELS_EMAIL,
        displayName = "Demo labels",
        username = LABELS_EMAIL
    )

    private val userFolders = listOf(
        "Work/Invoices 2025/Facturas",
        "Work/Invoices 2025",
        "Work/Clients/Acme",
        "Personal/Familia",
        "Personal/Niños",
        "Personal/Año nuevo",
        "Projects/Über",
        "Проекты/Планы",
        "旅行/東京",
        "Ideas 💡",
        "Reference/A very long folder name that keeps going to see how the row wraps in large fonts"
    )

    /** Special folders, the user folders above and 150 numbered ones to scroll through. */
    fun folders(accountId: Long, labels: Boolean): List<FolderEntity> {
        val special = if (labels) {
            listOf(
                special(accountId, "INBOX", "INBOX", FolderRole.INBOX),
                special(accountId, "[Gmail]/Sent Mail", "Sent Mail", FolderRole.SENT),
                special(accountId, "[Gmail]/Drafts", "Drafts", FolderRole.DRAFTS),
                special(accountId, "[Gmail]/Trash", "Trash", FolderRole.TRASH),
                special(accountId, "[Gmail]/Spam", "Spam", FolderRole.JUNK),
                special(accountId, "[Gmail]/All Mail", "All Mail", FolderRole.ALL_MAIL),
                FolderEntity(accountId, "[Gmail]", "[Gmail]", syncEnabled = false)
            )
        } else {
            listOf(
                special(accountId, "INBOX", "INBOX", FolderRole.INBOX),
                special(accountId, "Sent", "Sent", FolderRole.SENT),
                special(accountId, "Drafts", "Drafts", FolderRole.DRAFTS),
                special(accountId, "Archive", "Archive", FolderRole.ARCHIVE),
                special(accountId, "Trash", "Trash", FolderRole.TRASH),
                special(accountId, "Junk", "Junk", FolderRole.JUNK)
            )
        }
        val own = userFolders + (1..NUMBERED).map { "Archive/2019/Folder %03d".format(it) }
        return special + own.map {
            FolderEntity(accountId, it, it.substringAfterLast('/'), isLabel = labels)
        }
    }

    private fun special(accountId: Long, path: String, name: String, role: FolderRole) =
        FolderEntity(accountId, path, name, role, uidValidity = 1, uidNext = 1)

    /** Six conversations in the Inbox; on the label account they carry different label sets. */
    fun messages(accountId: Long, labels: Boolean, now: Instant): List<MessageEntity> {
        val labelSets = listOf(
            listOf("\\Inbox"),
            listOf("\\Inbox", "Work/Invoices 2025"),
            listOf("\\Inbox", "Work/Invoices 2025", "Personal/Familia"),
            listOf("\\Inbox", "Ideas 💡"),
            listOf("\\Inbox", "Work/Invoices 2025/Facturas"),
            listOf("\\Inbox")
        )
        return labelSets.mapIndexed { index, set ->
            MessageEntity(
                accountId = accountId,
                folderPath = "INBOX",
                uid = index + 1L,
                messageId = "<picker-$accountId-$index@${DemoData.DEMO_DOMAIN}>",
                threadId = "picker-$accountId-$index",
                subject = "Demo message ${index + 1}",
                senderName = "Demo sender",
                senderAddress = "sender@example.test",
                sentAt = now.minusSeconds(index * SECONDS_APART),
                snippet = "Select some messages, then move or label them.",
                seen = true,
                labels = if (labels) set else emptyList()
            )
        }
    }

    private const val NUMBERED = 150
    private const val SECONDS_APART = 3_600L
}
