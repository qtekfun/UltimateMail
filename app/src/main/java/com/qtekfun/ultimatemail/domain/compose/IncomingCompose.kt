// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress

/**
 * What another app asked the composer to start with (a `mailto:` link, a share): already
 * checked and capped, so the screen can use it as it is. [attachments] are `content:` URIs, to
 * be read only through [AttachmentSource]. [toString] shows no content.
 */
data class IncomingCompose(
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachments: List<String> = emptyList()
) {
    override fun toString(): String = "IncomingCompose(REDACTED)"
}

/**
 * An Android intent reduced to the plain values the composer cares about, so that
 * [IncomingParser] is pure code. The activity fills it from the real intent and must not trust
 * it: any extra that is missing or of another type is just left out.
 */
data class IncomingIntent(
    val action: String?,
    /** The intent's data URI as text (`mailto:...` for SENDTO). */
    val data: String? = null,
    val text: String? = null,
    val subject: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    /** `EXTRA_STREAM` URIs as text (one for SEND, several for SEND_MULTIPLE). */
    val streams: List<String> = emptyList()
) {
    override fun toString(): String = "IncomingIntent(action=$action)"
}

/**
 * Reads the intents that start a message from outside (RF-07): `ACTION_SENDTO` with a `mailto:`
 * URI (RFC 6068: `to`, `cc`, `bcc`, `subject` and `body`), `ACTION_SEND` and
 * `ACTION_SEND_MULTIPLE` (text and file streams).
 *
 * Nothing in an intent is trusted. Malformed parts are dropped, never repaired into something
 * else: invalid addresses, URIs that are not `content:` (a `file:` URI could point into the
 * app's private storage), URIs of the app's own provider ([ownAuthorities], which a hostile app
 * could use to make the app attach its own files) and anything beyond the caps below. Subject
 * line breaks become spaces (no header injection). The files themselves are read later by
 * `DraftAttachments`, which enforces the size limits.
 */
object IncomingParser {
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    const val MAX_RECIPIENTS = 100
    const val MAX_ATTACHMENTS = 20
    const val MAX_SUBJECT = 998
    const val MAX_BODY = 200_000
    const val MAX_URI = 2048
    private const val MAILTO = "mailto:"
    private const val CONTENT = "content://"

    /** The composer's starting point for [intent], or null if it is not for the composer. */
    fun parse(intent: IncomingIntent, ownAuthorities: Set<String> = emptySet()): IncomingCompose? =
        when (intent.action) {
            ACTION_SENDTO -> mailto(intent)?.let { withExtras(it, intent, ownAuthorities) }

            ACTION_SEND, ACTION_SEND_MULTIPLE -> {
                withExtras(IncomingCompose(), intent, ownAuthorities)
                    .takeIf { it.hasContent() }
            }

            else -> null
        }

    private fun IncomingCompose.hasContent() =
        body.isNotEmpty() || attachments.isNotEmpty() || subject.isNotEmpty() ||
            to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()

    /** The values of the `mailto:` URI; null when the data is missing or not a mailto. */
    private fun mailto(intent: IncomingIntent): IncomingCompose? {
        val data = intent.data?.trim()?.takeIf { it.startsWith(MAILTO, ignoreCase = true) }
            ?: return null
        val rest = data.substring(MAILTO.length).substringBefore('#')
        val query = rest.substringAfter('?', "")
        val params = query.split('&').filter { it.isNotEmpty() }.map {
            it.substringBefore('=').lowercase() to PercentDecoder.decode(it.substringAfter('=', ""))
        }

        fun values(name: String) = params.filter { it.first == name }.map { it.second }
        val path = PercentDecoder.decode(rest.substringBefore('?'))
        return IncomingCompose(
            to = recipients(listOf(path) + values("to")),
            cc = recipients(values("cc")),
            bcc = recipients(values("bcc")),
            subject = cleanSubject(values("subject").firstOrNull()),
            body = cleanBody(values("body").firstOrNull())
        )
    }

    /** Fills what the URI left empty from the intent's extras and adds the streams. */
    private fun withExtras(
        base: IncomingCompose,
        intent: IncomingIntent,
        ownAuthorities: Set<String>
    ) = base.copy(
        to = distinct(base.to + recipients(intent.to)),
        cc = distinct(base.cc + recipients(intent.cc)),
        bcc = distinct(base.bcc + recipients(intent.bcc)),
        subject = base.subject.ifEmpty { cleanSubject(intent.subject) },
        body = base.body.ifEmpty { cleanBody(intent.text) },
        attachments = streams(intent.streams, ownAuthorities)
    )

    private fun recipients(entries: List<String>): List<MailAddress> =
        distinct(entries.flatMap { RecipientParser.parseList(it.take(MAX_URI)).valid })

    private fun distinct(addresses: List<MailAddress>) = addresses
        .distinctBy { it.address.lowercase() }
        .take(MAX_RECIPIENTS)

    private fun cleanSubject(text: String?): String = text.orEmpty()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .trim()
        .take(MAX_SUBJECT)

    private fun cleanBody(text: String?): String = text.orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .take(MAX_BODY)

    private fun streams(uris: List<String>, ownAuthorities: Set<String>): List<String> {
        val own = ownAuthorities.map { it.lowercase() }.toSet()
        return uris
            .map { it.trim() }
            .filter { it.length <= MAX_URI && isReadableContentUri(it, own) }
            .distinct()
            .take(MAX_ATTACHMENTS)
    }

    private fun isReadableContentUri(uri: String, own: Set<String>): Boolean {
        if (!uri.startsWith(CONTENT, ignoreCase = true)) return false
        val authority = uri.substring(CONTENT.length).takeWhile { it != '/' && it != '?' }
        return authority.isNotEmpty() && '@' !in authority && authority.lowercase() !in own &&
            uri.none { it.isISOControl() }
    }
}
