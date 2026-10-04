// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The top bar of the reader (iOS style): back with the name of the mailbox, the arrows to the
 * previous (newer) and next (older) conversation, disabled at the ends of the list, and the
 * overflow menu.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReaderTopBarUiTest : UiTestBase() {
    // Newest first, as the list shows them.
    private val newest = "Lunch plans"
    private val middle = "Quarterly report"
    private val oldest = "Holiday photos"

    private val previous get() = hasContentDescription(text(R.string.conversation_previous))
    private val next get() = hasContentDescription(text(R.string.conversation_next))

    @Before
    fun seed() {
        seedAccount("ana@example.com", "Ana", listOf(newest, middle, oldest))
        launchApp()
        waitFor(conversationRow(newest))
    }

    private fun open(subject: String) {
        compose.onNode(conversationRow(subject)).performClick()
        waitFor(hasText(subject))
        waitFor(previous)
    }

    @Test
    fun theTopBarHasBackWithTheMailboxNameAndTheArrows() {
        open(middle)

        compose.onNode(
            hasContentDescription(text(R.string.conversation_back_to, text(R.string.folder_inbox)))
        )
            .assertIsDisplayed()
        compose.onNode(previous).assertIsDisplayed().assertIsEnabled()
        compose.onNode(next).assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasContentDescription(text(R.string.conversation_more))).assertIsDisplayed()
    }

    @Test
    fun theArrowsAreDisabledAtTheEndsOfTheList() {
        open(newest)
        compose.onNode(previous).assertIsNotEnabled()
        compose.onNode(next).assertIsEnabled()
        pressBack()
        waitFor(conversationRow(oldest))

        open(oldest)
        compose.onNode(next).assertIsNotEnabled()
        compose.onNode(previous).assertIsEnabled()
    }

    @Test
    fun theArrowsOpenTheNeighbours() {
        open(middle)

        compose.onNode(previous).performClick()
        waitFor(hasText(newest))
        waitUntilGone(hasText(middle))
        compose.onNode(previous).assertIsNotEnabled()

        compose.onNode(next).performClick()
        waitFor(hasText(middle))
        compose.onNode(next).performClick()
        waitFor(hasText(oldest))
        compose.onNode(next).assertIsNotEnabled()

        // Back still goes to the list, not through the conversations visited.
        pressBack()
        waitFor(conversationRow(newest))
    }

    @Test
    fun backReturnsToTheList() {
        open(middle)

        compose.onNode(
            hasContentDescription(text(R.string.conversation_back_to, text(R.string.folder_inbox)))
        )
            .performClick()

        waitFor(conversationRow(middle))
        waitUntilGone(previous)
    }
}
