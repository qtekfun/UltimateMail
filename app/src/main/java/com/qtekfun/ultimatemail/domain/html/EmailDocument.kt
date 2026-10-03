// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** Builds the page a message is shown in. */
object EmailDocument {
    /**
     * The viewport is the screen width, so a normal message wraps to it. Wide fixed layouts are
     * shrunk by their own `max-width`, words that cannot break are broken, and images and tables
     * never outgrow the page (tables are limited in viewport units, since a percentage of a table
     * nested in a cell is circular and ignored when the cell is sized). A blocked remote image
     * (its address parked in `data-blocked-src`) becomes a quiet bordered box with its alt text
     * instead of the browser's broken-image icon; a blocked tracking pixel takes no room at all.
     */
    private const val BASE_STYLE =
        "html{-webkit-text-size-adjust:100%}" +
            "body{margin:0;padding:8px 12px;overflow-wrap:anywhere;word-break:normal}" +
            "img{max-width:100%;height:auto}" +
            "table{max-width:calc(100vw - 24px)}td,th{overflow-wrap:anywhere}" +
            "pre{white-space:pre-wrap}" +
            "img[data-blocked-src]{display:inline-block;box-sizing:border-box;min-height:24px;" +
            "padding:2px 6px;border:1px dashed rgba(128,128,128,.6);border-radius:4px;" +
            "background:rgba(128,128,128,.12);color:gray;font:12px sans-serif;" +
            "overflow:hidden;text-align:center}" +
            "img[data-blocked-src][width=\"1\"],img[data-blocked-src][height=\"1\"]" +
            "{display:none}"

    /**
     * Wraps the sanitized fragment in a complete page. The CSP comes first, before any content,
     * so nothing in the message can run ahead of it.
     */
    fun wrap(
        sanitized: SanitizedHtml,
        allowRemoteContent: Boolean,
        colors: MailColorMode = MailColorMode.ORIGINAL
    ): String = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" " +
        "content=\"${ContentSecurityPolicy.build(allowRemoteContent)}\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<meta name=\"color-scheme\" content=\"${colors.colorScheme}\">" +
        "<style>$BASE_STYLE</style></head><body>${sanitized.html}</body></html>"
}
