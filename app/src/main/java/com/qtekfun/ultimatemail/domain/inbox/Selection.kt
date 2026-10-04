// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/**
 * The conversations picked in a list (multi-selection, RF-03). Selection mode is on while at
 * least one is picked, or after "Edit" ([editing]) until "Done"; picking the last one off leaves a
 * selection that began with a long-press. It belongs to one list ([scopeKey]):
 * another list starts empty. [keys] are [ConversationItem.key]s, so a pick survives new mail
 * arriving in the conversation.
 */
data class Selection(
    val scopeKey: String? = null,
    val keys: Set<String> = emptySet(),
    /** Started with "Edit": stays on with nothing picked, until [clear]. */
    val editing: Boolean = false
) {
    val active: Boolean get() = editing || keys.isNotEmpty()

    val count: Int get() = keys.size

    operator fun contains(key: String) = key in keys

    /** Picks [key], or takes it off; this is how a long-press starts selection mode too. */
    fun toggle(key: String): Selection = copy(keys = if (key in keys) keys - key else keys + key)

    /** Selects every one of [visible]. */
    fun selectAll(visible: Collection<String>): Selection = copy(keys = visible.toSet())

    /** "Edit": selection mode with nothing picked yet. */
    fun startEditing(): Selection = copy(editing = true)

    /** Leaves selection mode ("Done", or after an action). */
    fun clear(): Selection = copy(keys = emptySet(), editing = false)

    /** The same selection for the list [scope]; empty when that is another list. */
    fun forScope(scope: String?): Selection = if (scope == scopeKey) this else Selection(scope)

    /** Drops what is no longer in the list ([visible]); the same instance when nothing is. */
    fun retain(visible: Set<String>): Selection = if (visible.containsAll(keys)) {
        this
    } else {
        copy(keys = keys.filterTo(linkedSetOf()) { it in visible })
    }

    /** The picked items of [items], in list order. */
    fun pick(items: List<ConversationItem>): List<ConversationItem> =
        items.filter { it.key in keys }
}
