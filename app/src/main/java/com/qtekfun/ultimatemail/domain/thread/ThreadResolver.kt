// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import java.util.TreeSet

/**
 * Groups messages into conversations (SPEC RF-03). Pure and incremental: feed it batches with
 * [add] and it keeps the thread ids it already handed out, reporting merges.
 *
 * Rules, in priority order:
 *  1. Gmail X-GM-THRID: the thread is the Gmail id.
 *  2. IMAP THREAD: the thread is the server's id for that folder.
 *  3. References / In-Reply-To linking over Message-IDs, also through messages never received.
 *  4. Subject: a message without any reference whose subject carried a Re:/Fwd: prefix joins the
 *     closest earlier message with the same normalised subject, within
 *     [ThreadConfig.subjectWindowMillis]. Messages with references or a server-given thread
 *     never use this rule, so the subject cannot override what Message-IDs or the server say.
 *
 * Messages are scoped by account; nothing is ever joined across accounts. Adding a message that
 * is already known (same [MessageRef]) changes nothing. Not thread safe.
 */
class ThreadResolver(private val config: ThreadConfig = ThreadConfig()) {
    private class Known(
        val node: Int,
        val sentAtMillis: Long,
        val key: String,
        val subjectScope: String?,
        val joinsBySubject: Boolean
    ) {
        var linkedBySubject = false
    }

    private val graph = ThreadGraph()
    private val known = HashMap<MessageRef, Known>()
    private val subjectIndex = HashMap<String, TreeSet<Known>>()
    private var comparisons = 0L

    private val byAge = Comparator<Known> { a, b ->
        comparisons++
        val byTime = a.sentAtMillis.compareTo(b.sentAtMillis)
        if (byTime != 0) byTime else a.key.compareTo(b.key)
    }

    /** Elementary steps taken so far; grows linearly with the messages added. */
    internal val work: Long get() = graph.sets.work + comparisons

    /** Adds [messages] and returns the thread of each plus the threads that were merged. */
    fun add(messages: List<ThreadMessage>): ThreadUpdate {
        graph.beginBatch()
        val fresh = messages
            .filter { it.ref !in known }
            .distinctBy { it.ref }
            .sortedWith(compareBy({ it.sentAt }, { it.ref.key }))
        val added = fresh.map { register(it) }
        added.forEach { linkBySubject(it) }
        val assignments = messages.associate {
            it.ref to graph.threadIdOf(known.getValue(it.ref).node)
        }
        return ThreadUpdate(assignments, graph.mergedIds())
    }

    private fun register(message: ThreadMessage): Known {
        val ref = message.ref
        val sentAt = message.sentAt.toEpochMilli()
        val authority = authorityOf(message)
        val node = graph.addMessage(ref.key, sentAt, authority)
        val ownId = ThreadKeys.messageId(message.messageId)
        val linked = (listOfNotNull(ownId) + referencedIds(message, ownId)).map {
            graph.container("${ref.accountId}|$it")
        }
        linked.forEach { graph.union(node, it) }
        val subject = ThreadKeys.subject(message.subject)
        val scope = "${ref.accountId}|${subject.text}"
        val indexed = authority == null && subject.text.isNotEmpty()
        val hasReferences = linked.size > (if (ownId == null) 0 else 1)
        val entry = Known(
            node = node,
            sentAtMillis = sentAt,
            key = ref.key,
            subjectScope = if (indexed) scope else null,
            joinsBySubject = indexed && subject.hadPrefix && !hasReferences
        )
        if (indexed) subjectIndex.getOrPut(scope) { TreeSet(byAge) }.add(entry)
        known[ref] = entry
        return entry
    }

    private fun authorityOf(message: ThreadMessage): String? {
        val account = message.ref.accountId
        val gmail = message.gmailThreadId?.takeIf { it.isNotBlank() }
        val server = message.serverThreadId?.takeIf { it.isNotBlank() }
        return when {
            gmail != null -> "gmail:$account:$gmail"
            server != null -> "thread:$account:${message.ref.folderPath}:$server"
            else -> null
        }
    }

    /** Valid Message-IDs this message points at, other than its own, without repeats. */
    private fun referencedIds(message: ThreadMessage, ownId: String?): Set<String> {
        val ids = LinkedHashSet<String>()
        (message.references + listOf(message.inReplyTo)).forEach { raw ->
            ThreadKeys.messageId(raw)?.takeIf { it != ownId }?.let(ids::add)
        }
        return ids
    }

    private fun linkBySubject(entry: Known) {
        val index = subjectIndex.getValue(entry.subjectScope ?: return)
        if (entry.joinsBySubject) {
            index.lower(entry)?.let { join(entry, it) }
        }
        index.higher(entry)?.let { next ->
            if (next.joinsBySubject && !next.linkedBySubject) join(next, entry)
        }
    }

    /** Links [later] to [earlier] when they are close enough in time. */
    private fun join(later: Known, earlier: Known) {
        if (later.sentAtMillis - earlier.sentAtMillis > config.subjectWindowMillis) return
        later.linkedBySubject = true
        graph.union(later.node, earlier.node)
    }
}
