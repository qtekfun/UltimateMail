// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole

/** One row of the folder menu: a folder with its nesting [depth] and unread count. */
data class FolderListItem(
    val path: String,
    val name: String,
    val role: FolderRole,
    val isLabel: Boolean,
    /** 0 for top-level folders and for the special folders shown first. */
    val depth: Int,
    val unread: Int,
    /** Path of the row this one is nested under in the tree; null for top-level rows. */
    val parent: String? = null,
    /** False for containers such as Gmail's "[Gmail]": they only expand and collapse. */
    val selectable: Boolean = true,
    /** True when other rows of the tree are nested under this one. */
    val hasChildren: Boolean = false
)

/**
 * The folders of an account ready for the menu (RF-02): the [special] folders first in a fixed
 * order, then [nodes], the rest as a tree in depth-first order (every folder is followed by its
 * children). A missing parent in the IMAP hierarchy is filled in with a container row so the
 * tree is always connected. Use [visible] to flatten it for a given expand/collapse state.
 */
class FolderTree private constructor(
    val special: List<FolderListItem>,
    val nodes: List<FolderListItem>
) {
    private val byPath = nodes.associateBy { it.path }

    /** Path of the Inbox, or null when the account has no folder with that role yet. */
    val inboxPath: String? = special.firstOrNull { it.role == FolderRole.INBOX }?.path

    /** The rows of the tree to show when the parents in [expanded] are open and the rest closed. */
    fun visible(expanded: Set<String>): List<FolderListItem> {
        val shown = ArrayList<FolderListItem>(nodes.size)
        var hiddenBelow: Int? = null
        for (node in nodes) {
            val limit = hiddenBelow
            if (limit != null && node.depth > limit) continue
            hiddenBelow = null
            shown += node
            if (node.hasChildren && node.path !in expanded) hiddenBelow = node.depth
        }
        return shown
    }

    /** The paths of the rows above [path] in the tree, nearest first; empty for the others. */
    fun ancestorsOf(path: String): List<String> {
        val chain = ArrayList<String>()
        var parent = byPath[path]?.parent
        while (parent != null) {
            chain += parent
            parent = byPath[parent]?.parent
        }
        return chain
    }

    companion object {
        val Empty = FolderTree(emptyList(), emptyList())

        /**
         * The order of the special folders at the top of the menu. Archive and All mail are both
         * listed: a Gmail account only has the second, most others only the first.
         */
        private val specialOrder = listOf(
            FolderRole.INBOX,
            FolderRole.DRAFTS,
            FolderRole.SENT,
            FolderRole.ARCHIVE,
            FolderRole.ALL_MAIL,
            FolderRole.STARRED,
            FolderRole.TRASH,
            FolderRole.JUNK
        )

        /**
         * Special folders in [specialOrder], then everything else as a tree. A role is shown
         * once: if the server announces two folders with the same role, the second one goes to
         * the tree with the others. [unread] counts by folder path.
         */
        fun build(folders: List<FolderEntity>, unread: Map<String, Int>): FolderTree {
            val special = specialOrder.mapNotNull { role ->
                folders.firstOrNull { it.role == role }
            }
            val specialPaths = special.map { it.path }.toSet()
            val rest = folders.filter { it.path !in specialPaths }
            val drafts = draftsFor(rest, specialPaths)
            val children = drafts.values.groupBy { it.parent }
            val pruned = prunedContainers(drafts.values, folders, children)

            val nodes = ArrayList<FolderListItem>(drafts.size)
            fun walk(parent: String?, depth: Int) {
                children[parent].orEmpty()
                    .filter { it.path !in pruned }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .forEach { draft ->
                        val hasChildren = children[draft.path].orEmpty().any { it.path !in pruned }
                        nodes += draft.toItem(depth, hasChildren, unread)
                        walk(draft.path, depth + 1)
                    }
            }
            walk(null, 0)
            return FolderTree(special.map { it.toItem(unread) }, nodes)
        }

        private class Draft(
            val path: String,
            val name: String,
            val parent: String?,
            val entity: FolderEntity?
        )

        private fun draftsFor(
            rest: List<FolderEntity>,
            specialPaths: Set<String>
        ): Map<String, Draft> {
            val listed = rest.map { it.path }.toSet()
            // Ancestors the server did not list become containers, unless a special folder is
            // already shown at that path (a child of the Inbox is then a top-level row).
            val missing = LinkedHashMap<String, Pair<String, List<String>>>()
            rest.forEach { folder ->
                val chain = ancestors(folder)
                chain.forEachIndexed { index, (path, name) ->
                    if (path !in listed && path !in specialPaths) {
                        missing.putIfAbsent(path, name to chain.take(index).map { it.first })
                    }
                }
            }
            val shown = listed + missing.keys
            val drafts = LinkedHashMap<String, Draft>()
            rest.forEach { folder ->
                val parent = ancestors(folder).map { it.first }.lastOrNull { it in shown }
                drafts[folder.path] = Draft(folder.path, folder.name, parent, folder)
            }
            missing.forEach { (path, nameAndChain) ->
                val (name, chain) = nameAndChain
                drafts[path] = Draft(path, name, chain.lastOrNull { it in shown }, null)
            }
            return drafts
        }

        /** Containers that lost all their children to the special folders have nothing to show. */
        private fun prunedContainers(
            drafts: Collection<Draft>,
            all: List<FolderEntity>,
            children: Map<String?, List<Draft>>
        ): Set<String> {
            val hadChildren = all.flatMap { folder -> ancestors(folder).map { it.first } }.toSet()
            return drafts.filter { draft ->
                val folder = draft.entity
                folder != null && folder.role == FolderRole.OTHER && !folder.syncEnabled &&
                    draft.path in hadChildren && children[draft.path].isNullOrEmpty()
            }.map { it.path }.toSet()
        }

        /**
         * The folders above [folder] from the root down, as (path, name). The delimiter is not
         * stored, but it is the character just before the last segment, which
         * [FolderEntity.name] gives us.
         */
        private fun ancestors(folder: FolderEntity): List<Pair<String, String>> {
            val path = folder.path
            val name = folder.name
            val matches = name.isNotEmpty() && path.length > name.length + 1 && path.endsWith(name)
            val parent = if (matches) path.dropLast(name.length + 1) else ""
            if (parent.isEmpty()) return emptyList()
            val delimiter = path[parent.length]
            var prefix = ""
            return parent.split(delimiter).map { segment ->
                prefix = if (prefix.isEmpty()) segment else prefix + delimiter + segment
                prefix to segment
            }
        }

        private fun FolderEntity.toItem(unread: Map<String, Int>) = FolderListItem(
            path = path,
            name = name,
            role = role,
            isLabel = isLabel,
            depth = 0,
            unread = unread[path] ?: 0
        )

        private fun Draft.toItem(
            depth: Int,
            hasChildren: Boolean,
            unread: Map<String, Int>
        ): FolderListItem {
            val folder = entity
            return FolderListItem(
                path = path,
                name = name,
                role = folder?.role ?: FolderRole.OTHER,
                isLabel = folder?.isLabel ?: false,
                depth = depth,
                unread = unread[path] ?: 0,
                parent = parent,
                // Not synced and with children: a container, there is nothing to open.
                selectable = folder != null &&
                    !(folder.role == FolderRole.OTHER && !folder.syncEnabled && hasChildren),
                hasChildren = hasChildren
            )
        }
    }
}
