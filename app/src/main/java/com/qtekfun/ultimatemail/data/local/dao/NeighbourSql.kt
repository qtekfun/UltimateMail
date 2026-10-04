// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

/*
 * The previous and next conversation of a list, for the arrows of the reading screen. A
 * conversation sits in the list where its newest visible message sits, so the neighbour is the
 * nearest conversation by (sentAt, id) of that message, found with one indexed step instead of
 * loading the list. A conversation opened from a folder list is in that folder, so the folder
 * queries need no more than the conversation itself. ANCHOR is that position for the
 * conversation `:accountId`, `:folderPath`, `:threadId`; when none of its messages is visible
 * any more it is null and nothing matches.
 * Constants, so the query plan can be tested.
 */
private const val ANCHOR = "(SELECT t.sentAt, t.id FROM message t INDEXED BY $THREAD_INDEX " +
    "WHERE t.accountId = :accountId AND t.folderPath = :folderPath " +
    "AND t.threadId = :threadId AND $VISIBLE_T ORDER BY t.sentAt DESC, t.id DESC LIMIT 1)"

private const val IS_LATEST = "AND m.id = (SELECT t.id FROM message t INDEXED BY $THREAD_INDEX " +
    "WHERE t.accountId = m.accountId AND t.folderPath = m.folderPath " +
    "AND t.threadId = m.threadId AND $VISIBLE_T ORDER BY t.sentAt DESC, t.id DESC LIMIT 1) "

private const val IS_UNREAD = "AND (:unreadOnly = 0 OR EXISTS (SELECT 1 FROM message t " +
    "WHERE t.accountId = m.accountId AND t.folderPath = m.folderPath " +
    "AND t.threadId = m.threadId AND t.seen = 0 AND $VISIBLE_T)) "

private const val NEIGHBOUR_SELECT = "SELECT m.accountId, m.folderPath, m.threadId FROM message m "

private const val IN_FOLDER = "WHERE m.accountId = :accountId AND m.folderPath = :folderPath "

private const val IN_UNIFIED = "JOIN folder f ON f.accountId = m.accountId " +
    "AND f.path = m.folderPath WHERE f.role = 'INBOX' "

private const val VISIBLE = "AND $VISIBLE_M "

private const val NEWER = "AND (m.sentAt, m.id) > $ANCHOR "

private const val OLDER = "AND (m.sentAt, m.id) < $ANCHOR "

private const val NEAREST_NEWER = "ORDER BY m.sentAt ASC, m.id ASC LIMIT 1"

private const val NEAREST_OLDER = "ORDER BY m.sentAt DESC, m.id DESC LIMIT 1"

internal const val NEWER_IN_FOLDER_SQL =
    NEIGHBOUR_SELECT + IN_FOLDER + VISIBLE + NEWER + IS_LATEST + IS_UNREAD + NEAREST_NEWER

internal const val OLDER_IN_FOLDER_SQL =
    NEIGHBOUR_SELECT + IN_FOLDER + VISIBLE + OLDER + IS_LATEST + IS_UNREAD + NEAREST_OLDER

internal const val NEWER_IN_UNIFIED_SQL =
    NEIGHBOUR_SELECT + IN_UNIFIED + VISIBLE + NEWER + IS_LATEST + IS_UNREAD + NEAREST_NEWER

internal const val OLDER_IN_UNIFIED_SQL =
    NEIGHBOUR_SELECT + IN_UNIFIED + VISIBLE + OLDER + IS_LATEST + IS_UNREAD + NEAREST_OLDER
