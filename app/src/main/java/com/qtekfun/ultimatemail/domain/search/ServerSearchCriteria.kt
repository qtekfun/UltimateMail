// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import java.util.Locale

/** What the user typed, as a request for a mail server (RF-09, "search on the server too"). */
object ServerSearchCriteria {
    private val BLANKS = Regex("[\\s\\p{Cntrl}]+")

    /**
     * The criteria of [query]. Text loses its line breaks and control characters (IMAP quoted
     * strings cannot hold them) and what is left empty is dropped. Phrases stay whole; the prefix
     * of the word being typed means nothing here, since servers match parts of words anyway.
     */
    fun of(query: SearchQuery): MailSearchCriteria = MailSearchCriteria(
        text = query.terms.texts(),
        excluded = query.excluded.texts(),
        from = query.from.texts(),
        to = query.to.texts(),
        subject = query.subject.texts(),
        labels = query.labels.map(::clean).filter { it.isNotEmpty() },
        unseen = query.unread,
        flagged = query.starred,
        hasAttachment = query.hasAttachment,
        since = query.after,
        before = query.before
    )

    private fun List<SearchTerm>.texts(): List<String> =
        map { clean(it.text) }.filter { it.any(Char::isLetterOrDigit) }

    private fun clean(text: String): String = text.replace(BLANKS, " ").trim()
}

/** How the words of `label:` and `in:` find folders. */
object LabelMatch {
    /** The folder role a word like `inbox` or `trash` stands for, in English. */
    fun role(label: String): FolderRole? = when (label.trim().lowercase(Locale.ROOT)) {
        "inbox" -> FolderRole.INBOX
        "sent" -> FolderRole.SENT
        "draft", "drafts" -> FolderRole.DRAFTS
        "trash", "bin" -> FolderRole.TRASH
        "spam", "junk" -> FolderRole.JUNK
        "archive" -> FolderRole.ARCHIVE
        "all", "allmail" -> FolderRole.ALL_MAIL
        "starred" -> FolderRole.STARRED
        else -> null
    }

    /** Whether [label] names the folder with this [role], [name] or [path]. */
    fun matches(label: String, role: FolderRole, name: String, path: String): Boolean {
        val wanted = label.trim()
        return role(wanted) == role ||
            name.equals(wanted, ignoreCase = true) ||
            path.equals(wanted, ignoreCase = true)
    }
}
