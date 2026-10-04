// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.testing.SyncRequest
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 7, "compose and send offline": with no network, a message is written and sent;
 * "Sending..." with Undo shows for the length of the undo window, and then the message sits in
 * the Outbox, queued, until the server can be reached.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ComposeOfflineUiTest : UiTestBase() {
    private val subject = "Offline hello"
    private val recipient = "bob@example.com"

    @Test
    fun aMessageSentOfflineWaitsInTheOutboxAfterTheUndoWindow() {
        val account = seedAccount("ana@example.com", "Ana", listOf("Something to read"))
        // The mail arrived while online; from here on the phone has no network at all.
        world.online = false
        scheduler.runSyncs = true
        launchApp()
        waitFor(hasContentDescription("Something to read", substring = true))

        compose.onNodeWithContentDescription(text(R.string.compose_fab)).performClick()
        waitForText(text(R.string.compose_title_new))
        compose.onNode(hasSetTextAction() and hasContentDescription(text(R.string.composer_to)))
            .performTextInput(recipient)
        compose.onNode(hasSetTextAction() and hasText(text(R.string.composer_subject)))
            .performTextInput(subject)
        compose.onNode(hasSetTextAction() and hasText(text(R.string.composer_body)))
            .performTextInput("Written on a train without signal.")
        compose.onNodeWithContentDescription(text(R.string.composer_send)).performClick()

        // Back on the list: "Sending..." with Undo, for the length of the undo window.
        waitForText(text(R.string.notice_sending))
        compose.onNode(hasText(text(R.string.notice_undo)) and hasClickAction()).assertIsDisplayed()
        // The window ends on its own; then the message is handed to the queue.
        waitUntilGone(hasText(text(R.string.notice_sending)), SEND_WINDOW_TIMEOUT)
        openDrawer()
        waitFor(hasText(text(R.string.drawer_outbox)) and hasClickAction(), SEND_WINDOW_TIMEOUT)
        compose.onNode(hasText(text(R.string.drawer_outbox)) and hasClickAction()).performClick()

        waitForText(subject)
        compose.onNode(hasText(text(R.string.outbox_status_waiting))).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.outbox_to, recipient))).assertIsDisplayed()
        val queued = runBlocking { database.pendingOperationDao().all(account.id) }
        assertEquals(1, queued.count { it.type == OperationType.SEND })
        assertTrue("Nothing may have reached SMTP offline", world.sent.isEmpty())
        assertTrue(
            "Queuing the message should have asked for a sync",
            scheduler.requests.contains(SyncRequest(account.id, userInitiated = false))
        )
    }

    private companion object {
        /** The undo window is 5 seconds of real time; this leaves room for a slow device. */
        const val SEND_WINDOW_TIMEOUT = 30_000L
    }
}
