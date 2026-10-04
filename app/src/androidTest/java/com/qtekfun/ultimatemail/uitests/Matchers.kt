// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText

/**
 * The row of a conversation in a list. A row speaks as one sentence ("Unread, From Bob, subject,
 * ..."), so it is found by a part of its content description.
 */
fun conversationRow(subject: String): SemanticsMatcher =
    hasContentDescription(subject, substring = true)

/** A text button or row with [label] that can be tapped. */
fun tappable(label: String): SemanticsMatcher = hasText(label) and hasClickAction()
