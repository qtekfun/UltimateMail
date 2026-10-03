// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

/** The state of one checkbox of the label picker. */
enum class CheckState { UNCHECKED, PARTIAL, CHECKED }

/** The labels to set and to clear to get from the messages' labels to what the user chose. */
data class LabelChanges(val add: Set<String>, val remove: Set<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/**
 * The checkboxes of the label picker (Gmail). They start from the labels the selected messages
 * have: checked when all of them have a label, [CheckState.PARTIAL] (indeterminate) when only
 * some do. Tapping an unchecked or partial box checks it for every message, tapping a checked one
 * clears it from every message; a box can only come back to partial if it never changed.
 */
class LabelSelection private constructor(
    private val holders: Map<String, Int>,
    private val messageCount: Int,
    private val chosen: Map<String, Boolean>
) {
    /** The state of [label] now. */
    fun stateOf(label: String): CheckState = when (chosen[label]) {
        true -> CheckState.CHECKED
        false -> CheckState.UNCHECKED
        null -> initialState(label)
    }

    /** Selection after the user tapped [label]. */
    fun toggle(label: String): LabelSelection {
        val wanted = stateOf(label) != CheckState.CHECKED
        val next = chosen.toMutableMap()
        val initial = initialState(label)
        // Back to what the messages had at the start: nothing changed for this label. A partial
        // start is not something a tap can return to.
        if (initial != CheckState.PARTIAL && wanted == (initial == CheckState.CHECKED)) {
            next.remove(label)
        } else {
            next[label] = wanted
        }
        return LabelSelection(holders, messageCount, next)
    }

    /** The labels the user changed, ready to be turned into operations. */
    fun changes(): LabelChanges = LabelChanges(
        add = chosen.filterValues { it }.keys,
        remove = chosen.filterValues { !it }.keys
    )

    val changed: Boolean get() = chosen.isNotEmpty()

    private fun initialState(label: String): CheckState = when (holders[label] ?: 0) {
        0 -> CheckState.UNCHECKED
        messageCount -> CheckState.CHECKED
        else -> CheckState.PARTIAL
    }

    companion object {
        /** Starts from the labels of each selected message ([messageLabels], one set per message). */
        fun of(messageLabels: List<Set<String>>): LabelSelection {
            val counts = HashMap<String, Int>()
            messageLabels.forEach { labels -> labels.forEach { counts.merge(it, 1, Int::plus) } }
            return LabelSelection(counts, messageLabels.size, emptyMap())
        }
    }
}
