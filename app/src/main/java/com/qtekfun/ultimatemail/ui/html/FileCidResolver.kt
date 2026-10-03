// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.webkit.WebResourceResponse
import com.qtekfun.ultimatemail.domain.conversation.CidFile
import com.qtekfun.ultimatemail.domain.conversation.ContentIds
import java.io.File
import java.io.FileInputStream

/**
 * Serves the `cid:` images of a message from the files already downloaded for its inline parts
 * ([files], by normalized Content-ID). Only images are served, and only files that exist; any
 * other `cid:` request gets nothing and the WebView blocks it.
 */
class FileCidResolver(private val files: Map<String, CidFile>) : CidResolver {
    override fun resolve(cidUrl: String): WebResourceResponse? {
        val part = ContentIds.normalize(cidUrl)?.let { files[it] }
        val file = part?.let { File(it.path) }?.takeIf { it.isFile }
        return if (part != null && file != null && part.mimeType.startsWith("image/")) {
            WebResourceResponse(part.mimeType, null, FileInputStream(file))
        } else {
            null
        }
    }
}
