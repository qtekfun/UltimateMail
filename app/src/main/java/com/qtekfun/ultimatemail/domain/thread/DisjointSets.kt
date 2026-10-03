// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

/**
 * Union-find over integer nodes with path halving and union by size, so a find is amortised
 * near constant. Every step counts in [work], which tests use to prove linear behaviour.
 */
internal class DisjointSets {
    private var parent = IntArray(INITIAL_CAPACITY)
    private var size = IntArray(INITIAL_CAPACITY)
    private var count = 0

    /** Number of elementary steps taken so far. */
    var work: Long = 0
        private set

    fun add(): Int {
        if (count == parent.size) {
            parent = parent.copyOf(count * 2)
            size = size.copyOf(count * 2)
        }
        parent[count] = count
        size[count] = 1
        work++
        return count++
    }

    fun find(node: Int): Int {
        var current = node
        work++
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
            work++
        }
        return current
    }

    /** Joins two distinct roots and returns the surviving root. */
    fun link(rootA: Int, rootB: Int): Int {
        val big = if (size[rootA] >= size[rootB]) rootA else rootB
        val small = if (big == rootA) rootB else rootA
        parent[small] = big
        size[big] += size[small]
        work++
        return big
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
