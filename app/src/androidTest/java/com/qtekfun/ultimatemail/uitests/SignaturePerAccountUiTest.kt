// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 7, "signature per account": two accounts with different signatures. A message
 * composed from each starts with the signature of that account, and switching the sender swaps
 * only the signature block, leaving what the user wrote alone.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SignaturePerAccountUiTest : UiTestBase() {
    private val ana = "ana@example.com"
    private val bea = "bea@example.com"
    private val anaSignature = "Ana Signature, Sales"
    private val beaSignature = "Bea Signature, Support"

    private fun seedBothAccounts() {
        seedAccount(ana, "Ana", listOf("Hello Ana"), signature = anaSignature)
        seedAccount(bea, "Bea", listOf("Hello Bea"), signature = beaSignature)
    }

    private fun bodyWith(fragment: String) =
        hasSetTextAction() and hasText(fragment, substring = true)

    private fun inPopup(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(isPopup())

    private fun startNewMessage() {
        compose.onNodeWithContentDescription(text(R.string.compose_fab)).performClick()
        waitForText(text(R.string.compose_title_new))
    }

    private fun changeSenderTo(address: String) {
        compose.onNode(hasText(text(R.string.composer_from)) and hasClickAction()).performClick()
        waitFor(inPopup(hasText(address)))
        compose.onNode(inPopup(hasText(address))).performClick()
    }

    @Test
    fun switchingTheSenderSwapsOnlyTheSignatureBlock() {
        seedBothAccounts()
        launchApp()
        // Two accounts: the unified inbox is the start, and the first account writes.
        waitForText(text(R.string.inbox_unified))
        startNewMessage()
        waitFor(bodyWith(anaSignature))
        compose.onNode(bodyWith(anaSignature)).assertTextContains("-- ", substring = true)
        compose.onNode(bodyWith(anaSignature)).performTextInput("See you at ten. ")

        changeSenderTo(bea)

        waitFor(bodyWith(beaSignature))
        waitUntilGone(bodyWith(anaSignature))
        compose.onNode(
            bodyWith(beaSignature)
        ).assertTextContains("See you at ten.", substring = true)
        compose.onNode(bodyWith(beaSignature)).assertTextContains("-- ", substring = true)

        // And back: the first signature returns, the second one leaves, the text is still there.
        changeSenderTo(ana)

        waitFor(bodyWith(anaSignature))
        waitUntilGone(bodyWith(beaSignature))
        compose.onNode(
            bodyWith(anaSignature)
        ).assertTextContains("See you at ten.", substring = true)
    }

    @Test
    fun aMessageStartedFromEachAccountGetsItsOwnSignature() {
        seedBothAccounts()
        launchApp()
        waitForText(text(R.string.inbox_unified))

        // The menu shows the first account: its message gets its signature.
        startNewMessage()
        waitFor(bodyWith(anaSignature))
        compose.onNode(hasText(text(R.string.composer_from)) and hasClickAction())
            .assertTextContains(ana, substring = true)
        pressBack()
        waitForText(text(R.string.inbox_unified))

        // The second account's Inbox is opened from the menu: now its signature is the one inserted.
        openDrawer()
        compose.onNode(hasText(bea) and hasClickAction()).performClick()
        startNewMessage()
        waitFor(bodyWith(beaSignature))
        compose.onNode(hasText(text(R.string.composer_from)) and hasClickAction())
            .assertTextContains(bea, substring = true)
    }
}
