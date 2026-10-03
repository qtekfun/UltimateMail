// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy

/** What the notice about blocked remote content says. */
enum class RemoteBanner {
    /** Policy "never load": images stay blocked; the reader may still load them for this mail. */
    BLOCKED,

    /** Policy "ask": blocked until the reader says otherwise for this mail. */
    ASK
}

/** When the banner is shown, and in which form (RF-04, RF-11). */
object RemoteBanners {
    /**
     * A banner when remote content was blocked in the message and the reader has not allowed it
     * for this message yet; none otherwise. The policy only decides the wording: neither policy
     * ever loads anything by itself.
     */
    fun of(
        policy: RemoteContentPolicy,
        blockedRemoteContent: Boolean,
        allowedForMessage: Boolean
    ): RemoteBanner? = when {
        allowedForMessage || !blockedRemoteContent -> null
        policy == RemoteContentPolicy.NEVER -> RemoteBanner.BLOCKED
        else -> RemoteBanner.ASK
    }
}
