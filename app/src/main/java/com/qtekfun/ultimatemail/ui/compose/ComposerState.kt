// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.compose.AttachmentLimits
import com.qtekfun.ultimatemail.domain.compose.DraftAttachment
import com.qtekfun.ultimatemail.domain.compose.RecipientFieldState
import com.qtekfun.ultimatemail.domain.mail.MailAddress

/** The three recipient fields. */
enum class RecipientKind { TO, CC, BCC }

/** Where the composer is in its life. */
enum class ComposerPhase {
    /** Reading the draft from Room. */
    LOADING,

    EDITING,

    /** The draft does not exist (sent, discarded) or is in the outbox. */
    GONE,

    /** The composer was left (closed, discarded or sent): the screen should go back. */
    FINISHED
}

/** A question the composer asks before going on. */
enum class ComposerDialog { EMPTY_SUBJECT, EMPTY_BODY, DISCARD }

/** Something the user has to be told, shown inside the composer until dismissed. */
sealed interface ComposerMessage {
    data object NoRecipients : ComposerMessage

    data object InvalidRecipient : ComposerMessage

    data object AttachmentMissing : ComposerMessage

    data class AttachmentTooLarge(val limitBytes: Long) : ComposerMessage

    data object AttachmentUnreadable : ComposerMessage

    /** The draft cannot be changed any more. */
    data object DraftGone : ComposerMessage
}

/** An account the message can be sent from. */
data class SenderOption(val id: Long, val email: String, val name: String) {
    override fun toString(): String = "SenderOption(id=$id)"
}

/** The suggestions for what is being typed in [field]. */
data class RecipientSuggestionList(val field: RecipientKind, val items: List<MailAddress>) {
    override fun toString(): String = "RecipientSuggestionList(${items.size})"
}

/**
 * Everything the composer screen shows. Text is held here while editing and saved to Room
 * (autosave), which is the real owner of the draft: after process death only the draft id is
 * needed to get all of this back. [toString] shows no content.
 */
data class ComposerState(
    val phase: ComposerPhase = ComposerPhase.LOADING,
    val draftId: Long = 0,
    val kind: DraftKind = DraftKind.NEW,
    val senderId: Long = 0,
    val senders: List<SenderOption> = emptyList(),
    val to: RecipientFieldState = RecipientFieldState(),
    val cc: RecipientFieldState = RecipientFieldState(),
    val bcc: RecipientFieldState = RecipientFieldState(),
    /** Cc and Bcc are folded away until asked for or until the draft has any. */
    val showCcBcc: Boolean = false,
    val subject: String = "",
    val body: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val suggestions: RecipientSuggestionList? = null,
    val dialog: ComposerDialog? = null,
    val message: ComposerMessage? = null
) {
    val attachmentBytes: Long get() = attachments.sumOf { it.size }

    /** The attachments are big enough that many servers will refuse the message. */
    val attachmentWarning: Boolean get() = AttachmentLimits.isOverWarning(attachmentBytes)

    fun field(kind: RecipientKind): RecipientFieldState = when (kind) {
        RecipientKind.TO -> to
        RecipientKind.CC -> cc
        RecipientKind.BCC -> bcc
    }

    fun withField(kind: RecipientKind, value: RecipientFieldState): ComposerState = when (kind) {
        RecipientKind.TO -> copy(to = value)
        RecipientKind.CC -> copy(cc = value)
        RecipientKind.BCC -> copy(bcc = value)
    }

    override fun toString(): String = "ComposerState(phase=$phase, draft=$draftId)"
}
