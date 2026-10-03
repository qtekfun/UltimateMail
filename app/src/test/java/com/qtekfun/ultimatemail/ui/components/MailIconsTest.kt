// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailIconsTest {
    @Test
    fun `icons that point somewhere flip in a right-to-left layout`() {
        assertTrue(MailIcons.Reply.autoMirror)
        assertTrue(MailIcons.ReplyAll.autoMirror)
        assertTrue(MailIcons.Forward.autoMirror)
        assertTrue(MailIcons.Move.autoMirror)
    }

    @Test
    fun `icons that are objects do not flip`() {
        listOf(
            MailIcons.Inbox,
            MailIcons.Archive,
            MailIcons.Attachment,
            MailIcons.Folder,
            MailIcons.Label,
            MailIcons.StarOutline,
            MailIcons.Download
        ).forEach { assertFalse(it.autoMirror, it.name) }
    }
}
