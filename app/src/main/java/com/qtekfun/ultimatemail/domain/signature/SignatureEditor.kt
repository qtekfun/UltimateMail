// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.signature

/**
 * Puts signatures into draft bodies and swaps them when the sender account changes (RF-08).
 *
 * A signature is the delimiter line `-- ` followed by the signature text, preceded by a blank
 * line. Only that block is ever touched; the user's text, its line endings (LF or CRLF) and the
 * quoted text stay as they are. Quoted text is recognised by `>` prefixes (and by the usual
 * "Forwarded message" header), so a delimiter inside a quote, e.g. `> -- `, is never mistaken
 * for the signature of the draft.
 */
object SignatureEditor {
    /** The standard signature delimiter: dash, dash, space. */
    const val DELIMITER = "-- "

    /**
     * Adds the signature of [settings] to [body] for a message of [kind]. Replies and forwards get
     * it above the quote (below if [SignatureSettings.beforeQuote] is false); new messages get it
     * at the end. A body that already holds a signature is returned unchanged, so re-opening a
     * draft or chaining replies never duplicates it.
     */
    fun apply(body: String, kind: ComposeKind, settings: SignatureSettings): String {
        val block = settings.block
        val draft = Draft.parse(body)
        return if (block == null || draft.locate(block) != null) {
            body
        } else {
            draft.insert(block, kind, settings.beforeQuote).render()
        }
    }

    /**
     * Swaps the signature block of the account that was the sender ([previous], or null when it
     * had none) for the one of the new sender, in the same place, leaving everything else alone.
     * The previous block is found by its exact text; if the user edited it, the block that starts
     * at the last delimiter outside the quote is taken (by convention a signature runs to the end
     * of the message or to the quote). With no block in the body, the new one is added like
     * [apply] does; with a disabled or empty new signature the old block is just removed.
     */
    fun replace(
        body: String,
        previous: SignatureSettings?,
        next: SignatureSettings,
        kind: ComposeKind
    ): String {
        val draft = Draft.parse(body)
        val nextBlock = next.block
        val found = draft.locate(previous?.block)
        return when {
            found != null && nextBlock != null -> draft.swap(found, nextBlock).render()
            found != null -> draft.remove(found).render()
            nextBlock != null -> draft.insert(nextBlock, kind, next.beforeQuote).render()
            else -> body
        }
    }

    /** Whether [body] already holds a signature block of its own (not one inside a quote). */
    fun hasSignature(body: String): Boolean = Draft.parse(body).locate(null) != null
}
