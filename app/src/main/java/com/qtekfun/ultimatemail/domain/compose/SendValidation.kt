// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind

/** What the composer has to do before a message may be sent. */
sealed interface SendCheck {
    /** Nobody to send it to. */
    data object NoRecipients : SendCheck

    /** At least one recipient is not an address. */
    data object InvalidRecipient : SendCheck

    /** An attached file is gone from the device. */
    data object AttachmentMissing : SendCheck

    /** Ask first: the subject is empty. */
    data object ConfirmEmptySubject : SendCheck

    /** Ask first: nothing was written and nothing is attached. */
    data object ConfirmEmptyBody : SendCheck

    data object Ready : SendCheck
}

/** What the user already agreed to send as it is. */
data class SendConfirmations(val emptySubject: Boolean = false, val emptyBody: Boolean = false)

/** The composer's content as far as sending needs it. [toString] shows nothing of it. */
data class SendInput(
    val to: RecipientFieldState,
    val cc: RecipientFieldState,
    val bcc: RecipientFieldState,
    val subject: String,
    val body: String,
    val kind: DraftKind,
    /** The text the draft started with (signature, quote), while the user has not saved edits. */
    val templateBody: String?,
    val attachmentCount: Int,
    val missingAttachment: Boolean
) {
    override fun toString(): String = "SendInput(REDACTED)"
}

/**
 * The checks before sending (RF-07), in the order the user should meet them: recipients first
 * (none, or one that is not an address), then a missing attachment, then the two questions
 * (empty subject, empty body without attachments), each asked once.
 *
 * "Empty body" means the user wrote nothing: the text is blank, or still the template with only
 * the signature and the quote. A forward without a comment is normal, so it is never asked.
 */
object SendValidation {
    fun check(input: SendInput, confirmed: SendConfirmations = SendConfirmations()): SendCheck {
        val fields = listOf(input.to, input.cc, input.bcc)
        return when {
            fields.all { it.chips.isEmpty() && it.input.isBlank() } -> SendCheck.NoRecipients

            fields.any { it.hasInvalid || it.input.isNotBlank() } -> SendCheck.InvalidRecipient

            input.missingAttachment -> SendCheck.AttachmentMissing

            input.subject.isBlank() && !confirmed.emptySubject -> SendCheck.ConfirmEmptySubject

            isBodyEmpty(input) && input.attachmentCount == 0 && !confirmed.emptyBody ->
                SendCheck.ConfirmEmptyBody

            else -> SendCheck.Ready
        }
    }

    private fun isBodyEmpty(input: SendInput): Boolean = when {
        input.body.isBlank() -> true
        input.kind == DraftKind.FORWARD -> false
        else -> input.templateBody != null && input.body.trim() == input.templateBody.trim()
    }
}
