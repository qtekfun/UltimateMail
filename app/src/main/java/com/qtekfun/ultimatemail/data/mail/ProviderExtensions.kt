// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import jakarta.mail.FetchProfile
import jakarta.mail.Message
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

/**
 * The seam for server extensions that plain IMAP does not have. Today that is Gmail's
 * X-GM-EXT-1 (thread and message ids, labels). The session only talks to this interface, so
 * its handling is tested with fakes; GreenMail cannot play Gmail.
 */
interface ProviderExtensions {
    /** True when the connected server offers the extension. */
    fun isAvailable(store: IMAPStore): Boolean

    /** What to add to a header fetch so [metadata] has data to read. */
    fun fetchItems(): List<FetchProfile.Item>

    fun metadata(message: Message): GmailMetadata?

    /** Adds ([add] true) or removes [labels] on [messages], all in [folder]. */
    fun changeLabels(folder: IMAPFolder, messages: List<Message>, labels: Set<String>, add: Boolean)
}
