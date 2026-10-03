// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One call of [SyncScheduler.requestSync]. */
data class SyncRequest(val accountId: Long?, val userInitiated: Boolean)

/**
 * The scheduler of the tests. It never touches WorkManager. It records every request, so a test
 * can say "this action asked for a sync", and it only runs the real [SyncEngine] when [runSyncs]
 * is on: a test that wants to see mail arrive turns it on, one that wants to see what stays in
 * the queue leaves it off.
 */
@Singleton
class TestSyncScheduler @Inject constructor(private val engine: Provider<SyncEngine>) :
    SyncScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every request, in order. */
    val requests = CopyOnWriteArrayList<SyncRequest>()

    @Volatile
    var periodicStarted = false
        private set

    /** Whether a request also runs a sync of the real engine, in the background. */
    @Volatile
    var runSyncs = false

    override fun startPeriodic() {
        periodicStarted = true
    }

    override fun requestSync(accountId: Long?, userInitiated: Boolean) {
        requests += SyncRequest(accountId, userInitiated)
        if (!runSyncs) return
        scope.launch {
            if (accountId == null) {
                engine.get().syncAll(userInitiated)
            } else {
                engine.get().sync(accountId, userInitiated)
            }
        }
    }

    override fun stop() = Unit

    /** Ends the syncs still running; the tests call it when they finish. */
    fun shutDown() = scope.cancel()
}
