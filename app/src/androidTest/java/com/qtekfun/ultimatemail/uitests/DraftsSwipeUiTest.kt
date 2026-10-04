// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swiping a draft away in the Drafts list hides it and offers Undo (the draft is only deleted
 * when the window ends); Undo brings the row back.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DraftsSwipeUiTest : UiTestBase() {
    private val subject = "Trip notes"
    private var draftId = 0L

    private val draftRow get() = tappable(subject)

    @Before
    fun seed() {
        val account = seedAccount("ana@example.com", "Ana", listOf("Lunch plans"))
        val now = clock.instant()
        draftId = runBlocking {
            database.draftDao().insert(
                DraftEntity(
                    key = "draft-key-1",
                    accountId = account.id,
                    kind = DraftKind.NEW,
                    toAddresses = listOf("Bob <bob@example.com>"),
                    subject = subject,
                    body = "Packing list",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        launchApp()
        waitFor(conversationRow("Lunch plans"))
        openDrawer()
        compose.onNode(isSelectable() and hasText(text(R.string.folder_drafts))).performClick()
        waitFor(draftRow)
    }

    private fun stillStored() = runBlocking { database.draftDao().get(draftId) }

    @Test
    fun swipingADraftAwayShowsDiscardedWithUndoAndUndoBringsItBack() {
        compose.onNode(draftRow).performTouchInput { swipeRight() }

        waitUntilGone(draftRow)
        waitForText(text(R.string.notice_draft_discarded))
        compose.onNode(tappable(text(R.string.notice_undo))).assertIsDisplayed()
        // Hidden, not deleted yet: Undo has something to bring back.
        assertNotNull(stillStored())

        compose.onNode(tappable(text(R.string.notice_undo))).performClick()

        waitFor(draftRow)
        compose.onNode(draftRow).assertIsDisplayed()
        assertNotNull(stillStored())
    }
}
