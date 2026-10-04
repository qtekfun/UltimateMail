// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import com.qtekfun.ultimatemail.domain.inbox.rowItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboxFilterTest {
    private val plain = rowItem()
    private val unread = rowItem(unreadCount = 1)
    private val starred = rowItem(flagged = true)
    private val attached = rowItem().copy(hasAttachments = true)
    private val all = listOf(plain, unread, starred, attached)

    @Test
    fun `all keeps everything`() {
        all.forEach { assertTrue(InboxFilter.ALL.matches(it)) }
    }

    @Test
    fun `each filter keeps only what it names`() {
        assertEquals(listOf(unread), all.filter(InboxFilter.UNREAD::matches))
        assertEquals(listOf(starred), all.filter(InboxFilter.STARRED::matches))
        assertEquals(listOf(attached), all.filter(InboxFilter.ATTACHMENTS::matches))
        assertFalse(InboxFilter.ATTACHMENTS.matches(plain))
    }

    @Test
    fun `the menu offers all, unread, starred and attachments, in that order`() {
        assertEquals(
            listOf(
                InboxFilter.ALL,
                InboxFilter.UNREAD,
                InboxFilter.STARRED,
                InboxFilter.ATTACHMENTS
            ),
            InboxFilter.entries
        )
    }
}
