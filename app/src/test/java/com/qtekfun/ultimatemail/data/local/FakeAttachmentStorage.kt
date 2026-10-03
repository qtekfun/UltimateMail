// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import com.qtekfun.ultimatemail.sync.engine.StoredAttachment

/** Attachment storage in memory: the "files" are map entries, paths look like real ones. */
class FakeAttachmentStorage : AttachmentStorage {
    val files = mutableMapOf<String, ByteArray>()
    val deletedAccounts = mutableListOf<Long>()

    /** When set, [write] fails like a full disk. */
    var failWrites = false

    override fun write(accountId: Long, attachment: AttachmentEntity, bytes: ByteArray): String {
        check(!failWrites) { "disk full" }
        val path = "/files/$accountId/${attachment.messageId}/${attachment.id}"
        files[path] = bytes
        return path
    }

    override fun deleteAccount(accountId: Long) {
        deletedAccounts += accountId
        files.keys.removeAll { it.startsWith("/files/$accountId/") }
    }

    override fun exists(path: String) = path in files

    override fun stored(accountId: Long) = files.keys.filter { it.startsWith("/files/$accountId/") }
        .map { StoredAttachment(it.substringAfterLast('/').toLong(), it) }

    override fun delete(path: String) {
        files.remove(path)
    }
}
