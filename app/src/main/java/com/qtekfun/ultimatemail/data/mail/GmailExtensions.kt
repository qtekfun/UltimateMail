// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import jakarta.mail.FetchProfile
import jakarta.mail.Message
import org.eclipse.angus.mail.gimap.GmailFolder
import org.eclipse.angus.mail.gimap.GmailMessage
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

/**
 * Gmail's IMAP extensions through Angus' gimap module. Not run against a real Gmail yet: that
 * needs credentials (task T02). The detection part is covered on GreenMail, which answers that
 * the extension is absent.
 */
class GmailExtensions : ProviderExtensions {
    override fun isAvailable(store: IMAPStore): Boolean = store.hasCapability(CAPABILITY)

    override fun fetchItems(): List<FetchProfile.Item> = listOf(
        GmailFolder.FetchProfileItem.MSGID,
        GmailFolder.FetchProfileItem.THRID,
        GmailFolder.FetchProfileItem.LABELS
    )

    override fun metadata(message: Message): GmailMetadata? = (message as? GmailMessage)?.let {
        GmailMetadata(
            threadId = it.thrId,
            messageId = it.msgId,
            labels = it.labels.orEmpty().toList()
        )
    }

    override fun changeLabels(
        folder: IMAPFolder,
        messages: List<Message>,
        labels: Set<String>,
        add: Boolean
    ) {
        val gmailFolder = checkNotNull(folder as? GmailFolder) { "Not a Gmail folder" }
        gmailFolder.setLabels(messages.toTypedArray(), labels.toTypedArray(), add)
    }

    private companion object {
        const val CAPABILITY = "X-GM-EXT-1"
    }
}
