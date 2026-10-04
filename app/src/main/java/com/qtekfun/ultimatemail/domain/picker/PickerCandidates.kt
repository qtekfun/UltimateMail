// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.folder.FolderListItem
import com.qtekfun.ultimatemail.domain.folder.FolderTree

/**
 * The places the picker offers, taken from the folder tree of the account: the special folders
 * first, then the hierarchy. Rows that cannot be chosen (containers such as Gmail's "[Gmail]")
 * are left out, except that a container with something to choose below it stays as a [PickerRow.Group]
 * so the nesting reads.
 *
 * - [PickerMode.FOLDERS]: every selectable folder except Drafts (a message cannot be moved there
 *   sensibly) and Gmail-only virtual folders. Trash and Spam stay: moving there is legitimate.
 *   The folder every message is already in is disabled.
 * - [PickerMode.LABELS]: the Inbox (label `\Inbox`) and the user's own labels, plus Trash and
 *   Spam as [PickerFolder.moveTarget] rows (a move, with the usual hold and Undo, never a permanent
 *   delete). Gmail's other system folders are not labels a client can set, so they are not offered.
 */
class PickerCandidates private constructor(
    /** Rows in display order: special folders, then the tree. */
    val rows: List<PickerRow>,
    /** Just the destinations of [rows], in the same order. */
    val folders: List<PickerFolder>
) {
    private val byPath = folders.associateBy { it.path }
    private val byValue = folders.associateBy { it.value }

    fun byPath(path: String): PickerFolder? = byPath[path]

    /** The destination whose operation value is [value] (a folder path or a label). */
    fun byValue(value: String): PickerFolder? = byValue[value]

    companion object {
        val Empty = PickerCandidates(emptyList(), emptyList())

        /**
         * Folders of the account tree for [mode]; [messageFolders] are where the messages are.
         * Special folders are named with [roleNames] (the user's language) instead of the name
         * the server gave, so the search finds "Enviados" and not only "Sent".
         */
        fun build(
            tree: FolderTree,
            mode: PickerMode,
            messageFolders: Set<String>,
            roleNames: Map<FolderRole, String> = emptyMap()
        ): PickerCandidates {
            val only = messageFolders.singleOrNull()
            val special = tree.special.filter { allowed(it, mode) }
            val nodes = tree.nodes.filter { allowed(it, mode) || !it.selectable }
            // Special folders are listed on their own, so only the tree needs its containers shown.
            val kept = nodes.filter { it.selectable }.map { it.path }.toSet()
            val needed = kept.flatMap { tree.ancestorsOf(it) }.toSet()
            val rows = ArrayList<PickerRow>()
            special.forEach { rows += destination(it, mode, only, depth = 0, roleNames) }
            nodes.forEach { node ->
                when {
                    node.selectable -> rows += destination(node, mode, only, node.depth, roleNames)
                    node.path in needed -> rows += PickerRow.Group(node.path, node.name, node.depth)
                }
            }
            return PickerCandidates(
                rows,
                rows.filterIsInstance<PickerRow.Destination>().map {
                    it.folder
                }
            )
        }

        /**
         * The label a message is already known by through the folder it is listed in: the Inbox
         * label for the Inbox, the folder itself for a user label. Gmail's own list of labels can
         * leave this one out, and the picker must still show it as set.
         */
        fun implicitLabel(tree: FolderTree, folderPath: String): String? = when {
            folderPath == tree.inboxPath -> GmailLabels.INBOX
            tree.nodes.any { it.path == folderPath && it.isLabel && it.selectable } -> folderPath
            else -> null
        }

        private fun allowed(item: FolderListItem, mode: PickerMode): Boolean = when (mode) {
            PickerMode.FOLDERS -> item.selectable && item.role !in FOLDERS_EXCLUDED

            PickerMode.LABELS -> item.selectable && when (item.role) {
                FolderRole.INBOX -> true
                FolderRole.OTHER -> GmailLabels.systemPrefixes.none { item.path.startsWith(it) }
                else -> item.role in MOVE_TARGETS
            }
        }

        private fun destination(
            item: FolderListItem,
            mode: PickerMode,
            only: String?,
            depth: Int,
            roleNames: Map<FolderRole, String>
        ): PickerRow.Destination {
            val label = mode == PickerMode.LABELS
            val moveTarget = label && item.role in MOVE_TARGETS
            return PickerRow.Destination(
                PickerFolder(
                    path = item.path,
                    name = roleNames[item.role] ?: item.name,
                    role = item.role,
                    isLabel = item.isLabel,
                    depth = depth,
                    value = if (label &&
                        item.role == FolderRole.INBOX
                    ) {
                        GmailLabels.INBOX
                    } else {
                        item.path
                    },
                    enabled = (label && !moveTarget) || item.path != only,
                    moveTarget = moveTarget
                )
            )
        }

        /** Folders the label picker offers as places to move to, never as labels. */
        private val MOVE_TARGETS = setOf(FolderRole.TRASH, FolderRole.JUNK)

        private val FOLDERS_EXCLUDED =
            setOf(FolderRole.DRAFTS, FolderRole.ALL_MAIL, FolderRole.STARRED)
    }
}
