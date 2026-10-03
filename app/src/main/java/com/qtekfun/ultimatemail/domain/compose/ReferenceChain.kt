// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.thread.ThreadKeys

/**
 * The In-Reply-To and References headers of a reply (RFC 5322 section 3.6.4): In-Reply-To is the
 * Message-ID of the message answered; References is the References of that message (or its
 * In-Reply-To, for old clients that sent no References) followed by its Message-ID.
 *
 * Chains grow with every reply, so they are trimmed to [MAX_REFERENCES] ids: the first one (the
 * root of the conversation, which threading relies on) and the most recent ones. Ids that are not
 * usable (blank, with spaces) are dropped and repeats are kept once.
 */
object ReferenceChain {
    const val MAX_REFERENCES = 20

    data class Headers(val inReplyTo: String?, val references: List<String>)

    fun forReply(source: ComposeSource): Headers {
        val parent = source.messageId?.takeIf { ThreadKeys.messageId(it) != null }?.trim()
        val chain = source.references.ifEmpty { listOfNotNull(source.inReplyTo) }
        val parentKey = ThreadKeys.messageId(parent)
        // The parent always ends the chain, wherever the older references mention it.
        val older = chain.filter { ThreadKeys.messageId(it) != null }
            .map { it.trim() }
            .filter { ThreadKeys.messageId(it) != parentKey }
            .distinctBy { ThreadKeys.messageId(it) }
        val ids = older + listOfNotNull(parent)
        return Headers(parent, trim(ids))
    }

    private fun trim(ids: List<String>): List<String> = if (ids.size <=
        MAX_REFERENCES
    ) {
        ids
    } else {
        listOf(ids.first()) + ids.takeLast(MAX_REFERENCES - 1)
    }
}
