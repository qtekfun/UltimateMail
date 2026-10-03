// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/** One coloured chip: the [text] to show, and a [colorIndex] into the UI's label palette. */
data class LabelChipModel(val label: String, val text: String, val colorIndex: Int)

/** The chips to draw and how many more labels did not fit ([overflow]). */
data class LabelSummary(val chips: List<LabelChipModel>, val overflow: Int) {
    val isEmpty: Boolean get() = chips.isEmpty()

    companion object {
        val EMPTY = LabelSummary(emptyList(), 0)
    }
}

/** Turns the raw Gmail labels of a message into the chips of RF-03. */
object LabelPresentation {
    /** Number of colour slots of the label palette. */
    const val PALETTE_SIZE = 8

    /** Chips shown on a list row before the rest collapses into "+N". */
    const val MAX_CHIPS = 3

    /**
     * The chips for [labels]: system labels (backslash names such as Inbox or Sent, and
     * dollar-sign keywords such as Forwarded) and the folder being shown ([hidden]) are left
     * out, duplicates collapse, a nested label shows its last part ("Work/Invoices" is
     * "Invoices") and the colour depends only on the full label.
     */
    fun summarize(
        labels: List<String>,
        hidden: Set<String> = emptySet(),
        maxChips: Int = MAX_CHIPS
    ): LabelSummary {
        val visible = labels.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isSystem(it) && it !in hidden }
            .distinct()
            .toList()
        if (visible.isEmpty()) return LabelSummary.EMPTY
        val chips = visible.take(maxChips).map { label ->
            LabelChipModel(
                label = label,
                text = label.substringAfterLast('/').ifBlank { label },
                colorIndex = AvatarSpec.colorIndexOf(label, PALETTE_SIZE)
            )
        }
        return LabelSummary(chips, visible.size - chips.size)
    }

    private fun isSystem(label: String) = label.startsWith('\\') || label.startsWith('$')
}
