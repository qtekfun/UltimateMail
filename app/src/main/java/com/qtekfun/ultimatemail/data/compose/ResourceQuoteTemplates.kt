// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.compose

import android.content.Context
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.compose.QuoteTemplates
import com.qtekfun.ultimatemail.domain.compose.QuoteTemplatesProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject

/** The quote texts in the language the app is showing, read from strings.xml (`compose_*`). */
class ResourceQuoteTemplates @Inject constructor(@ApplicationContext private val context: Context) :
    QuoteTemplatesProvider {
    override fun current(): QuoteTemplates = QuoteTemplates(
        attribution = context.getString(R.string.compose_attribution),
        forwardMarker = context.getString(R.string.compose_forward_marker),
        fromLabel = context.getString(R.string.compose_header_from),
        dateLabel = context.getString(R.string.compose_header_date),
        subjectLabel = context.getString(R.string.compose_header_subject),
        toLabel = context.getString(R.string.compose_header_to),
        ccLabel = context.getString(R.string.compose_header_cc),
        locale = context.resources.configuration.locales[0],
        zone = ZoneId.systemDefault()
    )
}
