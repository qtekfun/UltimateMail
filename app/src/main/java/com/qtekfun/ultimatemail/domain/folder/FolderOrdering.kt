// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole

/** One row of the folder list: a folder with its nesting [depth] and unread count. */
data class FolderListItem(
    val path: String,
    val name: String,
    val role: FolderRole,
    val isLabel: Boolean,
    /** 0 for top-level folders and for the special folders shown first. */
    val depth: Int,
    val unread: Int
)

/** Orders folders for display (RF-02): special folders first, then the rest as a tree. */
object FolderOrdering {
    /** The order of the special folders at the top of the list. */
    private val specialOrder = listOf(
        FolderRole.INBOX,
        FolderRole.DRAFTS,
        FolderRole.SENT,
        FolderRole.ARCHIVE,
        FolderRole.TRASH,
        FolderRole.JUNK
    )

    /**
     * Special folders in [specialOrder], then everything else sorted so that every folder is
     * followed by its children. A role is shown once: if the server announces two folders with
     * the same role, the second one goes to the tree with the others.
     */
    fun order(folders: List<FolderEntity>, unread: Map<String, Int>): List<FolderListItem> {
        val special = specialOrder.mapNotNull { role -> folders.firstOrNull { it.role == role } }
        val specialPaths = special.map { it.path }.toSet()
        val rest = folders
            .filter { it.path !in specialPaths }
            .sortedWith { a, b -> compareSegments(segmentsOf(a), segmentsOf(b)) }
        return special.map { it.toItem(depth = 0, unread) } +
            rest.map { it.toItem(depth = depthOf(it), unread) }
    }

    /**
     * Number of ancestors in the path. The delimiter is not stored, but it is the character
     * just before the last segment, which [FolderEntity.name] gives us.
     */
    internal fun depthOf(folder: FolderEntity): Int {
        val parent = parentPath(folder) ?: return 0
        val delimiter = folder.path[parent.length]
        return parent.count { it == delimiter } + 1
    }

    private fun parentPath(folder: FolderEntity): String? {
        val path = folder.path
        val name = folder.name
        return if (name.isNotEmpty() && path.length > name.length + 1 && path.endsWith(name)) {
            path.dropLast(name.length + 1).takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    /** The path split into its segments, e.g. ["Work", "Invoices"]. */
    private fun segmentsOf(folder: FolderEntity): List<String> {
        val parent = parentPath(folder) ?: return listOf(folder.path)
        val delimiter = folder.path[parent.length]
        return parent.split(delimiter) + folder.name
    }

    /** Segment by segment, ignoring case; a parent comes before its children. */
    private fun compareSegments(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val order = String.CASE_INSENSITIVE_ORDER.compare(a[i], b[i])
            if (order != 0) return order
        }
        return a.size.compareTo(b.size)
    }

    private fun FolderEntity.toItem(depth: Int, unread: Map<String, Int>) = FolderListItem(
        path = path,
        name = name,
        role = role,
        isLabel = isLabel,
        depth = depth,
        unread = unread[path] ?: 0
    )
}
