// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/**
 * Turns the file name a sender chose into one that is safe to create in app storage: whatever the
 * message says, the result has no directory part, no control characters and no leading dot.
 */
object AttachmentFileNames {
    private const val MAX_LENGTH = 100
    private val unsafe = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]")

    /** A safe file name for [name]; [fallback] (never empty) stands in when nothing is left. */
    fun safe(name: String?, fallback: String): String {
        val cleaned = unsafe.replace(name.orEmpty(), "_").trim().trimStart('.')
        val shortened = if (cleaned.length > MAX_LENGTH) shorten(cleaned) else cleaned
        return shortened.ifBlank { fallback }
    }

    /** Cuts the base name but keeps a short extension, so the file still opens in the right app. */
    private fun shorten(name: String): String {
        val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXTENSION }
        val base = name.removeSuffix(extension?.let { ".$it" }.orEmpty())
        val room = MAX_LENGTH - (extension?.length?.plus(1) ?: 0)
        return base.take(room) + extension?.let { ".$it" }.orEmpty()
    }

    private const val MAX_EXTENSION = 10
}
