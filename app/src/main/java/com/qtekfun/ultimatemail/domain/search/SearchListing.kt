// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.ConversationSummary
import com.qtekfun.ultimatemail.data.local.dao.SearchSql
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One page of local results. [hasMore] is true when the query filled its limit, so asking for a
 * larger limit may bring more.
 */
data class SearchPage(val items: List<ConversationItem>, val hasMore: Boolean)

/**
 * Local search over the mail kept in Room (RF-09): subject, sender and every body already
 * downloaded. Results are conversations, newest first, as rows of the inbox list.
 */
class SearchListing @Inject constructor(database: UltimateMailDatabase) {
    private val conversations = database.conversationDao()

    /**
     * The newest [limit] messages matching [query] in [scope], one row per conversation; it
     * re-emits when mail changes. [zone] is the user's, because `after:` and `before:` are days.
     */
    fun observe(
        query: SearchQuery,
        scope: SearchScope,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Flow<SearchPage> {
        val statement = SearchSql.build(query, scope, zone, limit)
        return conversations.observeSearch(statement.toRawQuery()).map { hits ->
            SearchPage(distinct(hits).map { it.toItem(query) }, hasMore = hits.size >= limit)
        }
    }

    /** The rows of the messages with [ids] (found on the server), newest first. */
    fun observeByIds(ids: List<Long>, query: SearchQuery): Flow<List<ConversationItem>> =
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            conversations.observeByIds(ids).map { hits -> distinct(hits).map { it.toItem(query) } }
        }

    /**
     * A conversation shows once however many of its messages match, and so does a Gmail message
     * that is in several folders (every label is a folder there). Hits come newest first, so
     * the first of each is the one to keep.
     */
    private fun distinct(hits: List<ConversationSummary>): List<ConversationSummary> {
        val seenKeys = HashSet<String>()
        val seenGmail = HashSet<Pair<Long, Long>>()
        return hits.filter { hit ->
            val message = hit.latest
            val key = "${message.accountId}|${message.folderPath}|${message.threadId}"
            val gmail = message.gmailMessageId?.let { message.accountId to it }
            seenKeys.add(key) && (gmail == null || seenGmail.add(gmail))
        }
    }

    private fun ConversationSummary.toItem(query: SearchQuery) = ConversationItem(
        accountId = latest.accountId,
        folderPath = latest.folderPath,
        threadId = latest.threadId,
        latestMessageId = latest.id,
        senderName = latest.senderName,
        senderAddress = latest.senderAddress,
        subject = latest.subject,
        snippet = SnippetExcerpt.of(latest.snippet, latest.bodyText, query.terms),
        sentAt = latest.sentAt,
        messageCount = messageCount,
        unreadCount = unreadCount,
        flagged = latest.flagged,
        hasAttachments = latest.hasAttachments,
        labels = latest.labels,
        pendingSync = latest.pendingSync
    )
}
