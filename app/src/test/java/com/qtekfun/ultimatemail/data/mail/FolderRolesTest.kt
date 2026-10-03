// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FolderRolesTest {
    private fun role(name: String, vararg attributes: String, path: String = name) =
        FolderRoles.detect(attributes.toList(), name, path)

    @Test
    fun `INBOX is found by name in any case`() {
        assertEquals(MailFolderRole.INBOX, role("INBOX"))
        assertEquals(MailFolderRole.INBOX, role("Inbox", path = "inbox"))
    }

    @Test
    fun `SPECIAL-USE attributes win over names`() {
        assertEquals(MailFolderRole.SENT, role("Enviados", "\\HasNoChildren", "\\Sent"))
        assertEquals(MailFolderRole.DRAFTS, role("Borradores", "\\Drafts"))
        assertEquals(MailFolderRole.TRASH, role("Papelera", "\\Trash"))
        assertEquals(MailFolderRole.ARCHIVE, role("Archivo", "\\Archive"))
        assertEquals(MailFolderRole.JUNK, role("Correo no deseado", "\\Junk"))
        assertEquals(MailFolderRole.ALL_MAIL, role("All Mail", "\\All"))
        assertEquals(MailFolderRole.STARRED, role("Starred", "\\FLAGGED"))
        assertEquals(MailFolderRole.TRASH, role("Sent", "\\Trash"))
    }

    @Test
    fun `well known names are a fallback when no attribute says it`() {
        assertEquals(MailFolderRole.SENT, role("Sent Items"))
        assertEquals(MailFolderRole.TRASH, role("Deleted Items"))
        assertEquals(MailFolderRole.JUNK, role("Spam"))
        assertEquals(MailFolderRole.DRAFTS, role("DRAFTS"))
    }

    @Test
    fun `other folders have no role`() {
        assertEquals(MailFolderRole.OTHER, role("Receipts", "\\HasNoChildren"))
    }
}
