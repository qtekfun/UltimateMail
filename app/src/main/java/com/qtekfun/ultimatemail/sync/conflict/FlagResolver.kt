// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/**
 * Rules 1 and 5 for flags: the last change wins, and pending operations are laid over the server
 * state after every sync so the user's change is never undone by a refresh.
 */
object FlagResolver {
    fun resolve(server: Flags, pending: List<PendingFlagOperation>): FlagResolution {
        val ordered = pending.sortedWith(compareBy({ it.createdAt }, { it.id }))
        // For each flag, the latest operation that touches it is the one that counts.
        val winners = Flag.entries.mapNotNull { flag ->
            ordered.lastOrNull {
                it.patch[flag] != null
            }?.let { Winner(flag, it.id, it.patch[flag]) }
        }
        val merged = winners.fold(server) { flags, w -> flags.with(w.flag, w.value == true) }
        val needed = winners.filter { it.value != server[it.flag] }.map { it.id }.toSet()
        val (remaining, completed) = ordered.map { it.id }.partition { it in needed }
        return FlagResolution(merged, remaining, completed)
    }

    private data class Winner(val flag: Flag, val id: Long, val value: Boolean?)
}
