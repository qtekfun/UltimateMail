// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {
    private val store = FakePreferenceStore()
    private val repository = SettingsRepository(store)

    @Test
    fun `nothing stored reads as the defaults`() {
        val settings = repository.current()

        assertEquals(AppSettings(), settings)
        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertEquals(true, settings.dynamicColor)
        assertEquals(false, settings.amoled)
        assertEquals(SwipeAction.ARCHIVE, settings.swipe.right)
        assertEquals(SwipeAction.DELETE, settings.swipe.left)
        assertEquals(RemoteContentPolicy.NEVER, settings.remoteContent)
    }

    @Test
    fun `every setting is stored and read back`() {
        repository.setTheme(ThemeMode.DARK)
        repository.setDynamicColor(false)
        repository.setAmoled(true)
        repository.setSwipeRight(SwipeAction.TOGGLE_STAR)
        repository.setSwipeLeft(SwipeAction.NONE)
        repository.setRemoteContent(RemoteContentPolicy.ASK)

        assertEquals(
            AppSettings(
                theme = ThemeMode.DARK,
                dynamicColor = false,
                amoled = true,
                swipe = SwipeActions(right = SwipeAction.TOGGLE_STAR, left = SwipeAction.NONE),
                remoteContent = RemoteContentPolicy.ASK
            ),
            repository.current()
        )
    }

    @Test
    fun `settings survive a new repository on the same store`() {
        repository.setSwipeLeft(SwipeAction.MOVE)

        assertEquals(SwipeAction.MOVE, SettingsRepository(store).current().swipe.left)
    }

    @Test
    fun `unknown stored values read as the defaults`() {
        store.putString("theme", "SEPIA")
        store.putString("swipe_right", "EXPLODE")
        store.putString("remote_content", "")

        assertEquals(AppSettings(), repository.current())
    }

    @Test
    fun `the flow starts with the current settings and follows every change`() = runTest {
        repository.settings.test {
            assertEquals(AppSettings(), awaitItem())

            repository.setTheme(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem().theme)

            repository.setAmoled(true)
            assertEquals(true, awaitItem().amoled)
        }
    }

    @Test
    fun `writing the same value again does not emit`() = runTest {
        repository.settings.test {
            awaitItem()

            repository.setTheme(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem().theme)
            repository.setTheme(ThemeMode.DARK)

            expectNoEvents()
        }
    }

    @Test
    fun `the swipe actions flow only emits when a swipe action changes`() = runTest {
        repository.swipeActions.test {
            assertEquals(SwipeActions(), awaitItem())

            repository.setTheme(ThemeMode.DARK)
            repository.setSwipeRight(SwipeAction.TOGGLE_READ)

            assertEquals(SwipeActions(right = SwipeAction.TOGGLE_READ), awaitItem())
            expectNoEvents()
        }
    }
}
