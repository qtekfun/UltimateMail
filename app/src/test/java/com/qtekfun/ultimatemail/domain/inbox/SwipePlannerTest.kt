// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.SwipeActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwipePlannerTest {
    private val targets = RowTargets(mapOf(1L to accountFolders()))

    private fun decide(action: SwipeAction, item: ConversationItem = rowItem()) =
        SwipePlanner.decide(action, item, targets)

    @Test
    fun `the default swipes archive to the right and delete to the left`() {
        val defaults = SwipeActions()

        assertEquals(
            SwipeDecision.Apply(RowChange.ARCHIVE),
            decide(SwipeDirection.RIGHT.action(defaults))
        )
        assertEquals(
            SwipeDecision.Apply(RowChange.DELETE),
            decide(SwipeDirection.LEFT.action(defaults))
        )
    }

    @Test
    fun `nothing configured means the row does not move`() {
        val decision = decide(SwipeAction.NONE)

        assertEquals(SwipeDecision.Inactive, decision)
        assertFalse(decision.draggable)
    }

    @Test
    fun `move asks for a folder`() {
        assertEquals(SwipeDecision.PickFolder, decide(SwipeAction.MOVE))
    }

    @Test
    fun `read toggles by the state of the conversation`() {
        assertEquals(
            SwipeDecision.Apply(RowChange.MARK_READ),
            decide(SwipeAction.TOGGLE_READ, rowItem(unreadCount = 2))
        )
        assertEquals(
            SwipeDecision.Apply(RowChange.MARK_UNREAD),
            decide(SwipeAction.TOGGLE_READ, rowItem(unreadCount = 0))
        )
    }

    @Test
    fun `star toggles by the state of the conversation`() {
        assertEquals(
            SwipeDecision.Apply(RowChange.STAR),
            decide(SwipeAction.TOGGLE_STAR, rowItem(flagged = false))
        )
        assertEquals(
            SwipeDecision.Apply(RowChange.UNSTAR),
            decide(SwipeAction.TOGGLE_STAR, rowItem(flagged = true))
        )
    }

    @Test
    fun `an account without Archive or Trash is told so and the row springs back`() {
        val bare = RowTargets(mapOf(1L to accountFolders(archive = false, trash = false)))

        assertEquals(
            SwipeDecision.Blocked(SwipeBlock.NO_ARCHIVE_FOLDER),
            SwipePlanner.decide(SwipeAction.ARCHIVE, rowItem(), bare)
        )
        assertEquals(
            SwipeDecision.Blocked(SwipeBlock.NO_TRASH_FOLDER),
            SwipePlanner.decide(SwipeAction.DELETE, rowItem(), bare)
        )
        assertTrue(SwipeDecision.Blocked(SwipeBlock.NO_TRASH_FOLDER).draggable)
    }

    @Test
    fun `archiving from the archive or deleting from the trash is not offered`() {
        assertEquals(
            SwipeDecision.Inactive,
            decide(SwipeAction.ARCHIVE, rowItem(folderPath = "Archive"))
        )
        assertEquals(
            SwipeDecision.Inactive,
            decide(SwipeAction.ARCHIVE, rowItem(folderPath = "Trash"))
        )
        assertEquals(
            SwipeDecision.Inactive,
            decide(SwipeAction.DELETE, rowItem(folderPath = "Trash"))
        )
        // Deleting from the archive is fine.
        assertEquals(
            SwipeDecision.Apply(RowChange.DELETE),
            decide(SwipeAction.DELETE, rowItem(folderPath = "Archive"))
        )
    }

    @Test
    fun `gmail all mail counts as the archive`() {
        val gmail = RowTargets(
            mapOf(
                1L to listOf(
                    folder(1),
                    folder(1, "[Gmail]/All Mail", FolderRole.ALL_MAIL),
                    folder(1, "[Gmail]/Trash", FolderRole.TRASH)
                )
            )
        )

        assertEquals(
            SwipeDecision.Apply(RowChange.ARCHIVE),
            SwipePlanner.decide(SwipeAction.ARCHIVE, rowItem(), gmail)
        )
    }

    @Test
    fun `while the folders of the account are unknown archive and delete wait`() {
        val unknown = RowTargets()

        assertEquals(
            SwipeDecision.Inactive,
            SwipePlanner.decide(SwipeAction.ARCHIVE, rowItem(), unknown)
        )
        assertEquals(
            SwipeDecision.Inactive,
            SwipePlanner.decide(SwipeAction.DELETE, rowItem(), unknown)
        )
    }

    @Test
    fun `only archive and delete take the row out of the list`() {
        assertEquals(
            setOf(RowChange.ARCHIVE, RowChange.DELETE),
            RowChange.entries.filter { it.leavesList }.toSet()
        )
    }
}
