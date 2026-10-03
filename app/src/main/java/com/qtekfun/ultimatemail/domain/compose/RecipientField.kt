// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress

/**
 * One recipient shown as a chip. [address] is null for text that is not an address: the chip is
 * then highlighted as invalid and sending is refused until it is removed or fixed.
 */
data class RecipientChip(val text: String, val address: MailAddress?) {
    val valid: Boolean get() = address != null

    override fun toString(): String = "RecipientChip(valid=$valid)"
}

/** A To, Cc or Bcc field: the chips already made and the text being typed after them. */
data class RecipientFieldState(
    val chips: List<RecipientChip> = emptyList(),
    val input: String = ""
) {
    /** The valid addresses, in order, without repeats. */
    val addresses: List<MailAddress> get() = chips.mapNotNull { it.address }

    val hasInvalid: Boolean get() = chips.any { !it.valid }

    override fun toString(): String = "RecipientFieldState(chips=${chips.size})"

    companion object {
        fun of(addresses: List<MailAddress>) =
            RecipientFieldState(addresses.map { RecipientChip(RecipientParser.format(it), it) })
    }
}

/**
 * The rules of a recipient field (RF-07): typed or pasted text becomes chips when the user types
 * a separator (comma, semicolon, line break, or a space after a complete address) or leaves the
 * field. Text that is not an address still becomes a chip, marked invalid, so that the user
 * sees what is wrong instead of losing it. The same address twice is kept once.
 */
object RecipientFields {
    private val SEPARATORS = charArrayOf(',', ';', '\n')

    /** The user changed the text of the field to [text]; completed entries become chips. */
    fun typed(field: RecipientFieldState, text: String): RecipientFieldState {
        val lastSeparator = text.indexOfLast { it in SEPARATORS }
        if (lastSeparator >= 0) {
            val done = chips(field.chips, text.substring(0, lastSeparator))
            return RecipientFieldState(done, text.substring(lastSeparator + 1).trimStart())
        }
        val trimmed = text.trim()
        val spaceAfterAddress = text.endsWith(' ') && trimmed.contains('@') &&
            RecipientParser.parse(trimmed) != null
        return if (spaceAfterAddress) {
            RecipientFieldState(chips(field.chips, trimmed), "")
        } else {
            field.copy(input = text)
        }
    }

    /** Turns what is typed into chips (the user left the field or pressed Done). */
    fun commit(field: RecipientFieldState): RecipientFieldState =
        RecipientFieldState(chips(field.chips, field.input), "")

    private fun chips(current: List<RecipientChip>, text: String): List<RecipientChip> =
        AddressText.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .fold(current) { chips, entry -> chips.with(chipOf(entry)) }

    private fun chipOf(entry: String): RecipientChip {
        val parsed = RecipientParser.parse(entry)
        return RecipientChip(if (parsed != null) RecipientParser.format(parsed) else entry, parsed)
    }

    private fun List<RecipientChip>.with(chip: RecipientChip): List<RecipientChip> {
        val address = chip.address?.address?.lowercase()
        val repeated = if (address != null) {
            any { it.address?.address?.lowercase() == address }
        } else {
            any { it.text == chip.text }
        }
        return if (repeated) this else this + chip
    }

    /** A suggestion was chosen: it becomes a chip and the typed text is cleared. */
    fun pick(field: RecipientFieldState, address: MailAddress): RecipientFieldState =
        RecipientFieldState(
            field.chips.with(RecipientChip(RecipientParser.format(address), address)),
            ""
        )

    fun remove(field: RecipientFieldState, index: Int): RecipientFieldState =
        if (index in field.chips.indices) {
            field.copy(chips = field.chips.filterIndexed { i, _ -> i != index })
        } else {
            field
        }
}
