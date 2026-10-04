// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import javax.inject.Inject

/** A draft ready for the composer; [attachmentsSkipped] files that should be in it are not. */
data class OpenedDraft(val draft: Draft, val attachmentsSkipped: Int) {
    override fun toString(): String = "OpenedDraft(id=${draft.id}, skipped=$attachmentsSkipped)"
}

/**
 * The two starts of the composer that need more than the engine (RF-07): a forward, which carries
 * the attachments of the message ([ForwardAttachments]), and a draft that only the server has
 * ([ServerDraftImport]). Replies and the rest stay with [ComposeEngine].
 */
class ComposeOpener @Inject constructor(
    private val engine: ComposeEngine,
    private val attachments: DraftAttachments,
    private val forwarded: ForwardAttachments,
    private val serverDrafts: ServerDraftImport
) {
    /** A reply, reply all or forward of the message in [request]; null if it is gone. */
    suspend fun message(request: ComposeRequest): OpenedDraft? {
        val draft = engine.start(request) ?: return null
        val skipped = if (request.mode == ComposeMode.FORWARD) {
            forwarded.attach(draft.id, request.messageId).skipped
        } else {
            0
        }
        return OpenedDraft(draft, skipped)
    }

    /** The draft held only by the server in message row [messageRowId]; null if it cannot open. */
    suspend fun serverDraft(messageRowId: Long): OpenedDraft? =
        (serverDrafts.open(messageRowId) as? ServerDraftOpen.Opened)
            ?.let { OpenedDraft(it.draft, it.attachmentsSkipped) }

    /** Attaches the files at [uris] to [draftId]; returns how many could not be attached. */
    suspend fun attachPicked(draftId: Long, uris: List<String>): Int =
        uris.count { attachments.add(draftId, it) !is AddAttachmentResult.Added }
}
