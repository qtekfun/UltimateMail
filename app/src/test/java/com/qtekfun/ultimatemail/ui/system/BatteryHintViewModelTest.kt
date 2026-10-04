// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.system

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.domain.account.AccountListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryHintViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val preferences = FakePreferenceStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
    }

    @AfterEach
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** The first emission may be the initial false or already the answer from Room: skip to it. */
    private suspend fun ReceiveTurbine<Boolean>.awaitValue(expected: Boolean) {
        while (awaitItem() != expected) {
            // Keep reading until the state reaches the value.
        }
    }

    private fun viewModel() = BatteryHintViewModel(AccountListing(db), preferences)

    @Test
    fun `nothing is asked before there is an account`() = runTest {
        viewModel().pending.test {
            assertFalse(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `it is asked once an account exists`() = runTest {
        viewModel().pending.test {
            awaitValue(false)

            db.accountDao().insert(account())

            awaitValue(true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `answering or dismissing it is final and remembered`() = runTest {
        db.accountDao().insert(account())
        val vm = viewModel()
        vm.pending.test {
            awaitValue(true)

            vm.markDone()

            awaitValue(false)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(preferences.getBoolean(BatteryHintViewModel.KEY_DONE, false))
        viewModel().pending.test {
            assertFalse(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `phone makers known for closing background apps get the extra steps`() {
        assertEquals("OPPO", aggressiveBatteryVendor("OPPO"))
        assertEquals("Xiaomi", aggressiveBatteryVendor("xiaomi"))
        assertEquals("Samsung", aggressiveBatteryVendor("samsung"))
        assertNull(aggressiveBatteryVendor("Google"))
        assertNull(aggressiveBatteryVendor("Fairphone"))
    }
}
