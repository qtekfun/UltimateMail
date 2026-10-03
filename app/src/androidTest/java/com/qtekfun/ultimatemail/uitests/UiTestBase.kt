// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import com.qtekfun.ultimatemail.testing.FakeMailWorld
import com.qtekfun.ultimatemail.testing.InMemoryPreferenceStore
import com.qtekfun.ultimatemail.testing.InMemoryVault
import com.qtekfun.ultimatemail.testing.TestSyncScheduler
import com.qtekfun.ultimatemail.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import java.time.Clock
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * What every UI test of the app shares: the Hilt rule, the Compose rule, the fakes of the test
 * modules (see `testing/`), and the small helpers to wait for and find things on screen.
 *
 * Texts are always looked up in the string resources, never typed in, so the tests pass on a
 * device in any language the app is translated to.
 */
@OptIn(ExperimentalTestApi::class)
abstract class UiTestBase {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var database: UltimateMailDatabase

    @Inject
    lateinit var world: FakeMailWorld

    @Inject
    lateinit var vault: InMemoryVault

    @Inject
    lateinit var scheduler: TestSyncScheduler

    @Inject
    lateinit var engine: SyncEngine

    @Inject
    lateinit var syncStatus: SyncStatusStore

    @Inject
    lateinit var settings: InMemoryPreferenceStore

    @Inject
    lateinit var clock: Clock

    protected val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun injectDependencies() {
        hilt.inject()
    }

    @After
    fun closeEverything() {
        scenario?.close()
        scheduler.shutDown()
        database.close()
    }

    /** Starts the app on its main screen. */
    protected fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** The system Back button. */
    protected fun pressBack() {
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    protected fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    protected fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    /** Waits until at least one node matches. */
    protected fun waitFor(matcher: SemanticsMatcher, timeoutMillis: Long = TIMEOUT) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits until no node matches any more. */
    protected fun waitUntilGone(matcher: SemanticsMatcher, timeoutMillis: Long = TIMEOUT) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    protected fun waitForText(value: String, substring: Boolean = false) =
        waitFor(hasText(value, substring))

    /** Opens the side menu with its button and waits for it to be there. */
    protected fun openDrawer() {
        waitFor(hasContentDescription(text(R.string.drawer_open)))
        compose.onNodeWithContentDescription(text(R.string.drawer_open)).performClick()
        waitFor(hasContentDescription(text(R.string.drawer_sync_now)))
    }

    protected companion object {
        const val TIMEOUT = 10_000L
    }
}
