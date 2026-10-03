// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.model

/** How the connection to a mail server is secured. Plain text is never offered (SPEC §6). */
enum class ConnectionSecurity { TLS, STARTTLS }

/** How an account authenticates (RF-01). */
enum class AuthType { PASSWORD, OAUTH_GOOGLE, OAUTH_MICROSOFT }

/** Special folders, detected with IMAP SPECIAL-USE (RF-02). */
enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, ARCHIVE, JUNK, ALL_MAIL, STARRED, OTHER }

/** A local change waiting to be sent to the server (RF-10). */
enum class OperationType { SET_FLAGS, MOVE, ADD_LABEL, REMOVE_LABEL, DELETE, SAVE_DRAFT, SEND }

/** Where the content of an attachment is (RF-04). */
enum class AttachmentState { REMOTE, DOWNLOADING, DOWNLOADED, FAILED }
