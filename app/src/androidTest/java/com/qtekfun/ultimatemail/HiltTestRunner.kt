// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import dagger.hilt.android.testing.CustomTestApplication

/**
 * Base of the application the UI tests run on. It is not [UltimateMailApp]: that one starts the
 * periodic sync and asks for a sync on every start, which must never happen against a test run.
 * WorkManager has no automatic initializer in this app, so it gets a plain configuration here.
 */
open class TestAppBase :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
}

/** The Hilt application generated from [TestAppBase]; test modules replace the production ones. */
@CustomTestApplication(TestAppBase::class)
interface UiTestApplication

/** Runs the instrumented tests on the Hilt test application. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, UiTestApplication_Application::class.java.name, context)
}
