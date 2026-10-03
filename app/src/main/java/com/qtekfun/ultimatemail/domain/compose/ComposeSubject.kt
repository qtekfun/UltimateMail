// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.thread.ThreadKeys

/**
 * The subject of a reply or a forward (RF-07). A prefix is added only when the subject does not
 * start with one of that kind already, in any of the languages `domain.thread` knows (`Re:`,
 * `AW:`, `SV:`, `RE[2]:` for replies; `Fwd:`, `FW:`, `WG:`, `RV:`, `ENC:`, `TR:` for forwards),
 * so `Re: Re:` never stacks and `AW: hello` stays as it is.
 */
object ComposeSubject {
    private val replyPrefixes = setOf("re", "aw", "sv", "vs")
    private val forwardPrefixes =
        setOf("fw", "fwd", "wg", "enc", "tr", "rv", "reenvio", "reenvío", "env")

    fun reply(subject: String): String = prefixed(subject, "Re: ", replyPrefixes)

    fun forward(subject: String): String = prefixed(subject, "Fwd: ", forwardPrefixes)

    private fun prefixed(subject: String, prefix: String, known: Set<String>): String {
        val trimmed = subject.trim()
        return if (ThreadKeys.leadingPrefix(trimmed) in known) trimmed else prefix + trimmed
    }
}
