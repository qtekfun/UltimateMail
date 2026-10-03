// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import android.content.res.Resources
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.picker.PickerOutcome
import com.qtekfun.ultimatemail.domain.picker.PickerResult

/**
 * The text for the snackbar a caller shows after the picker, for example "Moved 2 messages to
 * Invoices". Pair it with an action labelled `R.string.picker_undo`.
 */
fun PickerResult.message(resources: Resources): String = when (val done = outcome) {
    is PickerOutcome.Moved -> resources.getQuantityString(
        R.plurals.picker_result_moved,
        messageCount,
        messageCount,
        done.name
    )

    is PickerOutcome.LabelsChanged -> resources.getString(R.string.picker_result_labels)

    PickerOutcome.Archived -> resources.getString(R.string.picker_result_archived)
}
