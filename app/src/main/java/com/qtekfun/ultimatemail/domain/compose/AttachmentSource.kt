// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import java.io.InputStream

/** What the system says about a file the user picked. Any part may be unknown. */
data class SourceInfo(val name: String?, val mimeType: String?, val size: Long?) {
    override fun toString(): String = "SourceInfo(size=$size)"
}

/**
 * Reads the files the user picks to attach (camera, gallery, file picker), by the `content:` URI
 * the picker returned. It wraps Android's ContentResolver (`data.compose.ContentResolverSource`)
 * so that the domain never touches `android.*`; tests use an in-memory one.
 */
interface AttachmentSource {
    /** Name, type and size of [uri], or null if it cannot be read (revoked grant, deleted). */
    fun describe(uri: String): SourceInfo?

    /** A stream of the content, or null if it cannot be opened. The caller closes it. */
    fun open(uri: String): InputStream?
}
