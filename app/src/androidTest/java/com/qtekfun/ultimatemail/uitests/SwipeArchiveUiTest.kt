// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 7, "swipe to archive": swiping a conversation right (the default gesture) takes it
 * out of the list and offers Undo; Undo brings it back and leaves nothing in the queue.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SwipeArchiveUiTest : UiTestBase() {
    private val lunch = "Lunch plans"
    private val report = "Quarterly report"

    private fun row(subject: String) = hasContentDescription(subject, substring = true)

    private val undo get() = hasText(text(R.string.notice_undo)) and hasClickAction()

    /** A fresh install marks read/unread on the right swipe; these tests are about archiving. */
    private fun rightSwipeArchives() {
        SettingsRepository(settings).setSwipeRight(SwipeAction.ARCHIVE)
    }

    @Test
    fun swipingRightArchivesAndUndoBringsTheConversationBack() {
        rightSwipeArchives()
        val account = seedAccount("ana@example.com", "Ana", listOf(lunch, report))
        launchApp()
        waitFor(row(lunch))

        compose.onNode(row(lunch)).performTouchInput { swipeRight() }

        waitUntilGone(row(lunch))
        compose.onNode(hasText(plural(R.plurals.notice_archived, 1))).assertIsDisplayed()
        compose.onNode(undo).assertIsDisplayed()
        // Only the swiped conversation left.
        compose.onNode(row(report)).assertIsDisplayed()

        compose.onNode(undo).performClick()

        waitFor(row(lunch))
        compose.onNode(row(report)).assertIsDisplayed()
        val queued = runBlocking { database.pendingOperationDao().all(account.id) }
        assertTrue(
            "Undo should leave no move to Archive in the queue, found ${queued.map { it.payload }}",
            queued.none { it.type == OperationType.MOVE && it.payload == ARCHIVE }
        )
    }

    @Test
    fun aSecondActionMakesTheFirstOneFinalAndItReachesTheServer() {
        scheduler.runSyncs = true
        rightSwipeArchives()
        val account = seedAccount("ana@example.com", "Ana", listOf(lunch, report))
        launchApp()
        waitFor(row(lunch))

        compose.onNode(row(lunch)).performTouchInput { swipeRight() }
        waitUntilGone(row(lunch))
        // A new notice replaces the one with Undo: what it could undo is final, and is synced.
        compose.onNode(row(report)).performTouchInput { swipeRight() }
        waitUntilGone(row(report))

        compose.waitUntil(SYNC_TIMEOUT) { lunch in account.mailbox.subjectsIn(ARCHIVE) }
        assertTrue(lunch !in account.mailbox.subjectsIn(INBOX))
    }

    private companion object {
        const val ARCHIVE = "Archive"
        const val SYNC_TIMEOUT = 30_000L
    }
}
