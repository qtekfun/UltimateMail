// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import java.time.Instant
import java.util.TreeMap

/** One folder of a [FakeMailbox]. */
class FakeFolder(val path: String, val role: MailFolderRole) {
    var uidValidity = 1L
    var nextUid = 1L
    val messages = TreeMap<Long, MessageHeader>()
    val bodies = mutableMapOf<Long, MessageBody>()
}

/**
 * The server side of one account, in memory: folders with UIDs and messages. The UI tests seed it
 * and the app reaches it through [FakeMailConnector], so what a test sees on screen went through
 * the real sync code. All access goes through [lock], because syncs run on background threads.
 */
class FakeMailbox(val address: String, val password: String) {
    val lock = Any()
    val folders = LinkedHashMap<String, FakeFolder>()

    /** Adds a folder (or returns the one that exists). */
    fun folder(path: String, role: MailFolderRole = MailFolderRole.OTHER): FakeFolder =
        synchronized(lock) { folders.getOrPut(path) { FakeFolder(path, role) } }

    /** Delivers a message to [path] and returns its UID. */
    fun deliver(
        path: String,
        subject: String,
        sentAt: Instant,
        from: MailAddress = MailAddress("bob@example.com", "Bob"),
        body: String = "Body of $subject",
        flags: MessageFlags = MessageFlags(),
        hasAttachments: Boolean = false
    ): Long = synchronized(lock) {
        val folder = folder(path)
        val uid = folder.nextUid++
        folder.messages[uid] = MessageHeader(
            uid = uid,
            messageId = "<$uid.${path.hashCode()}.${subject.hashCode()}@example.com>",
            subject = subject,
            from = from,
            to = listOf(MailAddress(address)),
            cc = emptyList(),
            date = sentAt,
            flags = flags,
            size = body.length.toLong(),
            hasAttachments = hasAttachments
        )
        folder.bodies[uid] = MessageBody(text = body, html = null, attachments = emptyList())
        uid
    }

    /** The subjects in [path], oldest first; what a test asserts after the app moved something. */
    fun subjectsIn(path: String): List<String> = synchronized(lock) {
        folders[path]?.messages?.values?.map { it.subject.orEmpty() }.orEmpty()
    }
}
