// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.HeldOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** A [NoticeCenter] whose end of an undo window runs on the spot, holding nothing back. */
fun noticeCenter(scheduler: SyncScheduler, held: HeldOperations = HeldOperations {}) =
    NoticeCenter(scheduler, held, CoroutineScope(Dispatchers.Unconfined))
