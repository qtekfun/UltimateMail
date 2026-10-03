// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Carries out what the picker decided: queues the operations, shows the change locally at once
 * ([LocalMoveApplier]), asks for a sync and remembers the destination. Also usable without the
 * picker: [archive] is the quick action (swipe, toolbar button) and [undo] reverts a result.
 */
class MoveLabelActions @Inject constructor(
    private val queue: OperationQueue,
    private val applier: LocalMoveApplier,
    private val recents: RecentDestinations,
    private val scheduler: SyncScheduler,
    private val source: PickerSource,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /**
     * Queues [result]'s operations and updates the local state. [destinations] are the folder
     * paths to remember as recent (empty for an archive).
     */
    suspend fun commit(result: PickerResult, destinations: List<String> = emptyList()) {
        enqueueAll(result.operations)
        applier.apply(result.operations)
        withContext(io) { recents.record(result.accountId, destinations) }
        scheduler.requestSync(result.accountId, userInitiated = true)
    }

    /**
     * Reverts [result]: queues its inverse operations. See [PickerResult] for what undo can do
     * once the server has the original change.
     */
    suspend fun undo(result: PickerResult) {
        enqueueAll(result.inverse)
        applier.apply(result.inverse)
        scheduler.requestSync(result.accountId, userInitiated = true)
    }

    /**
     * Archives the messages of [request] without showing the picker: removes the Inbox label on
     * Gmail, moves to the Archive folder elsewhere. Returns what to offer an undo for, or null
     * when nothing was done (already archived, or the account has no Archive folder).
     */
    suspend fun archive(request: PickerRequest): PickerResult? {
        val account = source.account(request.accountId) ?: return null
        val tree = source.observeTree(request.accountId).first()
        val result = PickerOperations.archive(
            request,
            source.modeOf(account, tree),
            tree,
            source.labelsOf(request, tree)
        )
        result?.let { commit(it) }
        return result
    }

    private suspend fun enqueueAll(operations: List<NewOperation>) {
        operations.forEach { queue.enqueue(it) }
    }
}
