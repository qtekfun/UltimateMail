// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import com.qtekfun.ultimatemail.di.ApplicationScope
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import com.qtekfun.ultimatemail.ui.conversation.PendingUndo
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The swipe-away of one list (Drafts, Outbox) with Undo. A swiped row is only hidden ([ids]);
 * nothing is deleted until the undo window of the notice ends, so Undo just shows it again and a
 * process that dies in the window loses nothing. The deletion runs in the application scope, so
 * it still happens if the list is left before the window ends.
 */
class SwipeDiscards @Inject constructor(
    private val notices: NoticeCenter,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val hidden = MutableStateFlow<Set<Long>>(emptySet())

    /** The ids to leave out of the list now. */
    val ids: StateFlow<Set<Long>> = hidden

    fun hide(id: Long) = hidden.update { it + id }

    fun show(id: Long) = hidden.update { it - id }

    /**
     * Shows [kind] with Undo for the hidden row [id]: Undo brings the row back, and when the
     * window ends [discard] runs and the row stops being tracked (it is gone, or came back).
     */
    fun offerUndo(id: Long, kind: NoticeKind, discard: suspend () -> Unit) {
        val undo = PendingUndo(
            accountIds = emptySet(),
            onCommit = {
                scope.launch {
                    discard()
                    show(id)
                }
            },
            revert = { show(id) }
        )
        notices.post(kind, undo = undo)
    }
}
