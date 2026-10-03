// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.qtekfun.ultimatemail.domain.html.RequestPolicy

/**
 * Opens a link from a message outside the app with an `ACTION_VIEW` intent, after the reader
 * confirmed it. Only web, mail and phone links are opened; returns false when [target] is not
 * one of those or no app can handle it.
 */
fun openExternally(context: Context, target: String): Boolean {
    val safe = RequestPolicy.externalLink(target)
    val intent = safe?.let {
        Intent(Intent.ACTION_VIEW, it.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return intent != null && runCatching { context.startActivity(intent) }.isSuccess
}
