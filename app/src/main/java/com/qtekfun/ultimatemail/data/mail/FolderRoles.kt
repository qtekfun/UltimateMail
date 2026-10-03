// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import java.util.Locale

/** Finds the role of a folder from its SPECIAL-USE attributes (RFC 6154), then from its name. */
internal object FolderRoles {
    private val byAttribute = mapOf(
        "\\sent" to MailFolderRole.SENT,
        "\\drafts" to MailFolderRole.DRAFTS,
        "\\trash" to MailFolderRole.TRASH,
        "\\archive" to MailFolderRole.ARCHIVE,
        "\\junk" to MailFolderRole.JUNK,
        "\\all" to MailFolderRole.ALL_MAIL,
        "\\flagged" to MailFolderRole.STARRED
    )

    /** Names of servers that do not announce SPECIAL-USE; a fallback only. */
    private val byName = mapOf(
        "sent" to MailFolderRole.SENT,
        "sent items" to MailFolderRole.SENT,
        "sent messages" to MailFolderRole.SENT,
        "drafts" to MailFolderRole.DRAFTS,
        "trash" to MailFolderRole.TRASH,
        "deleted items" to MailFolderRole.TRASH,
        "deleted messages" to MailFolderRole.TRASH,
        "junk" to MailFolderRole.JUNK,
        "junk email" to MailFolderRole.JUNK,
        "spam" to MailFolderRole.JUNK,
        "archive" to MailFolderRole.ARCHIVE
    )

    fun detect(attributes: Collection<String>, name: String, path: String): MailFolderRole = when {
        path.equals("INBOX", ignoreCase = true) -> MailFolderRole.INBOX

        else -> attributes.firstNotNullOfOrNull { byAttribute[it.lowercase(Locale.ROOT)] }
            ?: byName[name.lowercase(Locale.ROOT)]
            ?: MailFolderRole.OTHER
    }
}
