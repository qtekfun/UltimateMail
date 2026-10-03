// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** Builds the page a message is shown in. */
object EmailDocument {
    private const val BASE_STYLE =
        "body{margin:8px;overflow-wrap:anywhere}img{max-width:100%;height:auto}"

    /**
     * Wraps the sanitized fragment in a complete page. The CSP comes first, before any content,
     * so nothing in the message can run ahead of it.
     */
    fun wrap(sanitized: SanitizedHtml, allowRemoteContent: Boolean): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"${ContentSecurityPolicy.build(allowRemoteContent)}\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$BASE_STYLE</style></head><body>${sanitized.html}</body></html>"
}
