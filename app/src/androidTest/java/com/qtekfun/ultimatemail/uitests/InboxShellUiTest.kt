// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list screen as the iOS-style redesign made it: the large title, the floating bottom bar
 * (filter, Search, Compose), the filter menu, and the Edit mode with its selection circles.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InboxShellUiTest : UiTestBase() {
    private val unreadSubject = "Lunch plans"
    private val starredSubject = "Quarterly report"
    private val attachmentSubject = "Holiday photos"

    private val allRows get() = listOf(unreadSubject, starredSubject, attachmentSubject)

    private val edit get() = tappable(text(R.string.inbox_edit))

    private val done get() = tappable(text(R.string.inbox_done))

    private val filterButton get() = hasContentDescription(text(R.string.inbox_filter_button))

    @Before
    fun seed() {
        seedAccount("ana@example.com", "Ana") {
            val now = clock.instant()
            deliver(INBOX, unreadSubject, now.minus(Duration.ofHours(1)))
            deliver(
                INBOX,
                starredSubject,
                now.minus(Duration.ofHours(2)),
                flags = MessageFlags(seen = true, flagged = true)
            )
            deliver(
                INBOX,
                attachmentSubject,
                now.minus(Duration.ofHours(3)),
                flags = MessageFlags(seen = true),
                hasAttachments = true
            )
        }
        launchApp()
        waitFor(conversationRow(unreadSubject))
    }

    private fun assertOnlyShowing(vararg subjects: String) {
        allRows.forEach { subject ->
            if (subject in subjects) {
                waitFor(conversationRow(subject))
            } else {
                waitUntilGone(conversationRow(subject))
            }
        }
    }

    private fun pickFilter(label: Int) {
        compose.onNode(filterButton).performClick()
        compose.onNode(tappable(text(label))).performClick()
    }

    /** How many conversation rows (not the rows of the side menu behind them) match [state]. */
    private fun rowsThat(state: SemanticsMatcher): Int {
        val anyRow = allRows.map { conversationRow(it) }.reduce { a, b -> a or b }
        return compose.onAllNodes(anyRow and state).fetchSemanticsNodes().size
    }

    @Test
    fun theLargeTitleNamesTheMailbox() {
        // The large title is the text of the bar that is not a menu row; the bar also keeps a
        // collapsed copy that is not on screen while the list is at the top.
        val title = hasText(text(R.string.folder_inbox), substring = true) and !isSelectable()
        waitFor(title)
        val shown = compose.onAllNodes(title).fetchSemanticsNodes().indices
            .count { compose.onAllNodes(title)[it].isDisplayed() }
        assertTrue("The mailbox name should be on screen as the title", shown >= 1)
    }

    @Test
    fun theBottomBarHasFilterSearchAndCompose() {
        compose.onNode(filterButton).assertIsDisplayed()
        compose.onNode(tappable(text(R.string.search_open))).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.compose_fab)).assertIsDisplayed()
    }

    @Test
    fun theSearchFieldOpensTheSearchScreen() {
        compose.onNode(tappable(text(R.string.search_open))).performClick()
        waitForText(text(R.string.search_idle_title))
    }

    @Test
    fun theComposeButtonOpensTheComposer() {
        compose.onNodeWithContentDescription(text(R.string.compose_fab)).performClick()
        waitForText(text(R.string.compose_title_new))
    }

    @Test
    fun theFilterMenuFiltersTheRowsAndSaysSo() {
        pickFilter(R.string.inbox_filter_unread)
        assertOnlyShowing(unreadSubject)
        waitForText(text(R.string.inbox_filtered_by, text(R.string.inbox_filter_unread)))

        pickFilter(R.string.inbox_filter_starred)
        assertOnlyShowing(starredSubject)
        waitForText(text(R.string.inbox_filtered_by, text(R.string.inbox_filter_starred)))

        pickFilter(R.string.inbox_filter_attachments)
        assertOnlyShowing(attachmentSubject)
        waitForText(text(R.string.inbox_filtered_by, text(R.string.inbox_filter_attachments)))

        pickFilter(R.string.inbox_filter_all)
        assertOnlyShowing(*allRows.toTypedArray())
        waitUntilGone(hasText(text(R.string.inbox_filtered_by, ""), substring = true))
    }

    @Test
    fun editShowsEmptyCirclesAndTheActionBarAndDoneLeaves() {
        // Outside Edit the rows are not something to pick.
        assertEquals(0, rowsThat(isSelectable()))

        compose.onNode(edit).performClick()

        waitFor(done)
        waitForText(plural(R.plurals.inbox_selection_count, 0, 0))
        // One empty circle per row: each row is now selectable and none is selected.
        assertEquals(allRows.size, rowsThat(isSelectable() and isNotSelected()))
        // With nothing picked the action bar is there but cannot act.
        listOf(
            R.string.inbox_bar_mark,
            R.string.inbox_bar_move,
            R.string.inbox_bar_archive,
            R.string.inbox_bar_trash
        ).forEach {
            compose.onNode(hasContentDescription(text(it))).assertIsDisplayed().assertIsNotEnabled()
        }
        waitUntilGone(filterButton)

        compose.onNode(done).performClick()

        waitFor(edit)
        waitUntilGone(done)
        assertEquals(0, rowsThat(isSelectable()))
        waitFor(filterButton)
    }

    @Test
    fun selectAllPicksEveryRow() {
        compose.onNode(edit).performClick()
        waitFor(done)

        compose.onNode(tappable(text(R.string.inbox_select_all))).performClick()

        waitForText(plural(R.plurals.inbox_selection_count, allRows.size, allRows.size))
        assertEquals(allRows.size, rowsThat(isSelected()))
        compose.onNode(hasContentDescription(text(R.string.inbox_bar_move))).assertIsEnabled()
        compose.onNode(hasContentDescription(text(R.string.inbox_bar_trash))).assertIsEnabled()
    }

    @Test
    fun aLongPressSelectsOneRow() {
        compose.onNode(conversationRow(starredSubject)).performTouchInput { longClick() }

        waitForText(plural(R.plurals.inbox_selection_count, 1, 1))
        assertEquals(1, rowsThat(isSelected()))
        compose.onNode(conversationRow(starredSubject) and isSelected()).assertIsDisplayed()
        compose.onNode(conversationRow(unreadSubject) and isNotSelected()).assertIsDisplayed()
    }
}
