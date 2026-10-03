// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole

enum class SectionKind { RECENT, ALL }

/** One row of the list the picker shows. [key] is unique in a list, for stable scrolling. */
sealed interface PickerListItem {
    val key: String

    data class Section(val kind: SectionKind) : PickerListItem {
        override val key get() = "section:$kind"
    }

    data class Group(val path: String, val name: String, val depth: Int) : PickerListItem {
        override val key get() = "group:$path"
    }

    /**
     * A destination. The highlight ranges are inclusive ranges of [PickerFolder.name] and
     * [PickerFolder.path]; [showPath] says to show the full path under the name (while searching,
     * where the hierarchy is not visible).
     */
    data class Entry(
        val folder: PickerFolder,
        val state: CheckState,
        val nameHighlight: List<IntRange> = emptyList(),
        val pathHighlight: List<IntRange> = emptyList(),
        val showPath: Boolean = false,
        val recent: Boolean = false
    ) : PickerListItem {
        override val key get() = (if (recent) "recent:" else "all:") + folder.path
    }
}

/**
 * The path shown under a folder's name while searching: only for the user's own folders, and only
 * when it adds something. Special folders have a localized name and a server path nobody needs.
 */
fun PickerFolder.shownPath(): String? = path.takeIf { role == FolderRole.OTHER && it != name }

/** The list for the current query. [noMatches] is true for a search that found nothing. */
data class PickerListing(val items: List<PickerListItem>, val noMatches: Boolean) {
    companion object {
        /** How many recent destinations are shown above the tree. */
        const val RECENT_LIMIT = 5

        val Empty = PickerListing(emptyList(), noMatches = false)

        /**
         * With a blank [query]: the [recents] (folder paths, most recent first) that can still be
         * picked, then the whole tree. Otherwise the folders [search] finds, best first, each
         * with the part that matched. [stateOf] gives the checkbox state of a destination.
         */
        fun build(
            candidates: PickerCandidates,
            query: String,
            recents: List<String>,
            search: FolderSearch,
            stateOf: (PickerFolder) -> CheckState
        ): PickerListing = if (query.isBlank()) {
            browse(candidates, recents, stateOf)
        } else {
            found(candidates, query, search, stateOf)
        }

        private fun browse(
            candidates: PickerCandidates,
            recents: List<String>,
            stateOf: (PickerFolder) -> CheckState
        ): PickerListing {
            val items = ArrayList<PickerListItem>()
            val recent = recents.asSequence()
                .mapNotNull { candidates.byPath(it) }
                .filter { it.enabled }
                .distinct()
                .take(RECENT_LIMIT)
                .toList()
            if (recent.isNotEmpty()) {
                items += PickerListItem.Section(SectionKind.RECENT)
                recent.forEach { items += PickerListItem.Entry(it, stateOf(it), recent = true) }
                items += PickerListItem.Section(SectionKind.ALL)
            }
            candidates.rows.forEach { row ->
                items += when (row) {
                    is PickerRow.Group -> PickerListItem.Group(row.path, row.name, row.depth)

                    is PickerRow.Destination -> PickerListItem.Entry(
                        row.folder,
                        stateOf(row.folder)
                    )
                }
            }
            return PickerListing(items, noMatches = false)
        }

        private fun found(
            candidates: PickerCandidates,
            query: String,
            search: FolderSearch,
            stateOf: (PickerFolder) -> CheckState
        ): PickerListing {
            val items = search.search(query).mapNotNull { hit ->
                candidates.byPath(hit.id)?.let { folder ->
                    PickerListItem.Entry(
                        folder = folder,
                        state = stateOf(folder),
                        nameHighlight = hit.nameRanges,
                        pathHighlight = hit.pathRanges,
                        showPath = folder.shownPath() != null
                    )
                }
            }
            return PickerListing(items, noMatches = items.isEmpty())
        }
    }
}
