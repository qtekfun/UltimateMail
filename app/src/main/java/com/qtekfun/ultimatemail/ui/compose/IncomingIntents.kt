// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import android.content.Intent
import android.net.Uri
import com.qtekfun.ultimatemail.domain.compose.IncomingCompose
import com.qtekfun.ultimatemail.domain.compose.IncomingIntent
import com.qtekfun.ultimatemail.domain.compose.IncomingParser

/**
 * Reads an Android [Intent] into the plain [IncomingIntent] and lets [IncomingParser] judge it.
 * Extras of another app are never trusted: any extra that is missing, of another type or that
 * makes the bundle fail to unparcel is left out. Null when the intent is not for the composer.
 */
fun Intent.toIncomingCompose(ownAuthority: String): IncomingCompose? {
    val raw = runCatching { toIncomingIntent() }.getOrNull() ?: return null
    return IncomingParser.parse(raw, setOf(ownAuthority))
}

@Suppress("DEPRECATION") // The typed overloads need API 33; minSdk is 26.
private fun Intent.toIncomingIntent(): IncomingIntent {
    fun strings(name: String): List<String> =
        runCatching { getStringArrayExtra(name)?.toList() }.getOrNull().orEmpty()

    fun text(name: String): String? =
        runCatching { getCharSequenceExtra(name)?.toString() }.getOrNull()

    val streams = when (action) {
        Intent.ACTION_SEND ->
            listOfNotNull(runCatching { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull())

        Intent.ACTION_SEND_MULTIPLE ->
            runCatching { getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
                .orEmpty()
                .filterNotNull()

        else -> emptyList()
    }
    return IncomingIntent(
        action = action,
        data = dataString,
        text = text(Intent.EXTRA_TEXT),
        subject = text(Intent.EXTRA_SUBJECT),
        to = strings(Intent.EXTRA_EMAIL),
        cc = strings(Intent.EXTRA_CC),
        bcc = strings(Intent.EXTRA_BCC),
        streams = streams.map { it.toString() }
    )
}
