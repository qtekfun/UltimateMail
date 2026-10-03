// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The words of the quote block, in the language of the user (strings.xml: `compose_*`).
 * [attribution] has two placeholders: the date and the sender. [forwardMarker] must be a line of
 * dashes around the words (the signature editor recognises it as the start of a forwarded
 * message).
 */
data class QuoteTemplates(
    val attribution: String,
    val forwardMarker: String,
    val fromLabel: String,
    val dateLabel: String,
    val subjectLabel: String,
    val toLabel: String,
    val ccLabel: String,
    val locale: Locale,
    val zone: ZoneId
)

/** Supplies the [QuoteTemplates] of the current app language. */
fun interface QuoteTemplatesProvider {
    fun current(): QuoteTemplates
}

/**
 * Builds the text that goes below the user's own words in a reply or forward (RF-07), as plain
 * text: an attribution line ("On <date>, <name> <address> wrote:" in English, "El <fecha>,
 * <nombre> <dirección> escribió:" in Spanish) and the original quoted with `> `; or, for a
 * forward, a header block followed by the original text as it is.
 */
class QuoteBuilder(private val templates: QuoteTemplatesProvider) {
    /** The attribution line and the quoted [text], or just the line when there is no text. */
    fun reply(source: ComposeSource): String {
        val t = templates.current()
        val line = String.format(
            t.locale,
            t.attribution,
            date(t, source),
            who(source.from)
        )
        val quoted = quote(source.bodyText.orEmpty())
        return if (quoted.isEmpty()) line else "$line\n$quoted"
    }

    /** The forwarded-message block: marker, From/Date/Subject/To/Cc lines, blank line, text. */
    fun forward(source: ComposeSource): String {
        val t = templates.current()
        val header = buildList {
            add(t.forwardMarker)
            add("${t.fromLabel} ${who(source.from)}")
            add("${t.dateLabel} ${date(t, source)}")
            add("${t.subjectLabel} ${source.subject}")
            if (source.to.isNotEmpty()) add("${t.toLabel} ${RecipientParser.formatList(source.to)}")
            if (source.cc.isNotEmpty()) add("${t.ccLabel} ${RecipientParser.formatList(source.cc)}")
        }
        val text = normalize(source.bodyText.orEmpty())
        return header.joinToString("\n") + "\n\n" + text
    }

    private fun date(t: QuoteTemplates, source: ComposeSource): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(t.locale).format(source.sentAt.atZone(t.zone))

    private fun who(from: MailAddress?): String = when {
        from == null -> ""
        from.name.isNullOrBlank() -> from.address
        else -> "${from.name} <${from.address}>"
    }

    companion object {
        /** `> ` before every line (just `>` on blank ones); line endings become LF. */
        fun quote(text: String): String {
            if (text.isBlank()) return ""
            return normalize(text).trimEnd().lines().joinToString("\n") {
                if (it.isEmpty()) ">" else "> $it"
            }
        }

        private fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
