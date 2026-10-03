// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SendValidationTest {
    private val ana = RecipientFieldState.of(listOf(MailAddress("ana@example.test")))

    private fun input(
        to: RecipientFieldState = ana,
        cc: RecipientFieldState = RecipientFieldState(),
        subject: String = "Hi",
        body: String = "Text",
        kind: DraftKind = DraftKind.NEW,
        template: String? = null,
        attachments: Int = 0,
        missing: Boolean = false
    ) = SendInput(to, cc, RecipientFieldState(), subject, body, kind, template, attachments, missing)

    @Test
    fun `a complete message is ready`() {
        assertEquals(SendCheck.Ready, SendValidation.check(input()))
    }

    @Test
    fun `without recipients nothing is sent`() {
        assertEquals(
            SendCheck.NoRecipients,
            SendValidation.check(input(to = RecipientFieldState()))
        )
    }

    @Test
    fun `a recipient in cc alone is enough`() {
        assertEquals(
            SendCheck.Ready,
            SendValidation.check(input(to = RecipientFieldState(), cc = ana))
        )
    }

    @Test
    fun `an invalid chip or text still being typed blocks sending`() {
        val invalid = RecipientFields.commit(RecipientFieldState(input = "nobody"))

        assertEquals(SendCheck.InvalidRecipient, SendValidation.check(input(to = invalid)))
        assertEquals(
            SendCheck.InvalidRecipient,
            SendValidation.check(input(cc = RecipientFieldState(input = "an")))
        )
    }

    @Test
    fun `typed text alone in to counts as a recipient being written, not as no recipient`() {
        assertEquals(
            SendCheck.InvalidRecipient,
            SendValidation.check(input(to = RecipientFieldState(input = "ana@example.test")))
        )
    }

    @Test
    fun `a missing attachment blocks sending`() {
        assertEquals(SendCheck.AttachmentMissing, SendValidation.check(input(missing = true)))
    }

    @Test
    fun `an empty subject is asked once`() {
        assertEquals(SendCheck.ConfirmEmptySubject, SendValidation.check(input(subject = "  ")))
        assertEquals(
            SendCheck.Ready,
            SendValidation.check(input(subject = ""), SendConfirmations(emptySubject = true))
        )
    }

    @Test
    fun `a blank body is asked once unless there are attachments`() {
        assertEquals(SendCheck.ConfirmEmptyBody, SendValidation.check(input(body = "\n")))
        assertEquals(
            SendCheck.Ready,
            SendValidation.check(input(body = ""), SendConfirmations(emptyBody = true))
        )
        assertEquals(SendCheck.Ready, SendValidation.check(input(body = "", attachments = 1)))
    }

    @Test
    fun `a reply that is still the template counts as empty but a forward does not`() {
        val template = "\n\n-- \nAna\n\nOn Monday Bob wrote:\n> Hi"

        assertEquals(
            SendCheck.ConfirmEmptyBody,
            SendValidation.check(
                input(body = "$template\n", kind = DraftKind.REPLY, template = template)
            )
        )
        assertEquals(
            SendCheck.Ready,
            SendValidation.check(
                input(body = template, kind = DraftKind.FORWARD, template = template)
            )
        )
        assertEquals(
            SendCheck.Ready,
            SendValidation.check(
                input(body = "Thanks!$template", kind = DraftKind.REPLY, template = template)
            )
        )
    }

    @Test
    fun `the subject question comes before the body question and after the recipients`() {
        val nothing = input(to = RecipientFieldState(), subject = "", body = "")

        assertEquals(SendCheck.NoRecipients, SendValidation.check(nothing))
        assertEquals(
            SendCheck.ConfirmEmptySubject,
            SendValidation.check(nothing.copy(to = ana))
        )
        assertEquals(
            SendCheck.ConfirmEmptyBody,
            SendValidation.check(nothing.copy(to = ana), SendConfirmations(emptySubject = true))
        )
    }

    @Test
    fun `the account choice uses the one on screen then the only one then asks`() {
        assertEquals(AccountChoice.None, IncomingAccountChoice.choose(emptyList(), 1))
        assertEquals(AccountChoice.Use(2), IncomingAccountChoice.choose(listOf(1, 2), 2))
        assertEquals(AccountChoice.Use(7), IncomingAccountChoice.choose(listOf(7), null))
        assertEquals(AccountChoice.Use(7), IncomingAccountChoice.choose(listOf(7), 99))
        assertEquals(
            AccountChoice.Ask(listOf(1, 2)),
            IncomingAccountChoice.choose(listOf(1, 2), null)
        )
        assertEquals(
            AccountChoice.Ask(listOf(1, 2)),
            IncomingAccountChoice.choose(listOf(1, 2), 99)
        )
    }
}
