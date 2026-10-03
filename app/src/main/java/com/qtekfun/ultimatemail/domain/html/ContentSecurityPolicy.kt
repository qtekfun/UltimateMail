// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** The Content-Security-Policy every rendered message carries. */
object ContentSecurityPolicy {
    /**
     * Nothing loads by default; images may come from `data:` and `cid:` and, only when the
     * reader allowed remote content for the message, from `https:`. Inline styles are what
     * newsletters use. Scripts, frames, fonts, forms and `<base>` are all shut.
     */
    fun build(allowRemoteContent: Boolean): String {
        val images = if (allowRemoteContent) "data: cid: https:" else "data: cid:"
        return "default-src 'none'; img-src $images; style-src 'unsafe-inline'; " +
            "base-uri 'none'; form-action 'none'"
    }
}
