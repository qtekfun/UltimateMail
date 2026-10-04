// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/** The localized phrases of a row description; the UI fills them from string resources. */
interface DescriptionTexts {
    val unread: String
    val noSubject: String
    val hasAttachment: String
    val flagged: String
    val pendingSync: String

    fun from(sender: String): String

    fun messages(count: Int): String

    fun labels(names: String): String

    fun account(name: String): String
}

/** Builds the single TalkBack sentence that replaces the many parts of a conversation row. */
class ConversationDescriber(private val texts: DescriptionTexts) {
    /**
     * "Unread, from Ana, Lunch, 12:30, 3 messages, has attachment, ..., snippet". Parts that do
     * not apply are left out; [time] is the already formatted time to speak. The snippet is only
     * read when [includeSnippet] is set, that is when the user has the preview on screen.
     */
    fun describe(
        item: ConversationItem,
        time: String,
        labels: LabelSummary = LabelSummary.EMPTY,
        account: AccountMarker? = null,
        includeSnippet: Boolean = true
    ): String = buildList {
        if (item.unread) add(texts.unread)
        add(texts.from(item.sender))
        add(item.subject.ifBlank { texts.noSubject })
        add(time)
        if (item.messageCount > 1) add(texts.messages(item.messageCount))
        if (item.hasAttachments) add(texts.hasAttachment)
        if (item.flagged) add(texts.flagged)
        if (!labels.isEmpty) {
            val names = labels.chips.joinToString(", ") { it.text }
            add(texts.labels(if (labels.overflow > 0) "$names +${labels.overflow}" else names))
        }
        if (account != null) add(texts.account(account.name))
        if (item.pendingSync) add(texts.pendingSync)
        if (includeSnippet && item.snippet.isNotBlank()) add(item.snippet.trim())
    }.joinToString(", ")
}
