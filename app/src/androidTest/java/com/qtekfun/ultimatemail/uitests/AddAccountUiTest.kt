// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 7, "add account": a password account is added end to end. The connection test is
 * the fake one (it accepts the one password of the fake mailbox), and the first sync is the real
 * engine against the fake server, so the folders on screen came through the real sync code.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AddAccountUiTest : UiTestBase() {
    private val address = "ana@example.com"

    private fun field(label: Int) = hasSetTextAction() and hasText(text(label))

    private val submit get() = hasText(text(R.string.add_account_submit)) and hasClickAction()

    private fun openTheForm() {
        launchApp()
        waitForText(text(R.string.home_empty))
        compose.onNode(hasText(text(R.string.account_add)) and hasClickAction()).performClick()
        waitFor(field(R.string.field_email))
    }

    private fun fill(email: String, password: String) {
        compose.onNode(field(R.string.field_email)).performTextInput(email)
        compose.onNode(field(R.string.field_password)).performTextInput(password)
    }

    @Test
    fun anEmptyFormShowsWhatIsMissingAndAddsNothing() {
        openTheForm()

        compose.onNode(submit).performClick()

        waitForText(text(R.string.error_email_invalid))
        compose.onNode(hasText(text(R.string.error_password_missing))).assertIsDisplayed()
        assertTrue(runBlocking { database.accountDao().observeAll().first() }.isEmpty())
    }

    @Test
    fun aWrongPasswordIsExplainedAndNothingIsSaved() {
        world.mailbox(address, SEED_PASSWORD).withStandardFolders()
        openTheForm()
        fill(address, "not the password")

        compose.onNode(submit).performClick()

        waitForText(text(R.string.error_connection_auth))
        assertTrue(runBlocking { database.accountDao().observeAll().first() }.isEmpty())
        assertNull(runBlocking { vault.load(1) })
    }

    @Test
    fun aValidAccountIsCreatedAndItsFoldersAppear() {
        scheduler.runSyncs = true
        world.mailbox(address, SEED_PASSWORD).withStandardFolders()
            .deliver(INBOX, "Welcome aboard", clock.instant())
        openTheForm()
        // First the wrong password, then the right one, in the same form.
        fill(address, "not the password")
        compose.onNode(submit).performClick()
        waitForText(text(R.string.error_connection_auth))
        compose.onNode(field(R.string.field_password)).performTextReplacement(SEED_PASSWORD)

        compose.onNode(submit).performClick()

        // The account opens on its Inbox, which the first sync filled with the welcome message.
        waitFor(hasText(address))
        waitFor(hasContentDescription("Welcome aboard", substring = true))
        openDrawer()
        listOf(
            R.string.folder_inbox,
            R.string.folder_sent,
            R.string.folder_drafts,
            R.string.folder_archive,
            R.string.folder_trash
        ).forEach { folder ->
            waitFor(hasText(text(folder)) and hasClickAction())
        }
        // The account's own folders are in its section, closed until it is opened.
        compose.onNode(hasText(address) and hasClickAction()).performClick()
        waitFor(hasText(ACCENTED_FOLDER) and hasClickAction())

        val accounts = runBlocking { database.accountDao().observeAll().first() }
        assertEquals(listOf(address), accounts.map { it.email })
        assertEquals(SEED_PASSWORD, runBlocking { vault.load(accounts.single().id) }?.password)
        assertTrue(
            "The new account should have asked for its first sync",
            scheduler.requests.any { it.accountId == accounts.single().id && it.userInitiated }
        )
    }
}
