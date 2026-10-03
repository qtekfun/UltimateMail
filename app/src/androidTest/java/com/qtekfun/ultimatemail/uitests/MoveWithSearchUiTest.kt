// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.OperationType
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 7, "move with search": a long press selects a conversation, "Move to..." opens the
 * picker, typing filters the folders (ignoring case and accents) and tapping the result moves the
 * messages.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MoveWithSearchUiTest : UiTestBase() {
    private val lunch = "Lunch plans"
    private val report = "Quarterly report"

    private fun row(subject: String) = hasContentDescription(subject, substring = true)

    private fun destination(name: String) = hasContentDescription(name, substring = true)

    @Test
    fun selectingSearchingAndTappingAFolderMovesTheConversation() {
        val account = seedAccount("ana@example.com", "Ana", listOf(lunch, report))
        launchApp()
        waitFor(row(lunch))

        compose.onNode(row(lunch)).performTouchInput { longClick() }
        waitForText(plural(R.plurals.inbox_selection_count, 1, 1))
        compose.onNodeWithContentDescription(text(R.string.inbox_selection_more)).performClick()
        compose.onNode(
            hasText(text(R.string.inbox_action_move)) and hasClickAction()
        ).performClick()

        // The picker lists every folder at first.
        waitForText(text(R.string.picker_title_move))
        waitFor(destination(text(R.string.folder_archive)))
        waitFor(destination(ACCENTED_FOLDER))

        // Nothing matches this; the list says so.
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        waitForText(text(R.string.picker_no_matches, "zzz"))

        // Upper case and no accent still find "Facturación", and the other folders go away.
        compose.onNode(hasSetTextAction()).performTextReplacement("FACTURACION")
        waitFor(destination(ACCENTED_FOLDER))
        waitUntilGone(destination(text(R.string.folder_archive)))
        waitUntilGone(destination(text(R.string.folder_trash)))

        compose.onNode(destination(ACCENTED_FOLDER)).performClick()

        waitUntilGone(row(lunch))
        waitForText(plural(R.plurals.picker_result_moved, 1, 1, ACCENTED_FOLDER))
        compose.onNode(hasText(text(R.string.picker_undo)) and hasClickAction()).assertIsDisplayed()
        // The other conversation stayed where it was.
        compose.onNode(row(report)).assertIsDisplayed()
        val queued = runBlocking { database.pendingOperationDao().all(account.id) }
        assertTrue(
            "A move to the chosen folder should be queued, found ${queued.map { it.type }}",
            queued.any { it.type == OperationType.MOVE && it.payload == ACCENTED_FOLDER }
        )
    }
}
