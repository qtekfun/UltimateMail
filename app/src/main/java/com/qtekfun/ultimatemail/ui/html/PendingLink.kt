// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

/** A link the reader tapped and has not yet confirmed. */
data class PendingLink(val target: String, val deceptive: Boolean)
