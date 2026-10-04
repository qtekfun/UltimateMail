// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Mailboxes menu (the side menu in the iOS style): "All inboxes" and the Inbox of every
 * account with their counters, the card of special mailboxes, the accounts section closed until
 * opened, and Settings at the bottom.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MailboxesMenuUiTest : UiTestBase() {
    private val ana = "ana@example.com"
    private val bea = "bea@example.com"

    @Before
    fun seed() {
        seedAccount(ana, "Ana", listOf("Lunch plans", "Quarterly report"))
        seedAccount(bea, "Bea", listOf("Holiday photos"))
        launchApp()
        waitFor(conversationRow("Lunch plans"))
        openDrawer()
    }

    private fun item(label: String): SemanticsMatcher = isSelectable() and hasText(label)

    private fun unreadDescription(count: Int) = plural(R.plurals.drawer_unread_count, count, count)

    /** Where the first match starts; the account's address is also on its row in the section below. */
    private fun top(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher)[0].getBoundsInRoot().top

    private fun inboxOf(email: String, unread: Int) =
        item(email) and hasContentDescription(unreadDescription(unread))

    /** Scrolls the side menu up by dragging on its left part, where the sheet is. */
    private fun scrollMenuDown() {
        compose.onRoot().performTouchInput {
            val x = width * 0.15f
            swipe(Offset(x, height * 0.8f), Offset(x, height * 0.25f))
        }
        compose.waitForIdle()
    }

    @Test
    fun allInboxesAndEachInboxShowTheirUnreadCounters() {
        val everything = item(text(R.string.drawer_all_inboxes))
        compose.onNode(
            everything and hasContentDescription(unreadDescription(3))
        ).assertIsDisplayed()
        compose.onNode(inboxOf(ana, 2)).assertIsDisplayed()
        compose.onNode(inboxOf(bea, 1)).assertIsDisplayed()
        // All inboxes first, then the Inbox of each account.
        assertTrue(top(everything) < top(inboxOf(ana, 2)))
        assertTrue(top(inboxOf(ana, 2)) < top(inboxOf(bea, 1)))
    }

    @Test
    fun theSpecialMailboxesAreInOneCardInTheirOrder() {
        val order = listOf(
            R.string.folder_drafts,
            R.string.folder_sent,
            R.string.folder_archive,
            R.string.folder_trash
        ).map { item(text(it)) }
        order.forEach { waitFor(it) }
        val tops = order.map { top(it) }
        assertTrue("Drafts, Sent, Archive, Trash in this order, not $tops", tops == tops.sorted())
        // Below the inboxes.
        assertTrue(top(inboxOf(bea, 1)) < tops.first())
    }

    @Test
    fun theAccountsSectionIsClosedAndOpensToShowItsFolders() {
        val custom = item(ACCENTED_FOLDER)
        compose.onNode(hasText(text(R.string.drawer_section_accounts), ignoreCase = true))
            .assertExists()
        // Closed: the folder of the account that is not a special mailbox is not listed.
        compose.onNode(custom).assertDoesNotExist()
        val toggle = hasContentDescription(text(R.string.drawer_expand, ana))
        repeat(SCROLLS) {
            if (compose.onAllNodes(toggle).fetchSemanticsNodes().isEmpty()) scrollMenuDown()
        }

        compose.onNode(toggle).performClick()

        waitFor(hasContentDescription(text(R.string.drawer_collapse, ana)))
        repeat(SCROLLS) {
            if (compose.onAllNodes(custom).fetchSemanticsNodes().isEmpty()) scrollMenuDown()
        }
        compose.onNode(custom).assertExists()
    }

    @Test
    fun settingsIsTheLastRowAndOpensSettings() {
        val settingsRow = item(text(R.string.drawer_settings))
        compose.onNode(settingsRow).assertIsDisplayed()
        // At the bottom: under the special mailboxes and next to the end of the sheet.
        assertTrue(top(settingsRow) > top(item(text(R.string.folder_trash))))
        compose.onNodeWithContentDescription(text(R.string.drawer_sync_now)).assertIsDisplayed()

        compose.onNode(settingsRow).performClick()

        waitForText(text(R.string.settings_section_appearance))
    }

    private companion object {
        const val SCROLLS = 5
    }
}
