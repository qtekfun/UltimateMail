// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The things the user must be told about after a conflict was settled (a message an operation
 * wanted vanished, a folder was reset). Notices wait here until the UI collects [notices], and
 * each is delivered once. They carry ids only, never text or mail content.
 */
@Singleton
class SyncNotices @Inject constructor() {
    private val queue = Channel<SyncNotice>(MAX_WAITING, BufferOverflow.DROP_OLDEST)

    val notices: Flow<SyncNotice> = queue.receiveAsFlow()

    internal fun publish(notice: SyncNotice) {
        queue.trySend(notice)
    }

    private companion object {
        const val MAX_WAITING = 100
    }
}
