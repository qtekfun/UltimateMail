// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.qtekfun.ultimatemail.R

/** The text of a snackbar message; the ones about conversations say how many. */
fun Resources.noticeText(notice: ConversationNotice): String {
    notice.text?.let { return it }
    val plural = notice.kind.pluralRes()
    return if (plural != null) {
        getQuantityString(plural, notice.count, notice.count)
    } else {
        getString(notice.kind.messageRes())
    }
}

@PluralsRes
private fun NoticeKind.pluralRes(): Int? = when (this) {
    NoticeKind.ARCHIVED -> R.plurals.notice_archived
    NoticeKind.DELETED -> R.plurals.notice_deleted
    NoticeKind.MARKED_READ -> R.plurals.notice_marked_read
    NoticeKind.MARKED_UNREAD -> R.plurals.notice_marked_unread
    NoticeKind.STARRED -> R.plurals.notice_starred
    NoticeKind.UNSTARRED -> R.plurals.notice_unstarred
    else -> null
}

@StringRes
private fun NoticeKind.messageRes(): Int = when (this) {
    NoticeKind.NO_ARCHIVE_FOLDER -> R.string.notice_no_archive
    NoticeKind.NO_TRASH_FOLDER -> R.string.notice_no_trash
    NoticeKind.MOVE_SOON -> R.string.notice_move_soon
    NoticeKind.CUSTOM -> error("a custom notice carries its text")
    NoticeKind.COMPOSE_SOON -> R.string.notice_compose_soon
    NoticeKind.ATTACHMENT_FAILED -> R.string.notice_attachment_failed
    NoticeKind.ATTACHMENT_GONE -> R.string.notice_attachment_gone
    NoticeKind.ATTACHMENT_SAVED -> R.string.notice_attachment_saved
    NoticeKind.ATTACHMENT_NOT_SAVED -> R.string.notice_attachment_not_saved
    NoticeKind.NO_APP_FOR_ATTACHMENT -> R.string.notice_no_app
    else -> error("$this has a plural text")
}
