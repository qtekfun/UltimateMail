// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BulkAvailabilityTest {
    private val targets = RowTargets(
        mapOf(
            1L to accountFolders(),
            2L to accountFolders(2, archive = false, trash = false)
        )
    )

    @Test
    fun `read is a smart toggle that marks all read while any is unread`() {
        val mixed = listOf(rowItem(unreadCount = 0), rowItem(threadId = "b", unreadCount = 1))

        assertEquals(RowChange.MARK_READ, BulkAvailability.of(mixed, targets).readChange)
    }

    @Test
    fun `read marks all unread when everything is read`() {
        val read = listOf(rowItem(unreadCount = 0), rowItem(threadId = "b", unreadCount = 0))

        assertEquals(RowChange.MARK_UNREAD, BulkAvailability.of(read, targets).readChange)
    }

    @Test
    fun `star stars all while any is not starred, else unstars all`() {
        val mixed = listOf(rowItem(flagged = true), rowItem(threadId = "b", flagged = false))
        val starred = listOf(rowItem(flagged = true), rowItem(threadId = "b", flagged = true))

        assertEquals(RowChange.STAR, BulkAvailability.of(mixed, targets).starChange)
        assertEquals(RowChange.UNSTAR, BulkAvailability.of(starred, targets).starChange)
    }

    @Test
    fun `archive and delete are on when at least one conversation can take them`() {
        val items = listOf(rowItem(accountId = 1), rowItem(accountId = 2, threadId = "b"))

        val available = BulkAvailability.of(items, targets)

        assertTrue(available.canArchive)
        assertTrue(available.canDelete)
    }

    @Test
    fun `archive and delete are off when none can`() {
        val available = BulkAvailability.of(listOf(rowItem(accountId = 2)), targets)

        assertFalse(available.canArchive)
        assertFalse(available.canDelete)
    }

    @Test
    fun `move needs one account`() {
        val one = listOf(rowItem(accountId = 1), rowItem(accountId = 1, threadId = "b"))
        val two = listOf(rowItem(accountId = 1), rowItem(accountId = 2, threadId = "b"))

        assertTrue(BulkAvailability.of(one, targets).canMove)
        assertFalse(BulkAvailability.of(two, targets).canMove)
        assertFalse(BulkAvailability.of(emptyList(), targets).canMove)
    }
}
