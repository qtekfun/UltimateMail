// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import com.qtekfun.ultimatemail.domain.compose.IncomingCompose
import com.qtekfun.ultimatemail.domain.conversation.ComposeLauncher
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Something that should end with the composer open on a draft. */
sealed interface ComposeStart {
    /** A reply, reply all or forward of a message of the reader. */
    data class Message(val request: ComposeRequest) : ComposeStart

    /** A new, empty message from [accountId]. */
    data class New(val accountId: Long) : ComposeStart

    /** An existing draft (the Drafts list, the outbox, or Undo). */
    data class Draft(val draftId: Long) : ComposeStart

    /** A message another app asked for (mailto, share). */
    data class Incoming(val incoming: IncomingCompose) : ComposeStart
}

/**
 * The queue between whoever wants the composer (the reader, an intent, Undo) and the screen
 * that opens it. Drafts are created off the main thread, so asking is not the same as opening:
 * `ComposeEntryViewModel` takes the starts from here, makes the draft and navigates.
 */
@Singleton
class ComposeEntry @Inject constructor() {
    private val channel = Channel<ComposeStart>(Channel.UNLIMITED)

    val starts: Flow<ComposeStart> = channel.receiveAsFlow()

    fun request(start: ComposeStart) {
        channel.trySend(start)
    }
}

/** The reading screen's Reply, Reply all and Forward: the draft is made and shown by the entry. */
class QueuedComposeLauncher @Inject constructor(private val entry: ComposeEntry) :
    ComposeLauncher {
    override fun start(request: ComposeRequest): Boolean {
        entry.request(ComposeStart.Message(request))
        return true
    }
}
