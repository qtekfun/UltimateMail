// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoadMessageBodyInlineTest {
    @Test
    fun `attachments keep their normalized content id and whether they are inline`() = runTest {
        val h = EngineHarness(this)
        try {
            h.server.folder("INBOX", MailFolderRole.INBOX)
            h.server.deliver("INBOX")
            h.server.folder("INBOX").bodies[1] = MessageBody(
                text = null,
                html = "<img src=\"cid:Logo@x\">",
                attachments = listOf(
                    AttachmentInfo("2", "logo.png", "image/png", 5, "<Logo@x>", true),
                    AttachmentInfo("3", "a.pdf", "application/pdf", 9, null, false),
                    AttachmentInfo("4", "b.pdf", "application/pdf", 9, "<>", false)
                )
            )
            h.addAccount()
            h.engine.sync(h.accountId)
            val id = h.messages.get(h.accountId, "INBOX", 1)!!.id

            LoadMessageBody(h.messages, h.db.attachmentDao(), h.sessions)(id)

            val stored = h.db.attachmentDao().observe(id).first()
            assertEquals("logo@x", stored[0].contentId)
            assertTrue(stored[0].inline)
            assertNull(stored[1].contentId)
            assertFalse(stored[1].inline)
            assertNull(stored[2].contentId)
        } finally {
            h.close()
        }
    }
}
