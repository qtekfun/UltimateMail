// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.time.Instant

fun account(email: String = "ana@example.test") = AccountEntity(
    email = email,
    displayName = "Ana",
    username = email,
    authType = AuthType.PASSWORD,
    imapHost = "imap.example.test",
    imapPort = 993,
    imapSecurity = ConnectionSecurity.TLS,
    smtpHost = "smtp.example.test",
    smtpPort = 587,
    smtpSecurity = ConnectionSecurity.STARTTLS
)

fun folder(accountId: Long, path: String = "INBOX", role: FolderRole = FolderRole.INBOX) =
    FolderEntity(
        accountId = accountId,
        path = path,
        name = path.substringAfterLast('/'),
        role = role
    )

fun message(
    accountId: Long,
    uid: Long,
    folderPath: String = "INBOX",
    threadId: String = "t$uid",
    subject: String = "Subject $uid",
    seen: Boolean = false,
    sentAt: Long = uid * 1000,
    bodyText: String? = null,
    senderName: String = "Bob",
    senderAddress: String = "bob@example.test",
    snippet: String = "",
    flagged: Boolean = false,
    hasAttachments: Boolean = false,
    labels: List<String> = emptyList(),
    pendingSync: Boolean = false
) = MessageEntity(
    accountId = accountId,
    folderPath = folderPath,
    uid = uid,
    messageId = "<$uid@example.test>",
    threadId = threadId,
    subject = subject,
    senderName = senderName,
    senderAddress = senderAddress,
    sentAt = Instant.ofEpochMilli(sentAt),
    snippet = snippet,
    seen = seen,
    flagged = flagged,
    hasAttachments = hasAttachments,
    labels = labels,
    bodyText = bodyText,
    pendingSync = pendingSync
)
