// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.webkit.WebResourceResponse

/** Supplies the bytes of a `cid:` part of the message, or null when there is no such part. */
fun interface CidResolver {
    fun resolve(cidUrl: String): WebResourceResponse?
}
