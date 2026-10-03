// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.RoomRawQuery
import com.qtekfun.ultimatemail.domain.search.FtsPlan
import com.qtekfun.ultimatemail.domain.search.FtsQueryBuilder
import com.qtekfun.ultimatemail.domain.search.LabelMatch
import com.qtekfun.ultimatemail.domain.search.SearchQuery
import com.qtekfun.ultimatemail.domain.search.SearchScope
import java.time.LocalDate
import java.time.ZoneId

/**
 * The SQL of a local search. The text is assembled from fixed pieces only, one per part of the
 * query that is present, and everything that comes from the user (search words, folder names,
 * labels, dates) travels as a bound argument, never inside the text. That keeps the SQL short
 * and its plan good for every combination (a fixed statement full of `:x IS NULL OR ...` would
 * make SQLite scan), and leaves no way for input to change the statement.
 *
 * Hits are messages ordered by recency; each carries the counters of its conversation like the
 * rows of the inbox. Messages with a queued move or delete are not hits (see [VISIBLE_M]).
 */
internal class SearchStatement(val sql: String, val args: List<Any>) {
    fun toRawQuery() = RoomRawQuery(sql) { statement ->
        args.forEachIndexed { index, arg ->
            when (arg) {
                is Long -> statement.bindLong(index + 1, arg)
                else -> statement.bindText(index + 1, arg.toString())
            }
        }
    }
}

internal object SearchSql {
    /** Separates the entries of a stored list (see `Converters`). */
    private const val LIST_SEPARATOR = "\u001F"
    private const val ESCAPE = "\\"
    private const val LIKE_ANY = "%"

    /** Trash and Spam only show up in a search that names a folder or asks for one by `in:`. */
    private const val NOT_IN_TRASH = "NOT EXISTS (SELECT 1 FROM folder x " +
        "WHERE x.accountId = m.accountId AND x.path = m.folderPath " +
        "AND x.role IN ('TRASH', 'JUNK'))"

    private const val COUNTS = "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
        "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND $VISIBLE_T) " +
        "AS messageCount, " +
        "(SELECT COUNT(*) FROM message t WHERE t.accountId = m.accountId " +
        "AND t.folderPath = m.folderPath AND t.threadId = m.threadId AND t.seen = 0 " +
        "AND $VISIBLE_T) AS unreadCount"

    /** The statement for the newest [limit] hits of [query] in [scope]; [zone] fixes the days. */
    fun build(query: SearchQuery, scope: SearchScope, zone: ZoneId, limit: Int): SearchStatement {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val plan = FtsQueryBuilder.plan(query)
        scope(scope, query, indexed = plan.matches.isEmpty(), conditions, args)
        conditions += VISIBLE_M
        fullText(plan, conditions, args)
        recipients(query, conditions, args)
        labels(query, conditions, args)
        flags(query, conditions)
        dates(query, zone, conditions, args)
        args += limit.toLong()
        val sql = "SELECT m.*, $COUNTS FROM message m WHERE ${conditions.joinToString(" AND ")} " +
            "ORDER BY m.sentAt DESC, m.id DESC LIMIT ?"
        return SearchStatement(sql, args)
    }

    /**
     * With words to look for, the full-text index finds the few candidate rows and the scope only
     * checks them: the leading `+` keeps SQLite from walking every message of the account or
     * folder through an index instead. Without words, the scope is what the index serves.
     */
    private fun scope(
        scope: SearchScope,
        query: SearchQuery,
        indexed: Boolean,
        conditions: MutableList<String>,
        args: MutableList<Any>
    ) {
        val plus = if (indexed) "" else "+"
        scope.accountId?.let {
            conditions += "${plus}m.accountId = ?"
            args += it
        }
        if (scope is SearchScope.Folder) {
            conditions += "${plus}m.folderPath = ?"
            args += scope.path
        } else if (query.labels.isEmpty()) {
            conditions += NOT_IN_TRASH
        }
    }

    private fun fullText(plan: FtsPlan, conditions: MutableList<String>, args: MutableList<Any>) {
        if (plan.matches.isNotEmpty()) {
            val any = plan.matches.joinToString(" UNION ") {
                "SELECT docid FROM message_fts WHERE message_fts MATCH ?"
            }
            conditions += "m.id IN ($any)"
            args.addAll(plan.matches)
        }
        plan.excludes.forEach {
            conditions += "m.id NOT IN (SELECT docid FROM message_fts WHERE message_fts MATCH ?)"
            args += it
        }
    }

    /** `to:` looks at the To and Cc addresses, which are not in the full-text index. */
    private fun recipients(
        query: SearchQuery,
        conditions: MutableList<String>,
        args: MutableList<Any>
    ) {
        query.to.forEach { term ->
            conditions += "(m.toAddresses LIKE ? ESCAPE '\\' OR m.ccAddresses LIKE ? ESCAPE '\\')"
            val pattern = LIKE_ANY + likeEscape(term.text.trim()) + LIKE_ANY
            args += pattern
            args += pattern
        }
    }

    /**
     * `label:` / `in:` matches the folder the message is in (by role, name or path) or, for
     * Gmail, one of its labels (also written with its leading backslash, as in `\Inbox`).
     */
    private fun labels(
        query: SearchQuery,
        conditions: MutableList<String>,
        args: MutableList<Any>
    ) {
        query.labels.forEach { label ->
            conditions += "(EXISTS (SELECT 1 FROM folder lf WHERE lf.accountId = m.accountId " +
                "AND lf.path = m.folderPath AND (lf.role = ? OR lf.name LIKE ? ESCAPE '\\' " +
                "OR lf.path LIKE ? ESCAPE '\\')) OR (char(31) || m.labels || " +
                "char(31)) LIKE ? ESCAPE '\\' OR (char(31) || m.labels || " +
                "char(31)) LIKE ? ESCAPE '\\')"
            val exact = likeEscape(label)
            args += LabelMatch.role(label)?.name.orEmpty()
            args += exact
            args += exact
            args += LIKE_ANY + LIST_SEPARATOR + exact + LIST_SEPARATOR + LIKE_ANY
            args += LIKE_ANY + LIST_SEPARATOR + ESCAPE + ESCAPE + exact + LIST_SEPARATOR + LIKE_ANY
        }
    }

    private fun flags(query: SearchQuery, conditions: MutableList<String>) {
        query.unread?.let { conditions += if (it) "m.seen = 0" else "m.seen = 1" }
        if (query.starred) conditions += "m.flagged = 1"
        if (query.hasAttachment) conditions += "m.hasAttachments = 1"
    }

    private fun dates(
        query: SearchQuery,
        zone: ZoneId,
        conditions: MutableList<String>,
        args: MutableList<Any>
    ) {
        query.after?.let {
            conditions += "m.sentAt >= ?"
            args += startOf(it, zone)
        }
        query.before?.let {
            conditions += "m.sentAt < ?"
            args += startOf(it, zone)
        }
    }

    private fun startOf(day: LocalDate, zone: ZoneId): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** [text] as a LIKE pattern that matches it literally (escape character is a backslash). */
    internal fun likeEscape(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
