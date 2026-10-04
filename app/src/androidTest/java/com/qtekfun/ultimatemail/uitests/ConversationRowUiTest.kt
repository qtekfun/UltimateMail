// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.settings.PreviewLines
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row of the list in the iOS Mail style: the unread slot at its start and the preview of the
 * text, whose lines are a setting. The row speaks as one sentence, so what it shows is read from
 * that: "Unread" opens the sentence of an unread conversation, and the preview is in it only
 * while the preview is on screen.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConversationRowUiTest : UiTestBase() {
    private val unreadSubject = "Lunch plans"
    private val readSubject = "Quarterly report"
    private val preview = "The venue is booked for Friday and everybody has confirmed"

    @Before
    fun seed() {
        seedAccount("ana@example.com", "Ana") {
            val now = clock.instant()
            deliver(
                INBOX,
                unreadSubject,
                now.minus(Duration.ofHours(1)),
                body = "$preview. $LONG_TEXT"
            )
            deliver(
                INBOX,
                readSubject,
                now.minus(Duration.ofHours(2)),
                body = "Numbers are attached. $LONG_TEXT",
                flags = MessageFlags(seen = true)
            )
        }
    }

    private fun speaks(subject: String, part: String) =
        conversationRow(subject) and hasContentDescription(part, substring = true)

    @Test
    fun onlyTheRowOfAnUnreadConversationHasTheUnreadSlotFilled() {
        launchApp()
        waitFor(conversationRow(unreadSubject))
        waitFor(conversationRow(readSubject))

        val unread = text(R.string.conversation_desc_unread)
        compose.onNode(speaks(unreadSubject, unread)).assertExists()
        // The sentence of the read conversation does not start with it.
        compose.onNode(speaks(readSubject, unread)).assertDoesNotExist()
        // The unread one says it before anything else, as the dot comes before everything else.
        compose.onNode(
            speaks(unreadSubject, "$unread, ${text(R.string.conversation_desc_from, "Bob")}")
        )
            .assertExists()
    }

    @Test
    fun thePreviewSettingDecidesWhetherTheTextOfTheMessageShows() {
        launchApp()
        waitFor(speaks(unreadSubject, preview))
        val withPreview = compose.onNode(conversationRow(unreadSubject)).getBoundsInRoot().height

        SettingsRepository(settings).setPreviewLines(PreviewLines.NONE)

        waitUntilGone(speaks(unreadSubject, preview))
        compose.waitForIdle()
        val without = compose.onNode(conversationRow(unreadSubject)).getBoundsInRoot().height
        assertTrue(
            "A row without the preview ($without) should be shorter than one with two lines ($withPreview)",
            without < withPreview
        )

        SettingsRepository(settings).setPreviewLines(PreviewLines.TWO)

        waitFor(speaks(unreadSubject, preview))
    }

    private companion object {
        const val LONG_TEXT =
            "We should also decide who brings what, and whether the others want to join " +
                "us afterwards for a walk around the lake if the weather holds up as expected."
    }
}
