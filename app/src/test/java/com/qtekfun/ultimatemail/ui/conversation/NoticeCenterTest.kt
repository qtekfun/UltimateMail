// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import com.qtekfun.ultimatemail.domain.conversation.RecordingScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoticeCenterTest {
    private val scheduler = RecordingScheduler()
    private val center = NoticeCenter(scheduler)
    private var reverted = 0

    private fun undo(vararg accounts: Long) = PendingUndo(accounts.toSet()) { reverted++ }

    @Test
    fun `a notice with something to undo is undoable and carries its count`() {
        center.post(NoticeKind.ARCHIVED, count = 3, undo = undo(1))

        val shown = center.notice.value!!
        assertEquals(NoticeKind.ARCHIVED, shown.kind)
        assertEquals(3, shown.count)
        assertTrue(shown.undoable)
    }

    @Test
    fun `a plain notice is not undoable`() {
        center.post(NoticeKind.MOVE_SOON)

        assertFalse(center.notice.value!!.undoable)
    }

    @Test
    fun `taking the undo hands over what to revert and clears the notice without syncing`() =
        runTest {
            val id = center.post(NoticeKind.DELETED, undo = undo(1))

            center.takeUndo(id)!!.revert()

            assertEquals(1, reverted)
            assertNull(center.notice.value)
            assertTrue(scheduler.requests.isEmpty())
        }

    @Test
    fun `committing syncs every account of the change`() {
        val id = center.post(NoticeKind.ARCHIVED, undo = undo(1, 2))

        center.commit(id)

        assertEquals(listOf<Long?>(1, 2), scheduler.requests)
        assertNull(center.notice.value)
        assertNull(center.takeUndo(id))
    }

    @Test
    fun `a new notice makes the one it replaces final`() {
        center.post(NoticeKind.ARCHIVED, undo = undo(7))

        center.post(NoticeKind.STARRED, undo = undo(8))

        assertEquals(listOf<Long?>(7), scheduler.requests)
        assertEquals(NoticeKind.STARRED, center.notice.value!!.kind)
    }

    @Test
    fun `answering a stale id changes nothing`() {
        val first = center.post(NoticeKind.ARCHIVED, undo = undo(1))
        center.post(NoticeKind.MOVE_SOON)

        center.commit(first)
        center.shown(first)

        assertEquals(NoticeKind.MOVE_SOON, center.notice.value!!.kind)
        assertEquals(listOf<Long?>(1), scheduler.requests)
    }
}
