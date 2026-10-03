// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import com.qtekfun.ultimatemail.domain.inbox.ConversationBulkActions
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.domain.inbox.RowTargets
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import com.qtekfun.ultimatemail.ui.conversation.PendingUndo
import javax.inject.Inject

/**
 * Does what a swipe or the selection bar asks (T16) and tells the user: the change is applied and
 * queued through [ConversationBulkActions], then the snackbar says what was done, with Undo, and
 * the sync waits for the end of the undo window. The folder picker goes through [movePicker].
 */
class RowActionRunner @Inject constructor(
    private val bulk: ConversationBulkActions,
    private val notices: NoticeCenter,
    private val movePicker: MovePickerLauncher
) {
    /** Applies [change] to [items]; returns whether any conversation changed. */
    suspend fun run(
        change: RowChange,
        items: List<ConversationItem>,
        targets: RowTargets
    ): Boolean {
        val result = bulk.apply(change, items, targets)
        val undo = result.undo ?: return false
        notices.post(
            change.notice(),
            result.applied,
            PendingUndo(undo.accountIds) { bulk.undo(undo) }
        )
        return true
    }

    /** Opens the folder picker for every message of [items] (all of one account). */
    suspend fun pickFolder(items: List<ConversationItem>) {
        val messages = bulk.messagesOf(items)
        if (messages.isNotEmpty()) {
            movePicker.open(MovePickerRequest(items.first().accountId, messages))
        }
    }

    /** Tells the user what the swipe could not do. */
    fun report(kind: NoticeKind) {
        notices.post(kind)
    }

    private fun RowChange.notice() = when (this) {
        RowChange.ARCHIVE -> NoticeKind.ARCHIVED
        RowChange.DELETE -> NoticeKind.DELETED
        RowChange.MARK_READ -> NoticeKind.MARKED_READ
        RowChange.MARK_UNREAD -> NoticeKind.MARKED_UNREAD
        RowChange.STAR -> NoticeKind.STARRED
        RowChange.UNSTAR -> NoticeKind.UNSTARRED
    }
}
