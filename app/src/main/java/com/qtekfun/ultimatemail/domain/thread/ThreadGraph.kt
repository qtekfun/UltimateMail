// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

/**
 * The linking state behind [ThreadResolver]: one node per message and one per Message-ID seen in
 * a header (so a referenced message that never arrived still ties its replies together), joined
 * with a union-find, so cycles and garbage cannot loop. Each connected set is a thread.
 *
 * A set may carry an authority (Gmail X-GM-THRID or an IMAP THREAD id). Its thread id is the
 * authority itself and two different authorities are never joined: the server knows better than
 * the headers.
 */
internal class ThreadGraph {
    private class Component(
        var authority: String?,
        var threadId: String?,
        var oldestAt: Long,
        var oldestKey: String
    )

    val sets = DisjointSets()
    private val components = ArrayList<Component>()
    private val containers = HashMap<String, Int>()
    private val authorityReps = HashMap<String, Int>()
    private val liveIds = HashSet<String>()
    private val createdInBatch = HashSet<String>()
    private val renames = HashMap<String, String>()

    /** Starts a batch: from here on created and merged thread ids are tracked. */
    fun beginBatch() {
        createdInBatch.clear()
        renames.clear()
    }

    /** Adds a message node; [authority] is its thread id when the server dictates one. */
    fun addMessage(key: String, sentAtMillis: Long, authority: String?): Int {
        val node = sets.add()
        val id = authority ?: "$LOCAL_PREFIX$key"
        components.add(Component(authority, id, sentAtMillis, key))
        if (liveIds.add(id)) createdInBatch.add(id)
        if (authority != null) {
            val rep = authorityReps.getOrPut(authority) { node }
            if (rep != node) union(node, rep)
        }
        return node
    }

    /** The node standing for a Message-ID, created empty on first sight. */
    fun container(scopedId: String): Int = containers.getOrPut(scopedId) {
        val node = sets.add()
        components.add(Component(null, null, Long.MAX_VALUE, ""))
        node
    }

    fun threadIdOf(node: Int): String = checkNotNull(components[sets.find(node)].threadId)

    /** Joins the sets of two nodes unless that would mix two authorities. */
    fun union(a: Int, b: Int) {
        val rootA = sets.find(a)
        val rootB = sets.find(b)
        if (rootA == rootB) return
        val compA = components[rootA]
        val compB = components[rootB]
        val authorityA = compA.authority
        val authorityB = compB.authority
        if (authorityA != null && authorityB != null && authorityA != authorityB) return
        val survivor = survivingId(compA, compB)
        val older = if (isOlder(compA, compB)) compA else compB
        val root = sets.link(rootA, rootB)
        components[root] = Component(
            authorityA ?: authorityB,
            survivor,
            older.oldestAt,
            older.oldestKey
        )
    }

    /**
     * Thread ids that existed before the batch and were absorbed by another, each mapped to the
     * id that finally holds its messages.
     */
    fun mergedIds(): Map<String, String> {
        val merged = HashMap<String, String>()
        for ((lost, first) in renames) {
            if (lost in createdInBatch) continue
            var target = first
            while (true) target = renames[target] ?: break
            merged[lost] = target
        }
        return merged
    }

    private fun isOlder(a: Component, b: Component): Boolean =
        a.oldestAt < b.oldestAt || (a.oldestAt == b.oldestAt && a.oldestKey <= b.oldestKey)

    /**
     * The id both sets will share: the authority's, else one handed out in an earlier batch,
     * else the older set's. Records the loser.
     */
    private fun survivingId(a: Component, b: Component): String? {
        val idA = a.threadId
        val idB = b.threadId
        if (idA == null || idB == null || idA == idB) return idA ?: idB
        val keepA = when {
            a.authority != null -> true

            b.authority != null -> false

            // An id already handed out stays; one created in this batch is still free to change.
            (idA in createdInBatch) != (idB in createdInBatch) -> idB in createdInBatch

            else -> isOlder(a, b)
        }
        val kept = if (keepA) idA else idB
        val lost = if (keepA) idB else idA
        liveIds.remove(lost)
        renames[lost] = kept
        return kept
    }

    private companion object {
        const val LOCAL_PREFIX = "local:"
    }
}
