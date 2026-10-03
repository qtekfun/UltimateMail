// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.domain.compose.StoreResult
import com.qtekfun.ultimatemail.domain.conversation.AttachmentFileNames
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Keeps the files attached to drafts under [root] (app-private storage that is not backed up),
 * one folder per draft, each file named after a running number and the sanitized name the user's
 * file has. A file is copied to a temporary name and moved into place, so a copy that dies half
 * way, or goes over the size allowance, leaves nothing that looks complete.
 */
class FileOutboxStorage(private val root: File) : OutboxFileStorage {
    override fun write(
        draftId: Long,
        displayName: String,
        source: InputStream,
        maxBytes: Long
    ): StoreResult {
        val folder = File(root, draftId.toString())
        val name = "${nextNumber(folder)}-${AttachmentFileNames.safe(displayName, "attachment")}"
        val target = File(folder, name)
        val partial = File(folder, "$name.part")
        return try {
            folder.mkdirs()
            val size = partial.outputStream().use { copyAtMost(source, it, maxBytes) }
            when {
                size == null -> StoreResult.TooLarge
                partial.renameTo(target) -> StoreResult.Stored(target.absolutePath, size)
                else -> StoreResult.Failed
            }
        } catch (@Suppress("SwallowedException") ignored: IOException) {
            // The message of an I/O error can name a path, which holds a file name.
            StoreResult.Failed
        } finally {
            partial.delete()
        }
    }

    override fun read(path: String): ByteArray? =
        File(path).takeIf { isInsideRoot(it) && it.isFile }?.readBytes()

    override fun exists(path: String): Boolean = File(path).let { it.isFile && isInsideRoot(it) }

    override fun delete(path: String) {
        File(path).takeIf { isInsideRoot(it) }?.delete()
    }

    override fun deleteDraft(draftId: Long) {
        File(root, draftId.toString()).deleteRecursively()
    }

    /** Copies up to [maxBytes]; returns the bytes copied, or null if there was more. */
    private fun copyAtMost(from: InputStream, to: java.io.OutputStream, maxBytes: Long): Long? {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = from.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) return null
            to.write(buffer, 0, read)
        }
    }

    private fun nextNumber(folder: File): Int = (
        folder.list().orEmpty().mapNotNull { it.substringBefore('-').toIntOrNull() }.maxOrNull()
            ?: 0
        ) +
        1

    private fun isInsideRoot(file: File) = file.canonicalFile.startsWith(root.canonicalFile)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
