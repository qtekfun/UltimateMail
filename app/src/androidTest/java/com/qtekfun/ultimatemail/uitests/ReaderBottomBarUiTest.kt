// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.OperationType
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar and the overflow menu of the reader (iOS style): Trash, Archive, Move, Reply
 * (a menu with Reply, Reply all and Forward) and Compose; Star and Mark as unread in the menu.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReaderBottomBarUiTest : UiTestBase() {
    private val subject = "Quarterly report"
    private val other = "Lunch plans"
    private var accountId = 0L

    private fun button(label: Int) = hasContentDescription(text(label))

    @Before
    fun seed() {
        accountId = seedAccount("ana@example.com", "Ana", listOf(other, subject)).id
        launchApp()
        waitFor(conversationRow(subject))
        compose.onNode(conversationRow(subject)).performClick()
        waitFor(hasText(subject))
        waitFor(button(R.string.conversation_more))
    }

    private fun queuedMoves() = runBlocking { database.pendingOperationDao().all(accountId) }
        .filter { it.type == OperationType.MOVE }
        .map { it.payload }

    @Test
    fun theBottomBarHasAllItsButtons() {
        listOf(
            R.string.conversation_delete,
            R.string.conversation_archive,
            R.string.inbox_action_move,
            R.string.conversation_reply_menu,
            R.string.compose_fab
        ).forEach { compose.onNode(button(it)).assertIsDisplayed() }
    }

    @Test
    fun archiveAndTrashMoveTheConversationAway() {
        compose.onNode(button(R.string.conversation_archive)).performClick()

        waitFor(conversationRow(other))
        waitUntilGone(conversationRow(subject))
        assertTrue("Archive should be queued, found ${queuedMoves()}", "Archive" in queuedMoves())
    }

    @Test
    fun trashMovesTheConversationToTheTrashFolder() {
        compose.onNode(button(R.string.conversation_delete)).performClick()

        waitFor(conversationRow(other))
        waitUntilGone(conversationRow(subject))
        assertTrue("Trash should be queued, found ${queuedMoves()}", "Trash" in queuedMoves())
    }

    @Test
    fun moveOpensThePicker() {
        compose.onNode(button(R.string.inbox_action_move)).performClick()

        waitForText(text(R.string.picker_title_move))
        waitFor(hasContentDescription(ACCENTED_FOLDER, substring = true))
    }

    @Test
    fun replyOpensAMenuWithReplyReplyAllAndForward() {
        compose.onNode(button(R.string.conversation_reply_menu)).performClick()

        waitFor(tappable(text(R.string.compose_reply_all)))
        compose.onNode(tappable(text(R.string.compose_reply))).assertIsDisplayed()
        compose.onNode(tappable(text(R.string.compose_forward))).assertIsDisplayed()

        compose.onNode(tappable(text(R.string.compose_forward))).performClick()

        // The title of the composer, not the menu item (which can be tapped).
        waitFor(hasText(text(R.string.compose_forward)) and !hasClickAction())
    }

    @Test
    fun composeStartsANewMessage() {
        compose.onNode(button(R.string.compose_fab)).performClick()

        waitForText(text(R.string.compose_title_new))
    }

    @Test
    fun theOverflowMenuHasStarAndMarkUnread() {
        compose.onNode(button(R.string.conversation_more)).performClick()
        waitFor(tappable(text(R.string.conversation_mark_unread)))
        compose.onNode(tappable(text(R.string.conversation_star))).assertIsDisplayed()

        compose.onNode(tappable(text(R.string.conversation_star))).performClick()

        // The menu now offers to take the star away.
        compose.onNode(button(R.string.conversation_more)).performClick()
        waitFor(tappable(text(R.string.conversation_unstar)))
    }
}
