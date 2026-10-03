// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.time.Duration
import java.time.Instant

/**
 * Debug builds only: made-up accounts, folders and conversations to look at the inbox while the
 * sync engine does not exist. Every demo account uses the reserved `.invalid` domain
 * ([DEMO_DOMAIN]), which is also how they are found and removed; real accounts are never touched.
 */
object DemoData {
    const val DEMO_DOMAIN = "seed.invalid"

    fun isDemo(email: String) = email.endsWith("@$DEMO_DOMAIN")

    fun accounts() = listOf(
        demoAccount("home", "Demo Home"),
        demoAccount("work", "Demo Work")
    )

    /** An account for volume tests: it only gets the INBOX that `seed-bulk` fills. */
    fun bulkAccount() = demoAccount("bulk", "Demo Bulk")

    private fun demoAccount(local: String, name: String) = AccountEntity(
        email = "demo.$local@$DEMO_DOMAIN",
        displayName = name,
        username = "demo.$local@$DEMO_DOMAIN",
        authType = AuthType.PASSWORD,
        imapHost = "imap.$DEMO_DOMAIN",
        imapPort = 993,
        imapSecurity = ConnectionSecurity.TLS,
        smtpHost = "smtp.$DEMO_DOMAIN",
        smtpPort = 587,
        smtpSecurity = ConnectionSecurity.STARTTLS
    )

    /** INBOX (synced), Sent, a nested label and an Archive that was never synced. */
    fun folders(accountId: Long) = listOf(
        FolderEntity(accountId, "INBOX", "INBOX", FolderRole.INBOX, uidValidity = 1, uidNext = 1),
        FolderEntity(accountId, "Sent", "Sent", FolderRole.SENT, uidValidity = 1, uidNext = 1),
        FolderEntity(
            accountId,
            "Work/Invoices",
            "Invoices",
            FolderRole.OTHER,
            isLabel = true,
            uidValidity = 1
        ),
        FolderEntity(accountId, "Archive", "Archive", FolderRole.ARCHIVE)
    )

    private class Sender(val name: String, val address: String)

    private val senders = listOf(
        Sender("Ana García", "ana.garcia@example.test"),
        Sender("李雷", "lilei@example.test"),
        Sender("Иван Петров", "ivan@example.test"),
        Sender("😀 Party Planner", "party@example.test"),
        Sender("محمد علي", "mohamed@example.test"),
        Sender("", "no-name@example.test"),
        Sender("GitHub", "notifications@example.test"),
        Sender("Élodie Dupont", "elodie@example.test"),
        Sender("Dr. Bartholomew Montgomery-Featherstonehaugh III", "long.name@example.test")
    )

    private val subjects = listOf(
        "Lunch on Friday?",
        "Your invoice for September",
        "Reunión de equipo: nuevo calendario",
        "会议纪要",
        "Re: Re: Re: Fwd: the quarterly planning document with a really long subject line that never ends",
        "🎉 You are invited!",
        "",
        "Security alert",
        "Weekly digest"
    )

    private val snippets = listOf(
        "Hi! Are you free around 13:00? I was thinking about that new place near the station.",
        "Attached you will find the invoice. Please let us know if anything is missing.",
        "Os adjunto el nuevo calendario. Por favor, revisadlo antes del jueves y decidme algo.",
        "Short one.",
        ""
    )

    private val labelSets = listOf(
        emptyList(),
        listOf("Work"),
        listOf("\\Inbox", "Work/Invoices", "Billing"),
        listOf("Clients/Acme", "Urgent", "Travel", "Personal", "Later"),
        emptyList()
    )

    private val offsets = listOf(
        Duration.ofMinutes(4),
        Duration.ofHours(3),
        Duration.ofHours(20),
        Duration.ofDays(2),
        Duration.ofDays(5),
        Duration.ofDays(9),
        Duration.ofDays(40),
        Duration.ofDays(500)
    )

    /**
     * About [count] conversations of INBOX, mixing read and unread, attachments, stars, labels
     * and pending changes, plus one thread of 12 messages. [seed] varies accounts.
     */
    fun inboxMessages(accountId: Long, now: Instant, count: Int, seed: Int): List<MessageEntity> {
        val messages = mutableListOf<MessageEntity>()
        var uid = 1L
        for (i in 0 until count) {
            val pick = i + seed
            val sender = senders[pick % senders.size]
            messages += MessageEntity(
                accountId = accountId,
                folderPath = "INBOX",
                uid = uid,
                messageId = "<demo-$accountId-$uid@$DEMO_DOMAIN>",
                threadId = "demo-$accountId-$i",
                subject = subjects[pick % subjects.size],
                senderName = sender.name,
                senderAddress = sender.address,
                sentAt = now.minus(offsets[i % offsets.size]).minusSeconds(i * SECONDS_PER_STEP),
                snippet = snippets[pick % snippets.size],
                seen = pick % 3 != 0,
                flagged = pick % 7 == 0,
                hasAttachments = pick % 4 == 0,
                labels = labelSets[pick % labelSets.size],
                pendingSync = pick % 11 == 0
            )
            uid++
        }
        // A conversation of 12 messages, the newest one unread, with a label and an attachment.
        for (k in 0 until THREAD_SIZE) {
            messages += MessageEntity(
                accountId = accountId,
                folderPath = "INBOX",
                uid = uid,
                messageId = "<demo-thread-$accountId-$uid@$DEMO_DOMAIN>",
                threadId = "demo-$accountId-thread",
                subject = "Trip planning",
                senderName = if (k % 2 == 0) "Ana García" else "Me",
                senderAddress = if (k % 2 == 0) "ana.garcia@example.test" else "demo@$DEMO_DOMAIN",
                sentAt = now.minus(
                    Duration.ofMinutes(30)
                ).minusSeconds((THREAD_SIZE - k) * SECONDS_PER_STEP),
                snippet = "Message ${k + 1} of the thread about the trip.",
                seen = k < THREAD_SIZE - 1,
                hasAttachments = k == THREAD_SIZE - 1,
                labels = listOf("Travel"),
                flagged = k == 0
            )
            uid++
        }
        return messages
    }

    const val THREAD_SIZE = 12
    private const val SECONDS_PER_STEP = 90L
}
