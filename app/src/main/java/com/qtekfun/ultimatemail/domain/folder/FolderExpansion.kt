// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

/**
 * Which parents of the folder tree are open, across accounts (paths are only unique within an
 * account). Immutable; it round-trips through [toSaved] so it survives process death.
 */
class FolderExpansion private constructor(private val open: Set<String>) {
    constructor() : this(emptySet())

    /** The open parents of [accountId], as the paths [FolderTree.visible] takes. */
    fun pathsOf(accountId: Long): Set<String> {
        val prefix = prefixOf(accountId)
        return open.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    /** Opens a closed parent and closes an open one. */
    fun toggle(accountId: Long, path: String): FolderExpansion {
        val key = prefixOf(accountId) + path
        return FolderExpansion(if (key in open) open - key else open + key)
    }

    /** Opens every one of [paths], e.g. the ancestors of the folder being shown. */
    fun reveal(accountId: Long, paths: Collection<String>): FolderExpansion =
        FolderExpansion(open + paths.map { prefixOf(accountId) + it })

    fun toSaved(): ArrayList<String> = ArrayList(open.sorted())

    companion object {
        fun fromSaved(saved: List<String>?) = FolderExpansion(saved.orEmpty().toSet())

        private fun prefixOf(accountId: Long) = "$accountId:"
    }
}
