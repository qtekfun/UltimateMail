// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/** In-memory [OAuthClientIds] for tests; like the real one, an empty text removes the ID. */
class MemoryClientIds(var savedGoogle: String? = null, var savedMicrosoft: String? = null) :
    OAuthClientIds {
    override fun google(): String? = savedGoogle?.takeIf { it.isNotEmpty() }

    override fun microsoft(): String? = savedMicrosoft?.takeIf { it.isNotEmpty() }

    override fun setGoogle(clientId: String) {
        savedGoogle = clientId
    }

    override fun setMicrosoft(clientId: String) {
        savedMicrosoft = clientId
    }
}
