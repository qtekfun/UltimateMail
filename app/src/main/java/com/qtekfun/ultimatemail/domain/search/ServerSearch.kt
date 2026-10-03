// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

/** Why a search on the server gave nothing; a reason code for the UI, never server text. */
enum class ServerSearchFailure {
    /** The server could not be reached. */
    OFFLINE,

    /** The server did not answer in time. */
    TIMEOUT,

    /** The user has to sign in again (RF-01). */
    AUTHENTICATION,

    /** The server refused the search or answered something unusable. */
    SERVER,

    /** The scope has no account or no folder to search. */
    NOTHING_TO_SEARCH
}

/** The outcome of a [ServerSearch]. */
sealed interface ServerSearchResult {
    /**
     * [messageIds] are rows of Room (the hits the device already had and the ones just stored),
     * [added] how many of them are new. [incomplete] is true when some account or folder could
     * not be searched, so there may be more on the server.
     */
    data class Found(val messageIds: List<Long>, val added: Int, val incomplete: Boolean) :
        ServerSearchResult

    data class Failed(val reason: ServerSearchFailure) : ServerSearchResult
}

/**
 * Searches the mail servers of a [SearchScope] (RF-09): the hits end up as messages in Room, where
 * the search screen shows them with the local ones. Cancelling the calling coroutine cancels the
 * search.
 */
fun interface ServerSearch {
    suspend fun search(query: SearchQuery, scope: SearchScope): ServerSearchResult
}
