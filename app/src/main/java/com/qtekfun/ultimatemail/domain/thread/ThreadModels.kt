// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import java.time.Instant

/** Server identity of a message: unique per account, folder and UID. */
data class MessageRef(val accountId: Long, val folderPath: String, val uid: Long) {
    /** Stable text form, also the deterministic tie-breaker when two messages are equally old. */
    internal val key: String get() = "$accountId/$folderPath/$uid"
}

/**
 * What the threading algorithm needs to know about a message; no bodies, no addresses.
 *
 * [messageId], [inReplyTo] and [references] are the raw header values (with or without angle
 * brackets). [gmailThreadId] is X-GM-THRID and [serverThreadId] the id IMAP THREAD gave the
 * message inside its folder.
 */
data class ThreadMessage(
    val ref: MessageRef,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val subject: String?,
    val sentAt: Instant,
    val gmailThreadId: String? = null,
    val serverThreadId: String? = null
)

/** Tunables of the fallback subject rule. */
data class ThreadConfig(
    /** Longest gap between two messages that may be joined only because of their subject. */
    val subjectWindowMillis: Long = DEFAULT_SUBJECT_WINDOW_MILLIS
) {
    init {
        require(subjectWindowMillis >= 0) { "subjectWindowMillis must not be negative" }
    }

    companion object {
        const val DEFAULT_SUBJECT_WINDOW_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Result of adding messages. [assignments] holds the final thread id of every message that was
 * in the batch (duplicates of already known messages included). [merged] maps a thread id that
 * existed before the batch and no longer exists to the id that absorbed it: the caller rewrites
 * those rows in Room.
 */
data class ThreadUpdate(val assignments: Map<MessageRef, String>, val merged: Map<String, String>)
