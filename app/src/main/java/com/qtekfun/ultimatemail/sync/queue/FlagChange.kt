// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

/**
 * The payload of a SET_FLAGS operation: absolute values, so applying it twice is harmless. A null
 * flag is left as it is on the server.
 */
data class FlagChange(val seen: Boolean? = null, val flagged: Boolean? = null) {
    /** Where this change comes later than [earlier], it wins; elsewhere [earlier] still applies. */
    fun over(earlier: FlagChange) =
        FlagChange(seen = seen ?: earlier.seen, flagged = flagged ?: earlier.flagged)

    /** Two characters, one per flag: `1` set, `0` cleared, `-` untouched. */
    fun encode() = "${CODES[STATES.indexOf(seen)]}${CODES[STATES.indexOf(flagged)]}"

    companion object {
        private const val CODES = "10-"
        private val STATES = listOf(true, false, null)

        fun decode(payload: String): FlagChange {
            require(payload.length == 2 && payload.all { it in CODES }) { "Malformed flag change" }
            return FlagChange(
                seen = STATES[CODES.indexOf(payload[0])],
                flagged = STATES[CODES.indexOf(payload[1])]
            )
        }
    }
}
