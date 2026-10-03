// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.annotation.StringRes
import com.qtekfun.ultimatemail.R

/** The text of a snackbar message. */
@StringRes
fun NoticeKind.messageRes(): Int = when (this) {
    NoticeKind.ARCHIVED -> R.string.notice_archived
    NoticeKind.DELETED -> R.string.notice_deleted
    NoticeKind.NO_ARCHIVE_FOLDER -> R.string.notice_no_archive
    NoticeKind.NO_TRASH_FOLDER -> R.string.notice_no_trash
    NoticeKind.COMPOSE_SOON -> R.string.notice_compose_soon
    NoticeKind.ATTACHMENT_FAILED -> R.string.notice_attachment_failed
    NoticeKind.ATTACHMENT_GONE -> R.string.notice_attachment_gone
    NoticeKind.ATTACHMENT_SAVED -> R.string.notice_attachment_saved
    NoticeKind.ATTACHMENT_NOT_SAVED -> R.string.notice_attachment_not_saved
    NoticeKind.NO_APP_FOR_ATTACHMENT -> R.string.notice_no_app
}
