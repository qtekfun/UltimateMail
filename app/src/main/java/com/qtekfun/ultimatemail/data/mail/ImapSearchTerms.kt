// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.NotTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SentDateTerm
import jakarta.mail.search.SubjectTerm
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * [MailSearchCriteria] as the search keys of IMAP SEARCH: SUBJECT, FROM, TO, CC, BODY, SEEN,
 * FLAGGED, SENTSINCE and SENTBEFORE, which every server has. Free text is looked for in the
 * subject, the sender and the body, like the local search does. The library writes the command;
 * text is sent as a quoted string or literal, so it cannot add keys of its own.
 */
internal object ImapSearchTerms {
    /** The term for [criteria], or null when it asks for nothing a server can search for. */
    fun build(criteria: MailSearchCriteria): SearchTerm? {
        val terms = buildList<SearchTerm> {
            criteria.text.forEach { add(anywhere(it)) }
            criteria.excluded.forEach { add(NotTerm(anywhere(it))) }
            criteria.from.forEach { add(FromStringTerm(it)) }
            criteria.to.forEach { add(recipient(it)) }
            criteria.subject.forEach { add(SubjectTerm(it)) }
            criteria.unseen?.let { add(FlagTerm(Flags(Flags.Flag.SEEN), !it)) }
            if (criteria.flagged) add(FlagTerm(Flags(Flags.Flag.FLAGGED), true))
            criteria.since?.let { add(SentDateTerm(ComparisonTerm.GE, startOf(it))) }
            criteria.before?.let { add(SentDateTerm(ComparisonTerm.LT, startOf(it))) }
        }
        return when (terms.size) {
            0 -> null
            1 -> terms.single()
            else -> AndTerm(terms.toTypedArray())
        }
    }

    private fun anywhere(text: String): SearchTerm =
        OrTerm(arrayOf(SubjectTerm(text), FromStringTerm(text), BodyTerm(text)))

    private fun recipient(text: String): SearchTerm = OrTerm(
        RecipientStringTerm(Message.RecipientType.TO, text),
        RecipientStringTerm(Message.RecipientType.CC, text)
    )

    /**
     * The server compares dates only, and the library writes this one in the JVM's zone, so
     * midnight of the day in that same zone is the date that was meant.
     */
    private fun startOf(day: LocalDate): Date =
        Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
}
