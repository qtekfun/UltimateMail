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
import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Re-authentication (T27): an account that waits for the user to sign in again says so in the
 * menu; tapping that line opens the sign-in screen, a wrong password is refused, and the right
 * one stores the new credentials and clears the state. Nothing local is lost on the way.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReauthenticationUiTest : UiTestBase() {
    private val address = "ana@example.com"

    private val signInAgainLine get() =
        hasText(text(R.string.sync_status_reauth)) and hasClickAction()

    private val password get() = hasSetTextAction() and hasText(text(R.string.field_password))

    @Test
    fun theSignInAgainLineLeadsToASignInThatClearsTheState() {
        // No password stored: the sync of the seed finds that out, as it would on a real device.
        val account = seedAccount(address, "Ana", listOf("Kept message"), credentials = false)
        assertEquals(AccountSyncState.ReauthenticationNeeded, syncStatus.get(account.id))
        launchApp()
        openDrawer()
        waitFor(signInAgainLine)

        compose.onNode(signInAgainLine).performClick()

        waitForText(text(R.string.reauth_account_line, address, "imap.example.com"))
        compose.onNode(password).performTextInput("not the password")
        compose.onNode(hasText(text(R.string.reauth_submit)) and hasClickAction()).performClick()
        // A wrong password is refused and nothing is stored.
        waitForText(text(R.string.error_connection_auth))
        assertNull(runBlocking { vault.load(account.id) })
        assertEquals(AccountSyncState.ReauthenticationNeeded, syncStatus.get(account.id))

        compose.onNode(password).performTextReplacement(SEED_PASSWORD)
        compose.onNode(hasText(text(R.string.reauth_submit)) and hasClickAction()).performClick()

        // Back on the list, with the line gone and the stored password replaced.
        waitUntilGone(hasText(text(R.string.reauth_submit)))
        openDrawer()
        waitUntilGone(signInAgainLine)
        assertEquals(SEED_PASSWORD, runBlocking { vault.load(account.id) }?.password)
        assertTrue(syncStatus.get(account.id) != AccountSyncState.ReauthenticationNeeded)
        assertTrue(
            "Signing in again should have asked for a sync of the account",
            scheduler.requests.any { it.accountId == account.id && it.userInitiated }
        )
        // What was on the device is still there.
        compose.onNode(hasContentDescription("Kept message", substring = true)).assertIsDisplayed()
    }
}
