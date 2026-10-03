// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.domain.conversation.AttachmentFileNames
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import java.io.File

/**
 * Keeps downloaded attachments as files under [root] (app-private storage), one folder per
 * account and message, named after the id of the attachment and the sanitized name the sender
 * chose. The bytes are written to a temporary file and moved into place, so a download that dies half way
 * never leaves a file that looks complete.
 */
class FileAttachmentStorage(private val root: File) : AttachmentStorage {
    override fun write(accountId: Long, attachment: AttachmentEntity, bytes: ByteArray): String {
        val folder = File(File(root, accountId.toString()), attachment.messageId.toString())
            .also { it.mkdirs() }
        val safeName = AttachmentFileNames.safe(attachment.fileName, "attachment")
        val name = "${attachment.id}-$safeName"
        val target = File(folder, name)
        val partial = File(folder, "$name.part")
        partial.writeBytes(bytes)
        check(partial.renameTo(target)) { "Could not store the attachment" }
        return target.absolutePath
    }

    override fun deleteAccount(accountId: Long) {
        File(root, accountId.toString()).deleteRecursively()
    }

    override fun exists(path: String): Boolean = File(path).let { it.isFile && isInsideRoot(it) }

    private fun isInsideRoot(file: File) = file.canonicalFile.startsWith(root.canonicalFile)
}
