// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The short messages shown in the snackbar. */
enum class NoticeKind {
    ARCHIVED,
    DELETED,
    MARKED_READ,
    MARKED_UNREAD,
    STARRED,
    UNSTARRED,
    NO_ARCHIVE_FOLDER,
    NO_TRASH_FOLDER,
    MOVE_SOON,
    COMPOSE_SOON,
    ATTACHMENT_FAILED,
    ATTACHMENT_GONE,
    ATTACHMENT_SAVED,
    ATTACHMENT_NOT_SAVED,
    NO_APP_FOR_ATTACHMENT
}

/**
 * A snackbar message; [undoable] ones offer "Undo" and are answered with undo or commit. [count]
 * is how many conversations it is about (the text says "Archived 3 conversations").
 */
data class ConversationNotice(
    val id: Long,
    val kind: NoticeKind,
    val undoable: Boolean,
    val count: Int = 1
)

/**
 * What an undoable notice can take back: [revert] undoes the change, and [accountIds] are the
 * accounts to sync when the window to undo is over.
 */
class PendingUndo(val accountIds: Set<Long>, val revert: suspend () -> Unit)

/**
 * The one snackbar of the app (RF-05): what the reading screen and the list did, with Undo.
 * It outlives the screens (it is a singleton), and a new notice replaces the one shown: what the
 * replaced one could still have undone is then final and gets synced.
 */
@Singleton
class NoticeCenter @Inject constructor(private val scheduler: SyncScheduler) {
    private var nextId = 0L
    private val pending = mutableMapOf<Long, PendingUndo>()
    private val current = MutableStateFlow<ConversationNotice?>(null)

    /** The message to show now, if any. */
    val notice: StateFlow<ConversationNotice?> = current

    /** Shows [kind]; with an [undo] it is offered "Undo". Returns the id of the notice. */
    fun post(kind: NoticeKind, count: Int = 1, undo: PendingUndo? = null): Long {
        current.value?.takeIf { it.undoable }?.let { commit(it.id) }
        val id = ++nextId
        if (undo != null) pending[id] = undo
        current.value = ConversationNotice(id, kind, undo != null, count)
        return id
    }

    /** "Undo" was tapped on [id]: returns what to take back (null for a stale id). */
    fun takeUndo(id: Long): PendingUndo? {
        val undo = pending.remove(id) ?: return null
        clear(id)
        return undo
    }

    /** The undo window of [id] is over: what it changed is sent to the server. */
    fun commit(id: Long) {
        pending.remove(id)?.accountIds?.forEach { scheduler.requestSync(it) }
        clear(id)
    }

    /** A message without undo was shown. */
    fun shown(id: Long) = clear(id)

    private fun clear(id: Long) {
        current.update { shown -> shown?.takeIf { it.id != id } }
    }
}
