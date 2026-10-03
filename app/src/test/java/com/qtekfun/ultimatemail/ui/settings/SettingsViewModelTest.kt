// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val store = FakePreferenceStore()
    private val repository = SettingsRepository(store)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    private val created = mutableListOf<SettingsViewModel>()

    private fun viewModel() = SettingsViewModel(repository, AccountListing(db)).also {
        created += it
    }

    @Test
    fun `the first state already holds the stored settings`() {
        repository.setTheme(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, viewModel().state.value.settings.theme)
    }

    @Test
    fun `with nothing stored the defaults show and there are no accounts`() = runTest {
        viewModel().state.test {
            val state = awaitItem()

            assertEquals(AppSettings(), state.settings)
            assertEquals(emptyList<Any>(), state.accounts)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `each setter reaches the stored settings and the state`() = runTest {
        val model = viewModel()

        model.state.test {
            awaitItem()
            model.setTheme(ThemeMode.LIGHT)
            model.setDynamicColor(false)
            model.setAmoled(true)
            model.setSwipeRight(SwipeAction.MOVE)
            model.setSwipeLeft(SwipeAction.TOGGLE_READ)
            model.setRemoteContent(RemoteContentPolicy.ASK)

            var settings = awaitItem().settings
            while (settings.remoteContent !=
                RemoteContentPolicy.ASK
            ) {
                settings = awaitItem().settings
            }

            assertEquals(ThemeMode.LIGHT, settings.theme)
            assertEquals(false, settings.dynamicColor)
            assertEquals(true, settings.amoled)
            assertEquals(SwipeAction.MOVE, settings.swipe.right)
            assertEquals(SwipeAction.TOGGLE_READ, settings.swipe.left)
            cancelAndIgnoreRemainingEvents()
        }
        // What the screen shows is what the composer and the list will read.
        assertEquals(SwipeAction.MOVE, repository.current().swipe.right)
        assertEquals(RemoteContentPolicy.ASK, repository.current().remoteContent)
    }

    @Test
    fun `the accounts are listed in the order they were added`() = runTest {
        db.accountDao().insert(account("a@example.test"))
        db.accountDao().insert(account("b@example.test"))

        viewModel().state.test {
            var state = awaitItem()
            while (state.accounts.size < 2) state = awaitItem()

            assertEquals(
                listOf("a@example.test", "b@example.test"),
                state.accounts.map {
                    it.email
                }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
