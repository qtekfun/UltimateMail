// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import java.nio.charset.Charset
import java.util.Locale

/** Walks the MIME tree of a message: text and HTML bodies, and the attachment parts. */
internal object MimeParts {
    private const val TEXT_PLAIN = "text/plain"
    private const val TEXT_HTML = "text/html"

    class Leaf(val id: String, val part: Part, val mimeType: String, val isAttachment: Boolean) {
        val inline: Boolean
            get() = isAttachment && part.disposition.equals(Part.INLINE, ignoreCase = true)
    }

    /** The leaves in order; ids are dot-separated 1-based positions, as in IMAP sections. */
    fun leaves(root: Part): List<Leaf> {
        val out = mutableListOf<Leaf>()
        walk(root, "", out)
        return out
    }

    private fun walk(part: Part, id: String, out: MutableList<Leaf>) {
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (index in 0 until multipart.count) {
                val childId = if (id.isEmpty()) "${index + 1}" else "$id.${index + 1}"
                walk(multipart.getBodyPart(index), childId, out)
            }
            return
        }
        val mimeType = ContentType(part.contentType).baseType.lowercase(Locale.ROOT)
        val disposition = part.disposition?.lowercase(Locale.ROOT)
        val isText = mimeType == TEXT_PLAIN || mimeType == TEXT_HTML
        val isAttachment = !isText ||
            disposition == Part.ATTACHMENT ||
            (disposition == null && part.fileName != null)
        out += Leaf(id.ifEmpty { "1" }, part, mimeType, isAttachment)
    }

    fun hasAttachments(root: Part): Boolean = leaves(root).any { it.isAttachment && !it.inline }

    fun body(root: Part): MessageBody {
        val leaves = leaves(root)
        val text = leaves.firstOrNull { !it.isAttachment && it.mimeType == TEXT_PLAIN }
        val html = leaves.firstOrNull { !it.isAttachment && it.mimeType == TEXT_HTML }
        return MessageBody(
            text = text?.let { readText(it.part) },
            html = html?.let { readText(it.part) },
            attachments = leaves.filter { it.isAttachment }.map { it.toInfo() }
        )
    }

    private fun Leaf.toInfo() = AttachmentInfo(
        partId = id,
        fileName = part.fileName,
        mimeType = mimeType,
        size = part.size.toLong().coerceAtLeast(0),
        contentId = (part as? jakarta.mail.internet.MimePart)?.contentID,
        inline = inline
    )

    /** Reads the decoded stream and applies the part's charset, without the activation handlers. */
    private fun readText(part: Part): String {
        val charsetName = ContentType(part.contentType).getParameter("charset")
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return part.inputStream.use { String(it.readBytes(), charset) }
    }

    fun readBytes(part: Part): ByteArray = part.inputStream.use { it.readBytes() }
}
