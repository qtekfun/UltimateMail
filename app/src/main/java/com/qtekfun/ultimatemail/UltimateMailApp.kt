// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail

import android.app.Application
import androidx.work.Configuration
import com.qtekfun.ultimatemail.di.ApplicationScope
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.worker.SyncWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class UltimateMailApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: SyncWorkerFactory

    @Inject
    lateinit var scheduler: SyncScheduler

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    override fun onCreate() {
        super.onCreate()
        // Sync on app open, and keep the periodic sync (~15 minutes) scheduled (RF-10). The first
        // use of WorkManager builds its own database and schedulers: off the main thread, so
        // that it does not delay the first frame (SPEC section 6, cold start under 1.5 s).
        appScope.launch(io) {
            scheduler.startPeriodic()
            scheduler.requestSync()
        }
    }

    /** WorkManager starts on demand with this factory; its default initializer is off. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
