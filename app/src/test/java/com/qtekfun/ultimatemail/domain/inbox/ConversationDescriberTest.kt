// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConversationDescriberTest {
    private val texts = object : DescriptionTexts {
        override val unread = "unread"
        override val noSubject = "no subject"
        override val hasAttachment = "attachment"
        override val flagged = "starred"
        override val pendingSync = "pending"

        override fun from(sender: String) = "from $sender"

        override fun messages(count: Int) = "$count messages"

        override fun labels(names: String) = "labels $names"

        override fun account(name: String) = "account $name"
    }
    private val describer = ConversationDescriber(texts)

    private fun item(
        senderName: String = "Ana",
        subject: String = "Lunch",
        snippet: String = "",
        messageCount: Int = 1,
        unreadCount: Int = 0,
        flagged: Boolean = false,
        hasAttachments: Boolean = false,
        pendingSync: Boolean = false
    ) = ConversationItem(
        accountId = 1,
        folderPath = "INBOX",
        threadId = "t1",
        latestMessageId = 10,
        senderName = senderName,
        senderAddress = "ana@example.test",
        subject = subject,
        snippet = snippet,
        sentAt = Instant.EPOCH,
        messageCount = messageCount,
        unreadCount = unreadCount,
        flagged = flagged,
        hasAttachments = hasAttachments,
        labels = emptyList(),
        pendingSync = pendingSync
    )

    @Test
    fun `a plain read message says who, what and when`() {
        assertEquals("from Ana, Lunch, 12:30", describer.describe(item(), "12:30"))
    }

    @Test
    fun `everything that applies is said, in a fixed order, ending with the snippet`() {
        val labels = LabelPresentation.summarize(listOf("Work", "Clients", "Billing", "Misc"))
        val marker = AccountMarker("Home", 1)

        val text = describer.describe(
            item(
                unreadCount = 2,
                messageCount = 12,
                hasAttachments = true,
                flagged = true,
                pendingSync = true,
                snippet = "  See you at one  "
            ),
            time = "12:30",
            labels = labels,
            account = marker
        )

        assertEquals(
            "unread, from Ana, Lunch, 12:30, 12 messages, attachment, starred, " +
                "labels Work, Clients, Billing +1, account Home, pending, See you at one",
            text
        )
    }

    @Test
    fun `a single message does not say the message count`() {
        assertEquals("from Ana, Lunch, now", describer.describe(item(messageCount = 1), "now"))
    }

    @Test
    fun `without a sender name the address is spoken and without subject a placeholder`() {
        val text = describer.describe(item(senderName = " ", subject = " "), "now")

        assertEquals("from ana@example.test, no subject, now", text)
    }

    @Test
    fun `account markers get a colour slot and a short name`() {
        fun account(id: Long, name: String) =
            AccountSummary(id, "me@home.test", name, AuthType.PASSWORD)

        assertEquals(AccountMarker("Home", 3), AccountMarker.of(account(3, "Home")))
        assertEquals(AccountMarker("me", 3), AccountMarker.of(account(13, " ")))
    }
}
