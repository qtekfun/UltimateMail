// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

import java.time.LocalDate
import java.util.Locale

/**
 * What to ask a server to search for in one folder (RF-09). Every part narrows the result. Plain
 * IMAP answers it with SEARCH keys; Gmail with its own search syntax (X-GM-RAW), which
 * [GmailRawQuery] writes. Text is matched as a substring, case-insensitively, as IMAP does.
 * [toString] shows no search text: it can name people and subjects.
 *
 * @property text words and phrases that must all be found in the subject, the sender or the body.
 * @property excluded words and phrases that must not be found in any of them.
 * @property from, to, subject text of the sender, the recipients and the subject.
 * @property labels Gmail labels (or folder names); only Gmail's search can use them.
 * @property unseen true for unread messages, false for read ones, null for both.
 * @property since first day included; [before] first day excluded.
 * @property hasAttachment plain IMAP cannot search for it: the caller checks the headers.
 */
data class MailSearchCriteria(
    val text: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val from: List<String> = emptyList(),
    val to: List<String> = emptyList(),
    val subject: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val unseen: Boolean? = null,
    val flagged: Boolean = false,
    val hasAttachment: Boolean = false,
    val since: LocalDate? = null,
    val before: LocalDate? = null
) {
    override fun toString(): String = "MailSearchCriteria(REDACTED)"

    companion object {
        /** The most hits asked of one folder: the newest ones, which are the ones shown first. */
        const val DEFAULT_LIMIT = 200
    }
}

/**
 * [MailSearchCriteria] in Gmail's search syntax, the argument of `X-GM-RAW`. Words and phrases
 * are always quoted, so that capital `OR`, `AND`, a leading `-` or `{ }` in what the user typed
 * is text and never an operator; quotes inside the text are dropped.
 */
object GmailRawQuery {
    fun of(criteria: MailSearchCriteria): String = buildList {
        criteria.text.forEach { add(quoted(it)) }
        criteria.excluded.forEach { add("-" + quoted(it)) }
        criteria.from.forEach { add("from:" + quoted(it)) }
        criteria.to.forEach { add("to:" + quoted(it)) }
        criteria.subject.forEach { add("subject:" + quoted(it)) }
        criteria.labels.forEach { add("label:" + quoted(it)) }
        criteria.unseen?.let { add(if (it) "is:unread" else "is:read") }
        if (criteria.flagged) add("is:starred")
        if (criteria.hasAttachment) add("has:attachment")
        criteria.since?.let { add("after:" + day(it)) }
        criteria.before?.let { add("before:" + day(it)) }
    }.joinToString(" ")

    private fun quoted(text: String): String = "\"" + text.replace('"', ' ').trim() + "\""

    private fun day(date: LocalDate): String =
        String.format(Locale.ROOT, "%04d/%02d/%02d", date.year, date.monthValue, date.dayOfMonth)
}
