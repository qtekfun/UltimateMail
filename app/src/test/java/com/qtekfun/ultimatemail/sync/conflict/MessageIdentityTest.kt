// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageIdentityTest {
    @Test
    fun `is empty only without both ids`() {
        assertTrue(MessageIdentity().isEmpty())
        assertFalse(MessageIdentity(messageId = "<a@x>").isEmpty())
        assertFalse(MessageIdentity(gmailMessageId = 7).isEmpty())
    }

    @Test
    fun `matches by message id`() {
        assertTrue(MessageIdentity("<a@x>").matches(MessageIdentity("<a@x>", 3)))
        assertFalse(MessageIdentity("<a@x>").matches(MessageIdentity("<b@x>")))
    }

    @Test
    fun `matches by gmail id when the message ids differ or are missing`() {
        assertTrue(MessageIdentity(gmailMessageId = 3).matches(MessageIdentity("<b@x>", 3)))
        assertFalse(MessageIdentity("<a@x>", 3).matches(MessageIdentity("<b@x>", 4)))
    }

    @Test
    fun `missing ids never match each other`() {
        assertFalse(MessageIdentity().matches(MessageIdentity()))
        assertFalse(MessageIdentity("<a@x>").matches(MessageIdentity(gmailMessageId = 3)))
    }
}
